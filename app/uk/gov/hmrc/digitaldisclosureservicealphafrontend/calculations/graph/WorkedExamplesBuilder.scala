/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  MultiYearLiabilityResult,
  SessionState,
  WorkedExampleScenario
}

/** Runs the sample customers in `worked-examples.json` through the session's config. */
object WorkedExamplesBuilder:

  def build(
    state    : SessionState,
    translate: String => String = identity,
    scenarios: Seq[WorkedExampleScenario] = DefaultConfigs.workedExamples
  ): Seq[GraphExample] =
    scenarios.map(example(state, _, translate))

  private def example(state: SessionState, scenario: WorkedExampleScenario, translate: String => String): GraphExample =
    val allAnswers = scenario.answersFor(state.rateCatalog.taxYears.take(scenario.taxYearCount))
    val visible = QuestionEngine.visibleQuestions(state.questionPack, state.rateCatalog, allAnswers, translate)
    val answers = allAnswers.filter((id, _) => visible.exists(_.id == id))

    val journey = visible.map: q =>
      ExampleJourneyStep(q.id, q.title, answers.get(q.id).fold("Shown from config rules")(v => s"Answer: $v"))

    val result = LiabilityCalculator.calculate(state.rateCatalog, state.calculationSpec, answers)
    val yearCalcs = result.years.map: year =>
      ExampleYearCalc(
        taxYear = year.taxYear,
        ops = year.explanations.map: step =>
          ExampleCalcOp(CalculationsI18n.breakdownLabel(step.label, translate), step.operation, step.result),
        taxDue = Formats.gbp(year.taxDue)
      )

    GraphExample(
      id = scenario.id,
      titleKey = scenario.title,
      summaryKey = scenario.summary,
      answers = answers.toSeq.sortBy(_._1),
      journeySteps = journey,
      computation = computation(result, state.calculationSpec, translate),
      yearCalcs = yearCalcs,
      totalTaxDue = Formats.gbp(result.totalTaxDue)
    )

  private val Zero = Formats.gbp(0)

  /**
    * Lines up each year's explanations into rows. Relies on LiabilityExplanations emitting
    * them in spec order: income, total, allowances, taxable, bands, gross tax, tax paid, tax due.
    */
  private def computation(
    result   : MultiYearLiabilityResult,
    spec     : CalculationSpec,
    translate: String => String
  ): Computation =
    import ComputationRowKind.*
    val incomeCount = spec.incomeComponents.size
    val allowanceCount = spec.allowances.size
    val kinds =
      Seq.fill(incomeCount)(item) ++ Seq(subtotal) ++
        Seq.fill(allowanceCount)(deduction) ++ Seq(subtotal) ++
        Seq.fill(spec.tax.bands.size)(item) ++ Seq(subtotal, deduction, total)
    val foldable = (0 until incomeCount).toSet ++ (incomeCount + 1 to incomeCount + allowanceCount).toSet

    val byYear = result.years.map(_.explanations)
    val rowCount = byYear.headOption.map(_.size).getOrElse(0)
    val rows = (0 until rowCount).map: i =>
      val kind = kinds.lift(i).getOrElse(item)
      val baseLabel = byYear.head(i).label.split("\\.withYear:").head
      val cells = byYear.map: explanations =>
        explanations.lift(i).fold(ComputationCell("—")): e =>
          val amount = if kind == deduction && e.result != Zero then s"−${e.result}" else e.result
          ComputationCell(amount, e.working)
      i -> ComputationRow(CalculationsI18n.text(baseLabel, translate), kind, cells)

    val (zero, shown) = rows.partition: (i, row) =>
      foldable.contains(i) && row.cells.forall(_.amount == Zero)

    Computation(
      years = result.years.map(_.taxYear),
      rows = shown.map(_._2),
      zeroItems = zero.map(_._2.label)
    )

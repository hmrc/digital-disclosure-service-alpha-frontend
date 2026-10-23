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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel

import org.camunda.feel.syntaxtree.ParsedExpression
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  CalcExplanation,
  LiabilityResult,
  MultiYearLiabilityResult,
  QuestionPack,
  QuestionType,
  RateCatalog,
  RatePack,
  YearAnswers
}

import scala.math.BigDecimal.RoundingMode

/** What a year's expressions can read: `answers.<question id>` and `rates.<rate key>`. */
final case class FeelInputs(answers: Map[String, Any], rates: Map[String, BigDecimal])

/** One step's value for a tax year: a BigDecimal, or a Boolean for a yes/no step. */
final case class FeelStepValue(step: FeelStep, value: Any)

/** Every step's value for one tax year, in rule order. */
final case class FeelYear(rates: RatePack, inputs: FeelInputs, values: Seq[FeelStepValue]):
  def taxYear: String = rates.taxYear

  def valueOf(stepId: String): Option[Any] = values.find(_.step.id == stepId).map(_.value)

  def number(stepId: String): BigDecimal =
    valueOf(stepId).collect { case n: BigDecimal => n }.getOrElse(BigDecimal(0))

  def flag(stepId: String): Boolean =
    valueOf(stepId).collect { case b: Boolean => b }.getOrElse(false)

/** A step that gave no usable value: invalid FEEL types, a missing name, or nothing at all. */
final case class FeelStepFailure(stepIndex: Int, step: FeelStep, taxYear: String, message: String)

final class FeelCalculationFailed(failure: FeelStepFailure)
    extends RuntimeException(s"FEEL step '${failure.step.id}' failed for ${failure.taxYear}: ${failure.message}")

/**
  * Works out each tax year by evaluating the FEEL steps in order. Each step can read the answers, the year's
  * rates, the steps before it and the groups they built. Only answers to questions the user can currently
  * see are read, so an answer left behind by a changed earlier answer does not count.
  */
object FeelCalculator:

  def calculate(
    catalog  : RateCatalog,
    pack     : QuestionPack,
    rules    : CompiledFeelRules,
    answers  : Map[String, String],
    translate: String => String = identity
  ): MultiYearLiabilityResult =
    val years = QuestionEngine.selectedTaxYears(answers) match
      case scala.Nil => catalog.taxYears
      case selected  => selected.filter(y => catalog.forYear(y).isDefined)

    val vocabulary = FeelVocabulary(rules, pack, catalog, translate)
    val yearResults = years.flatMap(catalog.forYear).map: rates =>
      year(rates, catalog, pack, rules, answers) match
        case Right(figures) => result(rules, figures, vocabulary)
        case Left(failure)  => throw FeelCalculationFailed(failure)

    val totalTaxDue = pence(yearResults.map(_.taxDue).sum)
    val totalPenalty = pence(yearResults.map(_.penaltyAmount).sum)
    MultiYearLiabilityResult(
      catalogVersion = catalog.version,
      calculationVersion = s"${rules.rules.id} ${rules.rules.version} (FEEL)",
      years = yearResults,
      totalTaxDue = totalTaxDue,
      totalPenalty = totalPenalty,
      totalPayable = pence(totalTaxDue + totalPenalty)
    )

  /** Every step's value for one tax year, or the first step that failed. */
  def year(
    rates  : RatePack,
    catalog: RateCatalog,
    pack   : QuestionPack,
    rules  : CompiledFeelRules,
    answers: Map[String, String]
  ): Either[FeelStepFailure, FeelYear] =
    val inputs = inputsFor(rates, catalog, pack, answers)
    val base: Map[String, Any] = Map("answers" -> inputs.answers, "rates" -> inputs.rates)
    val start: Either[FeelStepFailure, Vector[FeelStepValue]] = Right(Vector.empty)
    rules.steps.zipWithIndex
      .foldLeft(start):
        case (Left(failure), _) => Left(failure)
        case (Right(done), (s, idx)) =>
          val groups = done.filter(_.step.group.isDefined).groupMap(_.step.group.get)(_.value).view.mapValues(_.toList).toMap
          val variables = base ++ done.map(v => v.step.id -> v.value) + ("groups" -> groups)
          evaluate(rules.parsed(s.id), variables)
            .map(value => done :+ FeelStepValue(s, value))
            .left.map(message => FeelStepFailure(idx, s, rates.taxYear, message))
      .map(values => FeelYear(rates, inputs, values))

  /**
    * The answers as FEEL sees them for one year. A per-year question reads that year's answer. A question the
    * user cannot see reads as no answer: £0 for an amount, an empty list for checkboxes, null otherwise.
    */
  def inputsFor(rates: RatePack, catalog: RateCatalog, pack: QuestionPack, answers: Map[String, String]): FeelInputs =
    val visible = QuestionEngine.visibleQuestions(pack, catalog, answers).map(_.id).toSet
    val values = pack.questions.map: q =>
      val answerId = if q.perTaxYear then YearAnswers.key(q.id, rates.taxYear) else q.id
      val raw = answers.get(answerId).filter(_ => visible.contains(answerId))
      val value: Any = q.questionType match
        case QuestionType.currency   => raw.flatMap(Answers.parseAmount).getOrElse(BigDecimal(0))
        case QuestionType.checkboxes => raw.map(Answers.splitMulti).getOrElse(Nil).toList
        case _                       => raw.orNull
      q.id -> value
    FeelInputs(values.toMap, rates.values.toMap)

  private def evaluate(parsed: ParsedExpression, variables: Map[String, Any]): Either[String, Any] =
    val result = Feel.engine.evaluate(parsed, variables)
    if result.isFailure then Left(result.failure.message)
    else if result.hasSuppressedFailures then Left(result.suppressedFailures.map(_.failureMessage).mkString("; "))
    else
      result.result match
        case null                 => Left("the expression gave no value")
        case n: BigDecimal        => Right(n)
        case b: java.lang.Boolean => Right(b.booleanValue)
        case other                => Left(s"expected a number or yes/no but got '$other'")

  private def result(rules: CompiledFeelRules, figures: FeelYear, vocabulary: FeelVocabulary): LiabilityResult =
    val outputs = rules.rules.outputs
    val penaltyAnswered = outputs.penaltyAnswered.exists(figures.flag)

    def explain(part: FeelPart): Seq[CalcExplanation] =
      figures.values.filter(_.step.part == part).map: v =>
        val exp = rules.expression(v.step)
        CalcExplanation(
          label = v.step.label,
          operation = FeelRuleRenderer.rule(v.step, exp, vocabulary),
          result = FeelRuleRenderer.formatValue(v.step.format, v.value),
          working = Some(FeelRuleRenderer.working(exp, vocabulary, figures))
        )

    LiabilityResult(
      taxYear = figures.taxYear,
      ratePackVersion = figures.rates.version,
      totalIncome = figures.number(outputs.totalIncome),
      allowancesUsed = outputs.allowances.map(a => a -> figures.number(a)).toMap,
      taxableIncome = figures.number(outputs.taxableIncome),
      taxDue = pence(figures.number(outputs.taxDue)),
      penaltyAmount = outputs.penalty.fold(BigDecimal(0))(p => pence(figures.number(p))),
      penaltyCalculated = penaltyAnswered,
      explanations = explain(FeelPart.tax),
      penaltySteps = if penaltyAnswered then explain(FeelPart.penalty) else Nil
    )

  private def pence(amount: BigDecimal): BigDecimal = amount.setScale(2, RoundingMode.HALF_UP)

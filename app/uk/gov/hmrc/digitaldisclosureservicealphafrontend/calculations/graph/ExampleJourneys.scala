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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{
  LiabilityCalculator,
  QuestionEngine
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  QuestionPack,
  RateCatalog,
  SessionState,
  YearAnswers
}

import scala.math.BigDecimal.RoundingMode

object ExampleJourneys:

  /** Design-focus income lanes from the Version 1.4 mural. */
  private val DesignFocusIncomeTypes =
    "bankInterest,dividends,foreignIncome,trustsEstates,selfEmployment,ukProperty"

  def build(state: SessionState, translate: String => String = identity): Seq[GraphExample] =
    val pack = state.questionPack
    val catalog = state.rateCatalog
    val spec = state.calculationSpec
    val firstYear = catalog.taxYears.headOption.getOrElse("2017-18")
    val complexYears = catalog.taxYears.take(2)

    val simpleAnswers = Map(
      "taxYears" -> firstYear,
      "incomeTypes" -> "dividends",
      "ageBand" -> "under65",
      "marriedOrCivilPartnership" -> "no",
      "blindPersonEligible" -> "no",
      YearAnswers.key("alreadyDeclaredIncome", firstYear) -> "0",
      YearAnswers.key("taxAlreadyPaid", firstYear) -> "0",
      YearAnswers.key("dividends", firstYear) -> "250",
      YearAnswers.key("claimAnyReliefs", firstYear) -> "no"
    )

    val complexAnswers = Map(
      "taxYears" -> complexYears.mkString(","),
      "incomeTypes" -> DesignFocusIncomeTypes,
      "ageBand" -> "under65",
      "marriedOrCivilPartnership" -> "no",
      "blindPersonEligible" -> "no"
    ) ++ complexYears.flatMap: year =>
      Map(
        YearAnswers.key("alreadyDeclaredIncome", year) -> "0",
        YearAnswers.key("taxAlreadyPaid", year) -> "0",
        YearAnswers.key("bankInterest", year) -> "250",
        YearAnswers.key("bankInterestTaxTakenOff", year) -> "no",
        YearAnswers.key("dividends", year) -> "250",
        YearAnswers.key("foreignIncome", year) -> "250",
        YearAnswers.key("foreignTaxPaid", year) -> "0",
        YearAnswers.key("trustsEstates", year) -> "250",
        YearAnswers.key("selfEmploymentUseTradingAllowance", year) -> "no",
        YearAnswers.key("selfEmploymentTurnover", year) -> "6000",
        YearAnswers.key("selfEmploymentExpenses", year) -> "500",
        YearAnswers.key("propertyType", year) -> "fhl",
        YearAnswers.key("propertyUseAllowance", year) -> "no",
        YearAnswers.key("propertyIncome", year) -> "12000",
        YearAnswers.key("propertyExpenses", year) -> "2000",
        YearAnswers.key("claimAnyReliefs", year) -> "no"
      )

    val simpleForPack =
      if pack.questions.exists(_.id == "incomeTypes") then simpleAnswers
      else
        simpleAnswers - "incomeTypes" - "ageBand" - "marriedOrCivilPartnership" ++ Map(
          YearAnswers.key("bankInterest", firstYear) -> "0",
          YearAnswers.key("dividends", firstYear) -> "250",
          YearAnswers.key("foreignIncome", firstYear) -> "0",
          YearAnswers.key("trustsEstates", firstYear) -> "0",
          YearAnswers.key("selfEmploymentTurnover", firstYear) -> "0",
          YearAnswers.key("selfEmploymentExpenses", firstYear) -> "0",
          YearAnswers.key("propertyIncome", firstYear) -> "0",
          YearAnswers.key("propertyExpenses", firstYear) -> "0",
          YearAnswers.key("capitalGains", firstYear) -> "0"
        )

    val complexForPack =
      if pack.questions.exists(_.id == "incomeTypes") then complexAnswers
      else
        complexAnswers - "incomeTypes" - "ageBand" - "marriedOrCivilPartnership" ++ complexYears.flatMap: year =>
          Map(
            YearAnswers.key("bankInterest", year) -> "250",
            YearAnswers.key("dividends", year) -> "250",
            YearAnswers.key("foreignIncome", year) -> "250",
            YearAnswers.key("trustsEstates", year) -> "250",
            YearAnswers.key("selfEmploymentTurnover", year) -> "6000",
            YearAnswers.key("selfEmploymentExpenses", year) -> "500",
            YearAnswers.key("propertyIncome", year) -> "12000",
            YearAnswers.key("propertyExpenses", year) -> "2000",
            YearAnswers.key("capitalGains", year) -> "0"
          )

    Seq(
      buildExample(
        id = "simple",
        titleKey = "calculations.graph.example.simple.title",
        summaryKey = "calculations.graph.example.simple.summary",
        answers = simpleForPack,
        pack = pack,
        catalog = catalog,
        spec = spec,
        translate = translate
      ),
      buildExample(
        id = "complex",
        titleKey = "calculations.graph.example.complex.title",
        summaryKey = "calculations.graph.example.complex.summary",
        answers = complexForPack,
        pack = pack,
        catalog = catalog,
        spec = spec,
        translate = translate
      )
    )

  private def buildExample(
    id        : String,
    titleKey  : String,
    summaryKey: String,
    answers   : Map[String, String],
    pack      : QuestionPack,
    catalog   : RateCatalog,
    spec      : CalculationSpec,
    translate : String => String
  ): GraphExample =
    val visible = QuestionEngine.visibleQuestions(pack, catalog, answers, translate)
    val journey = visible.map: q =>
      val note =
        answers.get(q.id).map(v => s"Answer: $v").getOrElse("Shown from config rules")
      ExampleJourneyStep(q.id, q.title, note)

    val result = LiabilityCalculator.calculate(catalog, spec, answers)
    val yearCalcs = result.years.map: yearResult =>
      ExampleYearCalc(
        taxYear = yearResult.taxYear,
        ops = yearResult.explanations.map: step =>
          ExampleCalcOp(
            label = CalculationsI18n.breakdownLabel(step.label, translate),
            operation = step.operation,
            result = step.result
          ),
        taxDue = gbp(yearResult.taxDue)
      )

    GraphExample(
      id = id,
      titleKey = titleKey,
      summaryKey = summaryKey,
      answers = answers.toSeq.sortBy(_._1),
      journeySteps = journey,
      yearCalcs = yearCalcs,
      totalTaxDue = gbp(result.totalTaxDue)
    )

  private def gbp(amount: BigDecimal): String =
    f"£${amount.setScale(2, RoundingMode.HALF_UP)}%,.2f"

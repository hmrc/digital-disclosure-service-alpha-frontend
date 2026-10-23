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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{LiabilityResult, MultiYearLiabilityResult}

class FeelCalculatorSpec extends AnyWordSpec with Matchers:

  private val rules = DefaultConfigs.feelRules.compiled
  private val year = "2017-18"
  private def y(id: String) = s"${id}__$year"

  private def feel(answers: Map[String, String]): MultiYearLiabilityResult =
    FeelCalculator.calculate(catalog, fullPack, rules, answers, translate)

  private def specResult(answers: Map[String, String]): MultiYearLiabilityResult =
    LiabilityCalculator.calculate(catalog, spec, answers)

  private def figures(result: MultiYearLiabilityResult) =
    result.years.map(r => (r.taxYear, r.totalIncome, r.taxableIncome, r.allowancesUsed, r.taxDue, r.penaltyAmount, r.penaltyCalculated))

  /** Only the answers the full pack would show, as a user would leave them. */
  private def visibleOnly(answers: Map[String, String]): Map[String, String] =
    val visible = QuestionEngine.visibleQuestions(fullPack, catalog, answers).map(_.id).toSet
    answers.filter((id, _) => id == "taxYears" || visible.contains(id))

  "FeelCalculator" should:
    "give the same figures as the calculation spec for every worked example" in:
      DefaultConfigs.workedExamples.foreach: example =>
        val answers = visibleOnly(example.answersFor(catalog.taxYears.take(example.taxYearCount)))
        figures(feel(answers)) shouldBe figures(specResult(answers))
        feel(answers).totalPayable shouldBe specResult(answers).totalPayable

    "give the same figures as the calculation spec with the taper, Blind Person’s Allowance and tax paid" in:
      val answers = visibleOnly(
        Map(
          "taxYears" -> "2016-17,2017-18",
          "incomeTypes" -> "employment,ukProperty,bankInterest",
          "ageBand" -> "under65",
          "marriedOrCivilPartnership" -> "no",
          "blindPersonEligible" -> "yes"
        ) ++ Seq("2016-17", "2017-18").flatMap: ty =>
          Seq(
            s"employmentIncome__$ty" -> "98000",
            s"employmentTaxDeducted__$ty" -> "20000",
            s"propertyType__$ty" -> "residential",
            s"propertyUseAllowance__$ty" -> "no",
            s"propertyIncome__$ty" -> "30000",
            s"propertyExpenses__$ty" -> "4000",
            s"bankInterest__$ty" -> "1500",
            s"bankInterestTaxTakenOff__$ty" -> "yes",
            s"bankInterestTaxDeducted__$ty" -> "300",
            s"taxAlreadyPaid__$ty" -> "1000"
          )
      )
      val result = feel(answers)
      figures(result) shouldBe figures(specResult(answers))
      // £125,500 income: the personal allowance tapers away completely
      result.years.flatMap(_.allowancesUsed.get("personalAllowance")).map(_.toInt) shouldBe Seq(0, 0)

    "work out the complex example’s tax and penalty" in:
      val complex = DefaultConfigs.workedExamples.find(_.id == "complex").get
      val result = feel(visibleOnly(complex.answersFor(catalog.taxYears.take(2))))
      result.years.map(_.taxDue) shouldBe Seq(BigDecimal("1180.00"), BigDecimal("1100.00"))
      result.years.map(_.penaltyAmount) shouldBe Seq(BigDecimal("247.80"), BigDecimal("231.00"))
      result.totalPayable shouldBe BigDecimal("2758.80")
      result.calculationVersion shouldBe "income-tax-design-focus-feel 1.0.0 (FEEL)"

    "ignore an answer to a question the user can no longer see" in:
      // Answered "no" to the trading allowance and gave expenses, then changed to "yes" and gave an allowance
      val answers = aboutYou ++ Map(
        "taxYears" -> year,
        "incomeTypes" -> "selfEmployment",
        y("selfEmploymentTurnover") -> "20000",
        y("selfEmploymentUseTradingAllowance") -> "yes",
        y("selfEmploymentExpenses") -> "5000",
        y("selfEmploymentTradingAllowance") -> "1000"
      )
      feel(answers).years.head.totalIncome shouldBe BigDecimal(19000)
      // the calculation spec still deducts the hidden expenses
      specResult(answers).years.head.totalIncome shouldBe BigDecimal(14000)

    "give no penalty with a reasonable excuse, and still report the penalty questions as answered" in:
      val result: LiabilityResult = feel(
        aboutYou ++ Map(
          "taxYears" -> year,
          "incomeTypes" -> "dividends",
          y("dividends") -> "20000",
          y("penaltyBehaviour") -> "reasonableExcuse"
        )
      ).years.head
      result.taxDue shouldBe BigDecimal("1700.00")
      result.penaltyCalculated shouldBe true
      result.penaltyAmount shouldBe BigDecimal(0)
      result.penaltySteps.find(_.label == "calculations.feel.step.penaltyApplies").map(_.result) shouldBe Some("No")

    "leave the penalty out until its questions are answered" in:
      val result = feel(aboutYou ++ Map("taxYears" -> year, "incomeTypes" -> "dividends", y("dividends") -> "20000")).years.head
      result.penaltyCalculated shouldBe false
      result.penaltySteps shouldBe empty

    "explain every tax step in words, with the year’s working" in:
      val result = feel(aboutYou ++ Map("taxYears" -> year, "incomeTypes" -> "dividends", y("dividends") -> "20000")).years.head
      val taxable = result.explanations.find(_.label == "Taxable_income").get
      taxable.operation shouldBe "Total income − Personal allowance − Blind Person’s Allowance, not below zero"
      taxable.working shouldBe Some("£20,000.00 − £11,500.00 − £0.00, not below zero")
      taxable.result shouldBe "£8,500.00"

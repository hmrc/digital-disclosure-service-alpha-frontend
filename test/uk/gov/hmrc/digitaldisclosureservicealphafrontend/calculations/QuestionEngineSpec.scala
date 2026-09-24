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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{CalculationSpec, QuestionPack, RateCatalog}

class QuestionEngineSpec extends AnyWordSpec with Matchers:

  private val pack = DefaultConfigs.ratesAndQuestionsPack
  private val catalog = DefaultConfigs.defaultRateCatalog

  /** Map config message keys to English copy for unit tests (mirrors conf/messages). */
  private val translate: String => String = {
    case "How_much_dividend_income_from_UK_companies_do_you_need_to_disclose" =>
      "How much dividend income from UK companies do you need to disclose?"
    case other => other
  }

  "QuestionEngine.resolve" should:
    "show shared questions before tax years are chosen" in:
      val visible = QuestionEngine.visibleQuestions(pack, catalog, Map.empty)
      visible.map(_.id) shouldBe Seq(
        "taxYears",
        "incomeTypes",
        "ageBand",
        "marriedOrCivilPartnership",
        "blindPersonEligible"
      )

    "expand per-year questions year by year" in:
      val answers = Map(
        "taxYears" -> "2015-16,2017-18",
        "incomeTypes" -> "dividends",
        "ageBand" -> "under65",
        "marriedOrCivilPartnership" -> "no",
        "blindPersonEligible" -> "no"
      )
      val visible = QuestionEngine.visibleQuestions(pack, catalog, answers, translate)
      val ids = visible.map(_.id)
      ids should contain allOf (
        "taxYears",
        "incomeTypes",
        "dividends__2015-16",
        "dividends__2017-18",
        "alreadyDeclaredIncome__2015-16",
        "claimAnyReliefs__2015-16"
      )
      ids should not contain "bankInterest__2015-16"
      visible.filter(_.id.startsWith("dividends")).map(_.title) should contain(
        "How much dividend income from UK companies do you need to disclose? (2015-16)"
      )
      pack.questions.find(_.id == "dividends").map(_.title) shouldBe Some(
        "How_much_dividend_income_from_UK_companies_do_you_need_to_disclose"
      )

      // Finish all 2015-16 amount questions before starting 2017-18
      val firstYearBlock = ids.indexOf("alreadyDeclaredIncome__2015-16")
      val endYearBlock = ids.indexOf("claimAnyReliefs__2015-16")
      val nextYearStart = ids.indexOf("alreadyDeclaredIncome__2017-18")
      firstYearBlock should be >= 0
      endYearBlock should be > firstYearBlock
      nextYearStart should be > endYearBlock
      ids.indexOf("dividends__2015-16") should (be > firstYearBlock and be < endYearBlock)
      ids.indexOf("dividends__2017-18") should be > nextYearStart
      // Shared questions stay ahead of the first year block
      ids.indexOf("incomeTypes") should be < firstYearBlock

    "show self-employment expense branch only when trading allowance is declined" in:
      val base = Map(
        "taxYears" -> "2017-18",
        "incomeTypes" -> "selfEmployment",
        "ageBand" -> "under65",
        "marriedOrCivilPartnership" -> "no",
        "blindPersonEligible" -> "no",
        "selfEmploymentUseTradingAllowance__2017-18" -> "no"
      )
      val withExpenses = QuestionEngine.visibleQuestions(pack, catalog, base, translate).map(_.id)
      withExpenses should contain("selfEmploymentExpenses__2017-18")
      withExpenses should not contain "selfEmploymentTradingAllowance__2017-18"
      withExpenses should contain("selfEmploymentBusinessName__2017-18")

      val withAllowance = QuestionEngine.visibleQuestions(
        pack,
        catalog,
        base + ("selfEmploymentUseTradingAllowance__2017-18" -> "yes"),
        translate
      ).map(_.id)
      withAllowance should contain("selfEmploymentTradingAllowance__2017-18")
      withAllowance should not contain "selfEmploymentExpenses__2017-18"

    "show rent-a-room relief instead of property allowance when that property type is chosen" in:
      val answers = Map(
        "taxYears" -> "2017-18",
        "incomeTypes" -> "ukProperty",
        "ageBand" -> "under65",
        "marriedOrCivilPartnership" -> "no",
        "blindPersonEligible" -> "no",
        "propertyType__2017-18" -> "rentARoom",
        "claimRentARoomRelief__2017-18" -> "yes"
      )
      val ids = QuestionEngine.visibleQuestions(pack, catalog, answers, translate).map(_.id)
      ids should contain("claimRentARoomRelief__2017-18")
      ids should contain("rentARoomRelief__2017-18")
      ids should not contain "propertyUseAllowance__2017-18"

    "build tax year options from the rate catalogue" in:
      val taxYears = QuestionEngine.find(pack, catalog, Map.empty, "taxYears").get
      taxYears.options.map(_.value) shouldBe Seq("2015-16", "2016-17", "2017-18")
      taxYears.options.map(_.label) should contain("2017 to 2018")

  "QuestionEngine.nextQuestion" should:
    "walk visible questions including expanded years" in:
      val answers = Map(
        "taxYears" -> "2017-18",
        "incomeTypes" -> "dividends",
        "ageBand" -> "under65",
        "marriedOrCivilPartnership" -> "no",
        "blindPersonEligible" -> "no"
      )
      val first = QuestionEngine.nextQuestion(pack, catalog, answers, None).get
      first.id shouldBe "taxYears"
      val afterYears = QuestionEngine.nextQuestion(pack, catalog, answers, Some("taxYears")).get
      afterYears.id shouldBe "incomeTypes"

  "DefaultConfigs JSON" should:
    "round-trip the multi-year rate catalogue, question pack and calculation spec" in:
      val parsed = Json.parse(DefaultConfigs.prettyRateJson()).as[RateCatalog]
      parsed.years.map(_.taxYear) shouldBe Seq("2015-16", "2016-17", "2017-18")
      Json.parse(DefaultConfigs.prettyQuestionJson(pack)).as[QuestionPack].questions.size should be > 40
      val calc = Json.parse(DefaultConfigs.prettyCalculationJson()).as[CalculationSpec]
      calc.incomeComponents.map(_.id) should contain allOf (
        "bankInterest",
        "selfEmploymentProfit",
        "ukPropertyProfit",
        "employmentIncome"
      )
      calc.incomeComponents.find(_.id == "selfEmploymentProfit").flatMap(_.altDeductField) shouldBe Some(
        "selfEmploymentTradingAllowance"
      )
      calc.version shouldBe "1.4.2"

  "LiabilityCalculator" should:
    "sum estimates across selected tax years using each year pack and the calculation spec" in:
      val result = LiabilityCalculator.calculate(
        catalog,
        DefaultConfigs.defaultCalculationSpec,
        Map(
          "taxYears" -> "2015-16,2017-18",
          "blindPersonEligible" -> "no",
          "bankInterest__2015-16" -> "250",
          "dividends__2015-16" -> "250",
          "foreignIncome__2015-16" -> "250",
          "trustsEstates__2015-16" -> "250",
          "selfEmploymentTurnover__2015-16" -> "6000",
          "selfEmploymentExpenses__2015-16" -> "500",
          "propertyIncome__2015-16" -> "12000",
          "propertyExpenses__2015-16" -> "2000",
          "bankInterest__2017-18" -> "250",
          "dividends__2017-18" -> "250",
          "foreignIncome__2017-18" -> "250",
          "trustsEstates__2017-18" -> "250",
          "selfEmploymentTurnover__2017-18" -> "6000",
          "selfEmploymentExpenses__2017-18" -> "500",
          "propertyIncome__2017-18" -> "12000",
          "propertyExpenses__2017-18" -> "2000"
        )
      )
      result.years.map(_.taxYear) shouldBe Seq("2015-16", "2017-18")
      result.calculationVersion shouldBe "1.4.2"
      // Per year income: 250*4 + 5500 + 10000 = 16500
      // 2015-16: taxable 16500 − 10600 = 5900 → 1180.00
      result.years.head.taxDue shouldBe BigDecimal("1180.00")
      // 2017-18: taxable 16500 − 11500 = 5000 → 1000.00
      result.years(1).taxDue shouldBe BigDecimal("1000.00")
      result.totalTaxDue shouldBe BigDecimal("2180.00")

    "deduct tax already paid from the banded estimate" in:
      val withoutPaid = LiabilityCalculator.calculateYear(
        DefaultConfigs.rate2017_18,
        DefaultConfigs.defaultCalculationSpec,
        Map("dividends" -> "20000", "blindPersonEligible" -> "no"),
        "2017-18"
      )
      val withPaid = LiabilityCalculator.calculateYear(
        DefaultConfigs.rate2017_18,
        DefaultConfigs.defaultCalculationSpec,
        Map(
          "dividends" -> "20000",
          "blindPersonEligible" -> "no",
          "taxAlreadyPaid" -> "500"
        ),
        "2017-18"
      )
      withPaid.taxDue shouldBe (withoutPaid.taxDue - BigDecimal(500))

    "honour calculation-spec changes such as disabling Blind Person’s Allowance" in:
      val noBlind = DefaultConfigs.defaultCalculationSpec.copy(
        allowances = DefaultConfigs.defaultCalculationSpec.allowances.filterNot(_.id == "blindPersonsAllowance")
      )
      val withBlind = LiabilityCalculator.calculateYear(
        DefaultConfigs.rate2017_18,
        DefaultConfigs.defaultCalculationSpec,
        Map(
          "blindPersonEligible" -> "yes",
          "selfEmploymentTurnover" -> "20000",
          "selfEmploymentExpenses" -> "2000"
        ),
        "2017-18"
      )
      val withoutBlind = LiabilityCalculator.calculateYear(
        DefaultConfigs.rate2017_18,
        noBlind,
        Map(
          "blindPersonEligible" -> "yes",
          "selfEmploymentTurnover" -> "20000",
          "selfEmploymentExpenses" -> "2000"
        ),
        "2017-18"
      )
      withBlind.blindAllowanceUsed shouldBe BigDecimal(2320)
      withoutBlind.blindAllowanceUsed shouldBe BigDecimal(0)
      withoutBlind.taxDue should be > withBlind.taxDue

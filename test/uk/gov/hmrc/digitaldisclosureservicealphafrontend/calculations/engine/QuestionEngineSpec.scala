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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*

class QuestionEngineSpec extends AnyWordSpec with Matchers:

  /** Maps config message keys to English copy (mirrors conf/messages). */
  private val translate: String => String = {
    case "How_much_dividend_income_from_UK_companies_do_you_need_to_disclose" =>
      "How much dividend income from UK companies do you need to disclose?"
    case other => other
  }

  private def visibleIds(answers: Map[String, String]): Seq[String] =
    QuestionEngine.visibleQuestions(fullPack, catalog, answers, translate).map(_.id)

  "QuestionEngine.visibleQuestions" should:
    "show shared questions before tax years are chosen" in:
      visibleIds(Map.empty) shouldBe Seq("taxYears", "incomeTypes", "ageBand", "marriedOrCivilPartnership", "blindPersonEligible")

    "expand per-year questions year by year" in:
      val answers = aboutYou ++ Map("taxYears" -> "2015-16,2017-18", "incomeTypes" -> "dividends")
      val visible = QuestionEngine.visibleQuestions(fullPack, catalog, answers, translate)
      val ids = visible.map(_.id)
      ids should contain allOf ("taxYears", "incomeTypes", "dividends__2015-16", "dividends__2017-18",
        "alreadyDeclaredIncome__2015-16", "claimAnyReliefs__2015-16")
      ids should not contain "bankInterest__2015-16"
      visible.filter(_.id.startsWith("dividends")).map(_.title) should contain(
        "How much dividend income from UK companies do you need to disclose? (2015-16)"
      )

      val firstYearStart = ids.indexOf("alreadyDeclaredIncome__2015-16")
      val firstYearEnd = ids.indexOf("claimAnyReliefs__2015-16")
      val secondYearStart = ids.indexOf("alreadyDeclaredIncome__2017-18")
      ids.indexOf("incomeTypes") should be < firstYearStart
      firstYearEnd should be > firstYearStart
      secondYearStart should be > firstYearEnd
      ids.indexOf("dividends__2015-16") should (be > firstYearStart and be < firstYearEnd)
      ids.indexOf("dividends__2017-18") should be > secondYearStart

    "show self-employment expenses only when the trading allowance is declined" in:
      val base = aboutYou ++ Map("taxYears" -> "2017-18", "incomeTypes" -> "selfEmployment")

      val withExpenses = visibleIds(base + ("selfEmploymentUseTradingAllowance__2017-18" -> "no"))
      withExpenses should contain allOf ("selfEmploymentExpenses__2017-18", "selfEmploymentBusinessName__2017-18")
      withExpenses should not contain "selfEmploymentTradingAllowance__2017-18"

      val withAllowance = visibleIds(base + ("selfEmploymentUseTradingAllowance__2017-18" -> "yes"))
      withAllowance should contain("selfEmploymentTradingAllowance__2017-18")
      withAllowance should not contain "selfEmploymentExpenses__2017-18"

    "show rent-a-room relief instead of the property allowance for that property type" in:
      val ids = visibleIds(
        aboutYou ++ Map(
          "taxYears" -> "2017-18",
          "incomeTypes" -> "ukProperty",
          "propertyType__2017-18" -> "rentARoom",
          "claimRentARoomRelief__2017-18" -> "yes"
        )
      )
      ids should contain allOf ("claimRentARoomRelief__2017-18", "rentARoomRelief__2017-18")
      ids should not contain "propertyUseAllowance__2017-18"

  "QuestionEngine.find" should:
    "build tax year options from the rate catalogue" in:
      val taxYears = QuestionEngine.find(fullPack, catalog, Map.empty, "taxYears").get
      taxYears.options.map(_.value) shouldBe Seq("2015-16", "2016-17", "2017-18")
      taxYears.options.map(_.label) should contain("2017 to 2018")

    "resolve a per-year question only when it is visible" in:
      val answers = aboutYou ++ Map("taxYears" -> "2017-18", "incomeTypes" -> "dividends")
      QuestionEngine.find(fullPack, catalog, answers, "dividends__2017-18").flatMap(_.taxYear) shouldBe Some("2017-18")
      QuestionEngine.find(fullPack, catalog, answers, "bankInterest__2017-18") shouldBe None
      QuestionEngine.find(fullPack, catalog, answers, "dividends__2015-16") shouldBe None

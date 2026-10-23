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

class FeelRulesPageBuilderSpec extends AnyWordSpec with Matchers:

  private val page = FeelRulesPageBuilder.build(fullConfig, DefaultConfigs.feelRules, DefaultConfigs.workedExamples, translate)

  private def row(id: String): FeelRuleRow = page.sections.flatMap(_.rows).find(_.id == id).get

  "FeelRulesPageBuilder" should:
    "lay the rules out for the worked example that covers the most tax years" in:
      page.exampleTitleKey shouldBe Some("calculations.graph.example.complex.title")
      page.years shouldBe Seq("2015 to 2016", "2016 to 2017")
      page.exampleProblem shouldBe None
      page.sections.map(_.titleKey) shouldBe Seq("calculations.feel.section.tax", "calculations.feel.section.penalty")

    "give every rule its name, its words, its FEEL and its value each year" in:
      val tax = row("taxDue")
      tax.name shouldBe "Estimated income tax"
      tax.feel shouldBe "round half up(max(0, taxBeforePayments - taxAlreadyPaid), 2)"
      tax.values shouldBe Seq("£1,180.00", "£1,100.00")
      row("penalty").values shouldBe Seq("£247.80", "£231.00")
      row("penaltyRate").values shouldBe Seq("21%", "21%")
      row("quality").values shouldBe Seq("90%", "90%")

    "list the answers and rates the rules read" in:
      page.answers.map(_.name) should contain allOf ("self employment turnover", "blind person eligible", "helping speed")
      page.answers.find(_.name == "dividends").map(_.question) shouldBe Some(translate("How_much_dividend_income_from_UK_companies_do_you_need_to_disclose"))
      page.rates.find(_.name == "personal allowance").map(_.values) shouldBe Some(Seq("£10,600", "£11,000"))
      page.rates.find(_.name == "basic rate").map(_.values) shouldBe Some(Seq("20%", "20%"))

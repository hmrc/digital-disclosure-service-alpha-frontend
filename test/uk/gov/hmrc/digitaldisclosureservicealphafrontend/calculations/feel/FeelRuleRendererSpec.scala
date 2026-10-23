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

class FeelRuleRendererSpec extends AnyWordSpec with Matchers:

  private val rules = DefaultConfigs.feelRules.compiled
  private val vocabulary = FeelVocabulary(rules, fullPack, catalog, translate)

  private def step(id: String): FeelStep = rules.steps.find(_.id == id).get
  private def words(id: String): String = FeelRuleRenderer.words(rules.expression(step(id)), vocabulary)

  "FeelRuleRenderer.words" should:
    "name steps by their label and rates by their name" in:
      words("personalAllowance") shouldBe
        "the personal allowance − ((Total income − the taper threshold, not below zero) ÷ 2, rounded down to a whole number), not below zero"

    "bracket a phrase used inside arithmetic" in:
      words("higherRateTax") shouldBe "(Taxable income − the basic rate band, not below zero) × the higher rate"
      words("basicRateTax") shouldBe "(the smaller of Taxable income and the basic rate band) × the basic rate"

    "use the option wording the user saw, not its code" in:
      words("blindPersonsAllowance") shouldBe "if blind person eligible is ‘Yes’, the Blind Person’s Allowance; otherwise 0"
      words("helpingScore") shouldBe "if helping speed is ‘Right away’, 1; if helping speed is ‘After a short delay’, 0.5; otherwise 0"
      words("extentScore") shouldBe
        "if telling extent is one of ‘I have disclosed everything I know’ or ‘This is a complex situation, but I am sharing everything I can remember’, 1; otherwise 0"

    "describe totals, averages, rounding and missing answers" in:
      words("totalIncome") shouldBe "the total of the income lines"
      words("telling") shouldBe "(the average of Timing, Nature and Extent) × Telling maximum"
      words("taxDue") shouldBe "(Estimated income tax before tax already paid − Tax already paid, not below zero), rounded to the nearest penny"
      words("penaltyAnswered") shouldBe "penalty behaviour has an answer"

    "write a step that is only a number as that value in the step’s format" in:
      FeelRuleRenderer.rule(step("tellingMaximum"), rules.expression(step("tellingMaximum")), vocabulary) shouldBe "Fixed at 30%"
      FeelRuleRenderer.rule(step("taxDue"), rules.expression(step("taxDue")), vocabulary) shouldBe words("taxDue")

  "FeelRuleRenderer.working" should:
    "put the year’s figures in place of the names" in:
      val answers = aboutYou ++ Map("taxYears" -> "2017-18", "incomeTypes" -> "dividends", "dividends__2017-18" -> "120000")
      val figures = FeelCalculator.year(catalog.forYear("2017-18").get, catalog, fullPack, rules, answers).toOption.get
      FeelRuleRenderer.working(rules.expression(step("personalAllowance")), vocabulary, figures) shouldBe
        "£11,500 − ((£120,000.00 − £100,000, not below zero) ÷ 2, rounded down to a whole number), not below zero"
      FeelRuleRenderer.working(rules.expression(step("totalIncome")), vocabulary, figures) shouldBe "£120,000.00"
      figures.number("personalAllowance") shouldBe BigDecimal(1500)

  "FeelRuleRenderer.formatValue" should:
    "write money, percentages, numbers and yes or no" in:
      FeelRuleRenderer.formatValue(FeelValueFormat.money, BigDecimal("231")) shouldBe "£231.00"
      FeelRuleRenderer.formatValue(FeelValueFormat.percentage, BigDecimal("0.2000000000000000000000000000000000")) shouldBe "20%"
      FeelRuleRenderer.formatValue(FeelValueFormat.number, BigDecimal("0.50")) shouldBe "0.5"
      FeelRuleRenderer.formatValue(FeelValueFormat.yesNo, true) shouldBe "Yes"

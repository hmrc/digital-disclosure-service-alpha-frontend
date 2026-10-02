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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*

class CalculationStagesBuilderSpec extends AnyWordSpec with Matchers:

  private val stages = CalculationStagesBuilder.stages(spec, catalog, identity)
  private val jargon = Seq("max(", "round(", "rate(", "Σ")

  "CalculationStagesBuilder.stages" should:
    "describe the calculation in five stages without formula jargon" in:
      stages.map(_.id) shouldBe Seq("income", "allowances", "taxable", "bands", "taxDue")
      stages.flatMap(_.rules).exists(r => jargon.exists(r.rule.contains)) shouldBe false

    "give calculated income a row each and group amounts taken as entered" in:
      val income = stages.head
      income.rules.map(_.label).take(2) shouldBe Seq("Self_employment_profit", "UK_property_profit")
      income.rules.last.items.map(_._2) should contain allOf ("employmentIncome", "dividends", "otherUkIncome")
      income.rules.flatMap(r => if r.items.isEmpty then Seq(r.label) else r.items.map(_._1)).size shouldBe
        spec.incomeComponents.size
      income.rules.find(_.label == "Self_employment_profit").flatMap(_.source) shouldBe
        Some("selfEmploymentTurnover − (selfEmploymentExpenses + selfEmploymentTradingAllowance)")

    "explain the personal allowance taper" in:
      stages.find(_.id == "allowances").flatMap(_.rules.headOption).map(_.rule).get should include("Reduced by £1 for every £2")

    "list the spec's tax-paid fields" in:
      stages.find(_.id == "taxDue").flatMap(_.rules.headOption).flatMap(_.source) shouldBe
        Some(spec.taxPaidFields.mkString(" + "))

  "CalculationStagesBuilder.rateTable" should:
    "show each rate with one column per tax year" in:
      val table = CalculationStagesBuilder.rateTable(catalog, identity)
      table.years shouldBe catalog.taxYears
      table.rows.find(_.label == "PersonalAllowance").map(_.values) shouldBe Some(Seq("£10,600", "£11,000", "£11,500"))
      table.rows.find(_.label == "HigherRate").map(_.values) shouldBe Some(Seq("40%", "40%", "40%"))

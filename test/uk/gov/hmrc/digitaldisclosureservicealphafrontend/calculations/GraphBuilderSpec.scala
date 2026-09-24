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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.{ComputationCell, ComputationRowKind, GraphBuilder}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ArchitectureOption, SessionState}

class GraphBuilderSpec extends AnyWordSpec with Matchers:

  "GraphBuilder" should:
    "build journey and calculation graphs from defaults" in:
      val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
      val state = SessionState(
        id = "test",
        option = ArchitectureOption.RatesAndQuestions,
        rateJson = defaults.rateJson,
        questionJson = defaults.questionJson,
        calculationJson = defaults.calculationJson,
        rateCatalog = defaults.catalog,
        questionPack = defaults.questions,
        calculationSpec = defaults.calculation
      )
      val graph = GraphBuilder.build(state)
      val journey = graph.journeyMap
      journey.sections.map(_.id) shouldBe Seq("prepare", "income-types", "gain-types", "year")
      val tasks = journey.sections.flatMap(_.tasks).map(t => t.id -> t).toMap
      tasks("about-disclosure").questions.map(_.id) shouldBe Seq("taxYears", "incomeTypes")
      tasks("about-disclosure").questions.head.options shouldBe defaults.catalog.taxYears
      val selfEmployment = tasks("income-selfEmployment").questions.map(q => q.id -> q).toMap
      selfEmployment("selfEmploymentTurnover").usedIn shouldBe Seq("Self_employment_profit (calculations.graph.journey.usage.gross)")
      selfEmployment("selfEmploymentTurnover").condition shouldBe None
      selfEmployment("selfEmploymentExpenses").condition.isDefined shouldBe true
      selfEmployment("selfEmploymentExpenses").depth shouldBe 1
      tasks("allowances").questions.find(_.id == "blindPersonEligible").map(_.usedIn.size) shouldBe Some(1)
      tasks("year-declared").questions.map(_.id) shouldBe Seq("alreadyDeclaredIncome", "taxAlreadyPaid")
      journey.unplaced shouldBe empty
      journey.notInEstimate.map(_.id) should contain allOf ("alreadyDeclaredIncome", "foreignTaxPaid", "cgtResidentialProperty")
      journey.notInEstimate.map(_.id) should not contain "dividends"
      graph.journeyMermaid should include("flowchart TD")
      graph.journeyMermaid should include("n_lane_incomeTypes_selfEmployment")
      graph.calcStages.map(_.id) shouldBe Seq("income", "allowances", "taxable", "bands", "taxDue")
      val incomeStage = graph.calcStages.head
      incomeStage.rules.map(_.label).take(2) shouldBe Seq("Self_employment_profit", "UK_property_profit")
      incomeStage.rules.last.items.map(_._2) should contain allOf ("employmentIncome", "dividends", "otherUkIncome")
      incomeStage.rules.flatMap(r => if r.items.isEmpty then Seq(r.label) else r.items.map(_._1)).size shouldBe
        defaults.calculation.incomeComponents.size
      incomeStage.rules.find(_.label == "Self_employment_profit").flatMap(_.source) shouldBe Some(
        "selfEmploymentTurnover − (selfEmploymentExpenses + selfEmploymentTradingAllowance)"
      )
      graph.calcStages.find(_.id == "allowances").flatMap(_.rules.headOption).map(_.rule).exists(
        _.contains("Reduced by £1 for every £2")
      ) shouldBe true
      graph.calcStages.find(_.id == "taxDue").map(_.rules.head.source) shouldBe Some(
        Some("taxAlreadyPaid + employmentTaxDeducted + bankInterestTaxDeducted + pensionTaxDeducted")
      )
      graph.calcStages.flatMap(_.rules).exists(r => Seq("max(", "round(", "rate(", "Σ").exists(r.rule.contains)) shouldBe false
      graph.rateTable.years shouldBe defaults.catalog.taxYears
      graph.rateTable.rows.find(_.label == "PersonalAllowance").map(_.values) shouldBe Some(Seq("£10,600", "£11,000", "£11,500"))
      graph.rateTable.rows.find(_.label == "HigherRate").map(_.values) shouldBe Some(Seq("40%", "40%", "40%"))
      graph.calcScope shouldBe defaults.calculation.description
      graph.examples.map(_.id) shouldBe Seq("simple", "complex")
      val complex = graph.examples.find(_.id == "complex").get.computation
      complex.years.size shouldBe 2
      complex.rows.map(_.label) should contain allOf ("Total_income", "Taxable_income", "Estimated_income_tax")
      complex.rows.find(_.label == "Self_employment_profit").map(_.cells.head) shouldBe Some(
        ComputationCell("£5,500.00", Some("£6,000.00 − £500.00"))
      )
      complex.rows.find(_.label == "Personal_allowance").map(_.cells.head.amount) shouldBe Some("−£10,600.00")
      complex.rows.find(_.label == "Basic_rate").flatMap(_.cells.head.working).exists(_.endsWith("× 20%")) shouldBe true
      complex.rows.last.kind shouldBe ComputationRowKind.total
      complex.zeroItems should contain("Employment_income")
      complex.rows.map(_.label) should not contain "Employment_income"
      graph.examples.foreach: example =>
        example.journeySteps.nonEmpty shouldBe true
        example.yearCalcs.nonEmpty shouldBe true
        example.yearCalcs.foreach: year =>
          year.ops.exists(_.operation.nonEmpty) shouldBe true
          year.ops.exists(_.operation.contains("£")) shouldBe true
          year.ops.exists(op => Seq("max(", "round(", "rate(", "Σ").exists(op.operation.contains)) shouldBe false
          year.ops.exists(_.result.startsWith("£")) shouldBe true
      graph.journeyMermaid should include("flowchart TD")
      graph.calculationMermaid should include("totalIncome")
      graph.calculationMermaid should not include "max("
      graph.calculationMermaid should include("taxPaid -->|minus| taxDue")
      graph.architectureMermaid should include("Stage 1")

    "place Option 1's fixed per-year questions in a year task" in:
      val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesOnly)
      val journey = GraphBuilder.build(
        SessionState(
          id = "test",
          option = ArchitectureOption.RatesOnly,
          rateJson = defaults.rateJson,
          questionJson = defaults.questionJson,
          calculationJson = defaults.calculationJson,
          rateCatalog = defaults.catalog,
          questionPack = defaults.questions,
          calculationSpec = defaults.calculation
        )
      ).journeyMap
      journey.unplaced shouldBe empty
      val yearTasks = journey.sections.find(_.id == "year").get.tasks
      yearTasks.map(_.id) shouldBe Seq("year-declared", "year-other")
      yearTasks.find(_.id == "year-other").get.questions.map(_.id) should contain allOf ("bankInterest", "dividends", "capitalGains")

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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.GraphBuilder
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
      graph.screens.map(_.id).head shouldBe "taxYears"
      graph.screens.exists(_.perTaxYear) shouldBe true
      graph.journeyBranches.trunk.map(_.id) should contain("taxYears")
      graph.journeyBranches.trunk.map(_.id) should contain("incomeTypes")
      graph.journeyBranches.branches.map(_.id) should contain("incomeTypes:selfEmployment")
      graph.journeyBranches.branches.find(_.id == "incomeTypes:selfEmployment").exists(_.screens.map(_.id).contains("selfEmploymentTurnover")) shouldBe true
      graph.journeyMermaid should include("flowchart TD")
      graph.journeyMermaid should include("n_lane_incomeTypes_selfEmployment")
      graph.calcSteps.map(_.id) should contain allOf ("selfEmploymentProfit", "totalIncome", "taxDue")
      graph.calcSteps.find(_.id == "totalIncome").map(_.operation) shouldBe Some("Add all income amounts together.")
      graph.calcSteps.find(_.id == "taxableIncome").map(_.operation) shouldBe Some(
        "Subtract all allowances from total income. If the result is less than £0, use £0 instead."
      )
      graph.calcSteps.exists(step => Seq("max(", "round(", "rate(", "Σ").exists(step.operation.contains)) shouldBe false
      graph.examples.map(_.id) shouldBe Seq("simple", "complex")
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
      graph.architectureMermaid should include("Stage 1")

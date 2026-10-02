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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption

class WorkedExamplesBuilderSpec extends AnyWordSpec with Matchers:

  private def examplesFor(option: ArchitectureOption) =
    WorkedExamplesBuilder.build(stateFor(option)).map(e => e.id -> e).toMap

  "WorkedExamplesBuilder" should:
    "lay the complex example out as a tax computation" in:
      val complex = examplesFor(ArchitectureOption.RatesAndQuestions)("complex").computation
      complex.years.size shouldBe 2
      complex.rows.map(_.label) should contain allOf ("Total_income", "Taxable_income", "Estimated_income_tax")
      complex.rows.find(_.label == "Self_employment_profit").map(_.cells.head) shouldBe
        Some(ComputationCell("£5,500.00", Some("£6,000.00 − £500.00")))
      complex.rows.find(_.label == "Personal_allowance").map(_.cells.head.amount) shouldBe Some("−£10,600.00")
      complex.rows.find(_.label == "Basic_rate").flatMap(_.cells.head.working).exists(_.endsWith("× 20%")) shouldBe true
      complex.rows.last.kind shouldBe ComputationRowKind.total
      complex.zeroItems should contain("Employment_income")
      complex.rows.map(_.label) should not contain "Employment_income"

    "give the same estimate whichever question pack asks for the figures" in:
      val full = examplesFor(ArchitectureOption.RatesAndQuestions)
      val fixed = examplesFor(ArchitectureOption.RatesOnly)
      fixed.keySet shouldBe Set("simple", "complex")
      fixed.keySet.foreach(id => fixed(id).totalTaxDue shouldBe full(id).totalTaxDue)

    "only use answers to questions the pack asks" in:
      val fixedAnswers = examplesFor(ArchitectureOption.RatesOnly)("complex").answers.map(_._1)
      fixedAnswers should contain("bankInterest__2015-16")
      fixedAnswers should not contain "incomeTypes"
      fixedAnswers should not contain "propertyType__2015-16"

      val fullAnswers = examplesFor(ArchitectureOption.RatesAndQuestions)("simple").answers.map(_._1)
      fullAnswers should contain("incomeTypes")
      fullAnswers should not contain "bankInterest__2015-16"

    "show the working for every year in pounds" in:
      examplesFor(ArchitectureOption.RatesAndQuestions).values.foreach: example =>
        example.journeySteps should not be empty
        example.yearCalcs should not be empty
        example.yearCalcs.foreach: year =>
          year.ops.exists(_.operation.contains("£")) shouldBe true
          year.ops.exists(_.result.startsWith("£")) shouldBe true
          year.ops.exists(op => Seq("max(", "round(", "rate(", "Σ").exists(op.operation.contains)) shouldBe false

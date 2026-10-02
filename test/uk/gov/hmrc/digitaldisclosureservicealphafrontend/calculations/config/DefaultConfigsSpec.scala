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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  CalculationSpec,
  QuestionPack,
  RateCatalog
}

class DefaultConfigsSpec extends AnyWordSpec with Matchers:

  "DefaultConfigs.defaultsFor" should:
    "give options with fixed questions the fixed pack and Option 2 the full pack" in:
      DefaultConfigs.defaultsFor(ArchitectureOption.RatesOnly).questionPack shouldBe fixedPack
      DefaultConfigs.defaultsFor(ArchitectureOption.DownstreamRates).questionPack shouldBe fixedPack
      DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions).questionPack shouldBe fullPack
      fixedPack.questions.exists(_.showIf.isDefined) shouldBe false
      fullPack.questions.size should be > 40

    "share one rate catalogue and calculation spec across options" in:
      fixedConfig.rateCatalog shouldBe fullConfig.rateCatalog
      fixedConfig.calculationSpec shouldBe fullConfig.calculationSpec
      catalog.taxYears shouldBe Seq("2015-16", "2016-17", "2017-18")
      spec.version shouldBe "1.4.2"
      spec.incomeComponents.map(_.id) should contain allOf ("bankInterest", "selfEmploymentProfit", "ukPropertyProfit", "employmentIncome")
      spec.incomeComponents.find(_.id == "selfEmploymentProfit").flatMap(_.altDeductField) shouldBe
        Some("selfEmploymentTradingAllowance")
      spec.taxPaidFields should contain("taxAlreadyPaid")

    "keep the JSON text in step with the parsed documents" in:
      Json.parse(fullConfig.rateJson).as[RateCatalog] shouldBe fullConfig.rateCatalog
      Json.parse(fullConfig.questionJson).as[QuestionPack] shouldBe fullConfig.questionPack
      Json.parse(fullConfig.calculationJson).as[CalculationSpec] shouldBe fullConfig.calculationSpec

  "DefaultConfigs.workedExamples" should:
    "load the sample customers shown on the graph page" in:
      DefaultConfigs.workedExamples.map(_.id) shouldBe Seq("simple", "complex")
      DefaultConfigs.workedExamples.map(_.taxYearCount) shouldBe Seq(1, 2)

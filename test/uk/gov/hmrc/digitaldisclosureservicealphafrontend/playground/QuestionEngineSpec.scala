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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.playground

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json

class QuestionEngineSpec extends AnyWordSpec with Matchers:

  private val pack = DefaultConfigs.ratesAndQuestionsPack

  "QuestionEngine.isVisible" should:
    "show questions without showIf" in:
      val q = pack.questions.find(_.id == "incomeTypes").get
      QuestionEngine.isVisible(q, Map.empty) shouldBe true

    "hide conditional questions until the dependency is met" in:
      val q = pack.questions.find(_.id == "propertyIncome").get
      QuestionEngine.isVisible(q, Map.empty) shouldBe false
      QuestionEngine.isVisible(q, Map("incomeTypes" -> "selfEmployment")) shouldBe false
      QuestionEngine.isVisible(q, Map("incomeTypes" -> "ukProperty,dividends")) shouldBe true

  "QuestionEngine.nextQuestion" should:
    "walk visible questions in order" in:
      val answers = Map("incomeTypes" -> "dividends", "blindPersonEligible" -> "no")
      val first = QuestionEngine.nextQuestion(pack, answers, None).get
      first.id shouldBe "incomeTypes"
      val afterIncome = QuestionEngine.nextQuestion(pack, answers, Some("incomeTypes")).get
      afterIncome.id shouldBe "blindPersonEligible"
      val afterBlind = QuestionEngine.nextQuestion(pack, answers, Some("blindPersonEligible")).get
      afterBlind.id shouldBe "dividends"
      QuestionEngine.nextQuestion(pack, answers, Some("dividends")) shouldBe None

  "DefaultConfigs JSON" should:
    "round-trip rate and question packs" in:
      Json.parse(DefaultConfigs.prettyRateJson()).as[RatePack].taxYear shouldBe "2017-18"
      Json.parse(DefaultConfigs.prettyQuestionJson(pack)).as[QuestionPack].questions.size should be > 3

  "SimpleLiabilityCalculator" should:
    "apply personal allowance and basic rate" in:
      val rates = DefaultConfigs.defaultRatePack
      val result = SimpleLiabilityCalculator.calculate(
        rates,
        Map(
          "blindPersonEligible" -> "no",
          "selfEmploymentTurnover" -> "20000",
          "selfEmploymentExpenses" -> "2000",
          "dividends" -> "0"
        )
      )
      result.totalIncome shouldBe BigDecimal(18000)
      result.taxableIncome shouldBe BigDecimal(6500)
      result.taxDue shouldBe BigDecimal("1300.00")

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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global

class CalculationsSessionServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private given HeaderCarrier = HeaderCarrier()

  private def newService() = CalculationsSessionService(SessionStore(), downstreamRatesService)

  "CalculationsSessionService.start" should:
    "store a new session with the option's default config" in:
      val service = newService()
      val state = service.start(ArchitectureOption.RatesOnly).futureValue
      state.config shouldBe fixedConfig
      state.downstream shouldBe None
      service.get(state.id) shouldBe Some(state)

    "overlay downstream rates for Option 4" in:
      val state = newService().start(ArchitectureOption.DownstreamRates).futureValue
      state.downstream.map(_.yearsFetched) shouldBe Some(Seq("2017-18"))
      state.rateCatalog.forYear("2017-18").map(_.value("personalAllowance")) shouldBe Some(BigDecimal(11850))
      state.config.rateJson should include("11850")

  "CalculationsSessionService.updateConfig" should:
    "apply valid config and clear answers" in:
      val service = newService()
      val started = service.saveAnswer(service.start(ArchitectureOption.RatesAndQuestions).futureValue, "taxYears", "2017-18")
      val renamed = catalog.copy(version = "edited")

      val updated = service.updateConfig(started, Json.toJson(renamed).toString, fullConfig.questionJson, fullConfig.calculationJson)
      updated.map(_.rateCatalog.version) shouldBe Right("edited")
      updated.map(_.answers) shouldBe Right(Map.empty)
      service.get(started.id).map(_.rateCatalog.version) shouldBe Some("edited")

    "keep the stored session when the config is invalid" in:
      val service = newService()
      val started = service.start(ArchitectureOption.RatesAndQuestions).futureValue
      service.updateConfig(started, "{}", fullConfig.questionJson, fullConfig.calculationJson).isLeft shouldBe true
      service.get(started.id) shouldBe Some(started)

    "ignore a submitted question pack when questions are fixed" in:
      val service = newService()
      val started = service.start(ArchitectureOption.RatesOnly).futureValue
      val updated = service.updateConfig(started, fixedConfig.rateJson, fullConfig.questionJson, fixedConfig.calculationJson)
      updated.map(_.questionPack) shouldBe Right(fixedPack)

  "CalculationsSessionService.restoreDefaults" should:
    "put back the default config and clear answers" in:
      val service = newService()
      val started = service.start(ArchitectureOption.RatesAndQuestions).futureValue
      val edited = service
        .updateConfig(started, Json.toJson(catalog.copy(version = "edited")).toString, fullConfig.questionJson, fullConfig.calculationJson)
        .map(service.saveAnswer(_, "taxYears", "2017-18"))
        .toOption
        .get

      val restored = service.restoreDefaults(edited).futureValue
      restored.config shouldBe fullConfig
      restored.answers shouldBe empty

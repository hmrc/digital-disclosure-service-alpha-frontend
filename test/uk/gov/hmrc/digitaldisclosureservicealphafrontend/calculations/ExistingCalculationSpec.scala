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
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ExistingCalculation

class ExistingCalculationSpec extends AnyWordSpec with Matchers:

  "ExistingCalculation.fromRetrieveJson" should:
    "read personal allowance and tax bands from the HIP retrieve shape" in:
      val json = Json.parse(scala.io.Source.fromResource("calculations/stubs/hip-5294-2017-18.json").mkString)
      val calc = ExistingCalculation.fromRetrieveJson(json, "2017-18").toOption.get
      calc.taxYear shouldBe "2017-18"
      calc.calculationId shouldBe Some("poc-hip-5294-2017-18")
      calc.personalAllowance shouldBe Some(BigDecimal(11850))
      calc.blindPersonsAllowance shouldBe Some(BigDecimal(2320))
      calc.basicRateBand shouldBe Some(BigDecimal(33500))
      calc.basicRate shouldBe Some(BigDecimal("0.20"))
      calc.higherRate shouldBe Some(BigDecimal("0.40"))

    "overlay those figures onto a RatePack" in:
      val pack = DefaultConfigs.rate2017_18
      val calc = ExistingCalculation(
        taxYear = "2017-18",
        calculationId = Some("id"),
        personalAllowance = Some(11850),
        blindPersonsAllowance = None,
        basicRateBand = Some(33500),
        basicRate = Some(BigDecimal("0.20")),
        higherRate = Some(BigDecimal("0.40"))
      )
      val overlaid = ExistingCalculation.overlay(pack, calc)
      overlaid.personalAllowance shouldBe BigDecimal(11850)
      overlaid.blindPersonsAllowance shouldBe pack.blindPersonsAllowance
      overlaid.version shouldBe "2017-18.downstream"

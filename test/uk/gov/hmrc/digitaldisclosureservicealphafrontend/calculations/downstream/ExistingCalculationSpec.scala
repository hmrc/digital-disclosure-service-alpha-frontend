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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.downstream

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*

import scala.io.Source
import scala.util.Using

class ExistingCalculationSpec extends AnyWordSpec with Matchers:

  "ExistingCalculation.fromRetrieveJson" should:
    "read personal allowance and tax bands from the HIP retrieve shape" in:
      val json = Json.parse(Using.resource(Source.fromResource("calculations/stubs/hip-5294-2017-18.json"))(_.mkString))
      val calc = ExistingCalculation.fromRetrieveJson(json, "2017-18").toOption.get
      calc.taxYear shouldBe "2017-18"
      calc.calculationId shouldBe Some("poc-hip-5294-2017-18")
      calc.values shouldBe Map(
        "personalAllowance"     -> BigDecimal(11850),
        "blindPersonsAllowance" -> BigDecimal(2320),
        "basicRateBand"         -> BigDecimal(33500),
        "basicRate"             -> BigDecimal("0.20"),
        "higherRate"            -> BigDecimal("0.40")
      )

  "ExistingCalculation.overlay" should:
    "replace only the figures the calculation has, and ignore rates the catalogue does not declare" in:
      val pack = ratesFor("2017-18")
      val calc = ExistingCalculation(
        taxYear = "2017-18",
        calculationId = Some("id"),
        values = Map("personalAllowance" -> BigDecimal(12000), "notARate" -> BigDecimal(1))
      )
      val overlaid = ExistingCalculation.overlay(pack, calc)
      overlaid.value("personalAllowance") shouldBe BigDecimal(12000)
      overlaid.value("blindPersonsAllowance") shouldBe pack.value("blindPersonsAllowance")
      overlaid.values.keys.toSeq shouldBe pack.values.keys.toSeq
      overlaid.version shouldBe "2017-18.downstream"

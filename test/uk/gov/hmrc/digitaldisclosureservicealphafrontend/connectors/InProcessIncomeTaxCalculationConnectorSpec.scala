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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.http.HeaderCarrier

class InProcessIncomeTaxCalculationConnectorSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private val connector = new InProcessIncomeTaxCalculationConnector
  private given HeaderCarrier = HeaderCarrier()

  "InProcessIncomeTaxCalculationConnector" should:
    "return the canned 2017-18 retrieve" in:
      val result = connector.getCalculationDetails("AA123456A", "2017-18").futureValue
      result.toOption.get.personalAllowance shouldBe Some(BigDecimal(11850))

    "reject years before 2017-18" in:
      val result = connector.getCalculationDetails("AA123456A", "2015-16").futureValue
      result match
        case Left(_: CalcRetrieveError.UnsupportedYear) => succeed
        case other                                      => fail(other.toString)

    "return not found for later years with no stub data" in:
      val result = connector.getCalculationDetails("AA123456A", "2018-19").futureValue
      result match
        case Left(_: CalcRetrieveError.NotFound) => succeed
        case other                               => fail(other.toString)

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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.http.HeaderCarrier

import scala.concurrent.ExecutionContext.Implicits.global

class DownstreamRateCatalogServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private given HeaderCarrier = HeaderCarrier()

  "DownstreamRateCatalogService.overlay" should:
    "overlay 2017-18 from the GET stub and leave earlier years" in:
      val rates = downstreamRatesService.overlay(catalog).futureValue
      rates.summary.yearsFetched shouldBe Seq("2017-18")
      rates.summary.yearsUnchanged should contain allOf ("2015-16", "2016-17")
      rates.catalog.version shouldBe s"${catalog.version}+downstream"
      rates.catalog.forYear("2017-18").map(_.personalAllowance) shouldBe Some(BigDecimal(11850))
      rates.catalog.forYear("2015-16") shouldBe catalog.forYear("2015-16")

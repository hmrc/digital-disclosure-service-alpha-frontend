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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.DownstreamRateCatalogService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.InProcessIncomeTaxCalculationConnector
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext.Implicits.global

class DownstreamRateCatalogServiceSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private val config = Configuration(
    "appName"                                     -> "digital-disclosure-service-alpha-frontend",
    "auth.sign-in-url"                            -> "http://localhost:9949/auth-login-stub/gg-sign-in",
    "upscan.max-file-size"                        -> 10485760L,
    "microservice.services.dds-frontend.host"     -> "localhost",
    "microservice.services.dds-frontend.port"     -> 9000,
    "microservice.services.dds-frontend.protocol" -> "http",
    "calculations.mtd-retrieve.nino"              -> "AA123456A"
  )
  private val appConfig = new AppConfig(config, new ServicesConfig(config))
  private val service =
    new DownstreamRateCatalogService(new InProcessIncomeTaxCalculationConnector, appConfig)
  private given HeaderCarrier = HeaderCarrier()

  "DownstreamRateCatalogService" should:
    "leave Option 1 catalogues unchanged" in:
      val prepared = service.prepare(ArchitectureOption.RatesOnly).futureValue
      prepared.downstream shouldBe None
      prepared.catalog.years.find(_.taxYear == "2017-18").map(_.personalAllowance) shouldBe
        Some(DefaultConfigs.rate2017_18.personalAllowance)

    "overlay 2017-18 from the GET stub and leave earlier years" in:
      val prepared = service.prepare(ArchitectureOption.DownstreamRates).futureValue
      val summary = prepared.downstream.get
      summary.yearsFetched shouldBe Seq("2017-18")
      summary.yearsUnchanged should contain allOf ("2015-16", "2016-17")
      prepared.catalog.years.find(_.taxYear == "2017-18").map(_.personalAllowance) shouldBe Some(BigDecimal(11850))
      prepared.catalog.years.find(_.taxYear == "2015-16").map(_.personalAllowance) shouldBe
        Some(DefaultConfigs.rate2015_16.personalAllowance)

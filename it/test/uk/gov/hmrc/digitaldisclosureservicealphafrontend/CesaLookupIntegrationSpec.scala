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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend

import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, urlEqualTo}
import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneServerPerSuite
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.CesaLookupService
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.test.WireMockSupport

import scala.concurrent.ExecutionContext.Implicits.global

/** Stands in for cesa-stub with WireMock, using the same paths and link shapes the stub serves. */
class CesaLookupIntegrationSpec
  extends AnyWordSpec
     with Matchers
     with ScalaFutures
     with IntegrationPatience
     with WireMockSupport
     with GuiceOneServerPerSuite:

  override def fakeApplication(): Application =
    GuiceApplicationBuilder()
      .configure(
        "microservice.services.cesa.host" -> "localhost",
        "microservice.services.cesa.port" -> wireMockPort
      )
      .build()

  private given HeaderCarrier = HeaderCarrier()

  private val utr     = "1234567890"
  private val base    = s"/self-assessment/individual/$utr"
  private val baseUrl = s"http://localhost:$port/digital-disclosure-service-alpha-frontend"

  private def stub(path: String, status: Int, body: String = ""): Unit =
    wireMockServer.stubFor(
      get(urlEqualTo(path)).willReturn(
        aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)
      )
    )

  private def stubFullUser(): Unit =
    stub(base, 200, s"""{"links":{"designatoryDetails":"$base/designatory-details","accountSummary":"$base/account-summary"}}""")
    stub(s"$base/designatory-details", 200, s"""{"links":{"taxpayer":"$base/designatory-details/taxpayer"}}""")
    stub(s"$base/designatory-details/taxpayer", 200, """{"name":{"forename":"Alex","surname":"Example"}}""")
    stub(s"$base/account-summary", 200, """{"totalAmountDueToHmrc":{"amount":12345.67,"currency":"GBP"}}""")
    stub(s"$base/calendar", 200, """{"years":[]}""")
    stub(s"$base/account/futureliabilities", 404, s"CESA future liabilitiy details not found for user with UTR: $utr")

  "CesaLookupService" should:
    "call the root resource, follow its links (two levels), and fetch calendar and future liabilities" in:
      stubFullUser()

      val calls = app.injector.instanceOf[CesaLookupService].lookup(utr).futureValue

      calls.map(c => c.label -> c.status) shouldBe Seq(
        "Individual (links)" -> Some(200),
        "designatoryDetails" -> Some(200),
        "taxpayer"           -> Some(200),
        "accountSummary"     -> Some(200),
        "Return calendar"    -> Some(200),
        "Future liabilities" -> Some(404)
      )
      calls.find(_.label == "accountSummary").flatMap(_.json).map(j => (j \ "totalAmountDueToHmrc" \ "amount").as[BigDecimal]) shouldBe
        Some(BigDecimal("12345.67"))

    "not follow links when the root resource is not found" in:
      stub(base, 404)
      stub(s"$base/calendar", 500)
      stub(s"$base/account/futureliabilities", 404)

      val calls = app.injector.instanceOf[CesaLookupService].lookup(utr).futureValue

      calls.map(c => c.label -> c.status) shouldBe Seq(
        "Individual (links)" -> Some(404),
        "Return calendar"    -> Some(500),
        "Future liabilities" -> Some(404)
      )

  "GET /cesa" should:
    "render the lookup page with no UTR" in:
      val response = app.injector.instanceOf[HttpClientV2].get(url"$baseUrl/cesa").execute[HttpResponse].futureValue

      response.status shouldBe 200
      response.body should include("CESA lookup proof of concept")

    "show every CESA response for a UTR" in:
      stubFullUser()

      val response =
        app.injector.instanceOf[HttpClientV2].get(url"$baseUrl/cesa?utr=$utr").execute[HttpResponse].futureValue

      response.status shouldBe 200
      response.body should include(s"Responses for $utr")
      response.body should include("5 of 6 calls returned a 2xx status.")
      response.body should include("12345.67")

    "reject a UTR containing unexpected characters" in:
      val response =
        app.injector.instanceOf[HttpClientV2].get(url"$baseUrl/cesa?utr=${"../../etc"}").execute[HttpResponse].futureValue

      response.status shouldBe 400
      response.body should include("Enter a UTR using only letters, numbers and hyphens")

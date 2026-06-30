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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.play.guice.GuiceOneAppPerSuite
import play.api.Application
import play.api.http.Status
import play.api.inject.bind
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{PaymentJourney, UploadJourney}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.{PaymentJourneyRepository, UploadJourneyRepository}

import scala.concurrent.Future

class IndividualPocHubControllerSpec
  extends AnyWordSpec
     with Matchers
     with GuiceOneAppPerSuite:

  private val stubRepo = new UploadJourneyRepository:
    def upsert(journey: UploadJourney): Future[Unit] = Future.successful(())
    def findByReference(reference: String): Future[Option[UploadJourney]] = Future.successful(None)

  private val stubPaymentRepo = new PaymentJourneyRepository:
    def upsert(journey: PaymentJourney): Future[Unit] = Future.successful(())
    def get(id: String): Future[Option[PaymentJourney]] = Future.successful(None)

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .disable[uk.gov.hmrc.mongo.play.PlayMongoModule]
      .configure(
        "payments.seed-card-payment-internal-auth-on-start" -> false,
        "features.individual-auth-poc"                      -> true
      )
      .overrides(
        bind[UploadJourneyRepository].toInstance(stubRepo),
        bind[PaymentJourneyRepository].toInstance(stubPaymentRepo)
      )
      .build()

  private val controller = app.injector.instanceOf[IndividualPocHubController]

  "GET /individual-poc hub" should:
    "return 200 when feature flag enabled" in:
      val result = controller.hub(FakeRequest("GET", "/individual-poc"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Individual auth and NINO")

  "feature flag disabled" should:
    "return 404" in:
      val disabledApp = new GuiceApplicationBuilder()
        .disable[uk.gov.hmrc.mongo.play.PlayMongoModule]
        .configure(
          "payments.seed-card-payment-internal-auth-on-start" -> false,
          "features.individual-auth-poc"                      -> false
        )
        .overrides(
          bind[UploadJourneyRepository].toInstance(stubRepo),
          bind[PaymentJourneyRepository].toInstance(stubPaymentRepo)
        )
        .build()

      val disabledController = disabledApp.injector.instanceOf[IndividualPocHubController]
      val result             = disabledController.hub(FakeRequest("GET", "/individual-poc"))
      status(result) shouldBe Status.NOT_FOUND

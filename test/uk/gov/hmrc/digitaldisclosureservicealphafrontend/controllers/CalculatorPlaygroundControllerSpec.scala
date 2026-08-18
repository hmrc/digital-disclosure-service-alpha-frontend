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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{NrsJourney, PaymentJourney, UploadJourney}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.playground.PlaygroundOption
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.{NrsJourneyRepository, PaymentJourneyRepository, UploadJourneyRepository}

import scala.concurrent.Future

class CalculatorPlaygroundControllerSpec
  extends AnyWordSpec
     with Matchers
     with GuiceOneAppPerSuite:

  private val stubRepo = new UploadJourneyRepository:
    def upsert(journey: UploadJourney): Future[Unit] = Future.successful(())
    def findByReference(reference: String): Future[Option[UploadJourney]] = Future.successful(None)

  private val stubPaymentRepo = new PaymentJourneyRepository:
    def upsert(journey: PaymentJourney): Future[Unit] = Future.successful(())
    def get(id: String): Future[Option[PaymentJourney]] = Future.successful(None)

  private val stubNrsRepo = new NrsJourneyRepository:
    def upsert(journey: NrsJourney): Future[Unit] = Future.successful(())
    def get(id: String): Future[Option[NrsJourney]] = Future.successful(None)

  override def fakeApplication(): Application =
    new GuiceApplicationBuilder()
      .disable[uk.gov.hmrc.mongo.play.PlayMongoModule]
      .configure("payments.seed-card-payment-internal-auth-on-start" -> false)
      .overrides(
        bind[UploadJourneyRepository].toInstance(stubRepo),
        bind[PaymentJourneyRepository].toInstance(stubPaymentRepo),
        bind[NrsJourneyRepository].toInstance(stubNrsRepo)
      )
      .build()

  private val controller = app.injector.instanceOf[CalculatorPlaygroundController]

  "GET /calculator-playground" should:
    "return 200" in:
      val result = controller.home(FakeRequest())
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Config-driven calculator playground")

  "GET /calculator-playground/start/rates-and-questions" should:
    "create a session and redirect to config" in:
      val result = controller.start(PlaygroundOption.RatesAndQuestions.id)(FakeRequest())
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).exists(_.endsWith("/calculator-playground/config")) shouldBe true
      session(result).get("playgroundId") shouldBe defined

  "config and task list" should:
    "render after start" in:
      val start = controller.start(PlaygroundOption.RatesOnly.id)(FakeRequest())
      val id = session(start).get("playgroundId").get
      val configResult = controller.config(FakeRequest().withSession("playgroundId" -> id))
      status(configResult) shouldBe Status.OK
      contentAsString(configResult) should include("Rate pack")

      val taskList = controller.taskList(FakeRequest().withSession("playgroundId" -> id))
      status(taskList) shouldBe Status.OK
      contentAsString(taskList) should include("Prepare your disclosure")

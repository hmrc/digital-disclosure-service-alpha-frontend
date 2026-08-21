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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{NrsJourney, PaymentJourney, UploadJourney}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.{
  NrsJourneyRepository,
  PaymentJourneyRepository,
  UploadJourneyRepository
}

import scala.concurrent.Future

class CalculationsControllerSpec
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

  private val controller = app.injector.instanceOf[CalculationsController]

  "GET /calculations" should:
    "return 200" in:
      val result = controller.home(FakeRequest())
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Config-driven calculations")

  "GET /calculations/start/rates-and-questions" should:
    "create a session and redirect to config" in:
      val result = controller.start(ArchitectureOption.RatesAndQuestions.id)(FakeRequest())
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).exists(_.endsWith("/calculations/config")) shouldBe true
      session(result).get("calculationsId") shouldBe defined

  "GET /calculations/start/rates-only" should:
    "create a session and go straight to the task list" in:
      val result = controller.start(ArchitectureOption.RatesOnly.id)(FakeRequest())
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).exists(_.endsWith("/calculations/task-list")) shouldBe true
      session(result).get("calculationsId") shouldBe defined

  "config and task list" should:
    "render after start" in:
      val start = controller.start(ArchitectureOption.RatesOnly.id)(FakeRequest())
      val id = session(start).get("calculationsId").get
      val configResult = controller.config(FakeRequest().withSession("calculationsId" -> id))
      status(configResult) shouldBe Status.OK
      contentAsString(configResult) should include("Rate catalogue")
      contentAsString(configResult) should not include "Question pack (JSON)"

      val taskList = controller.taskList(FakeRequest().withSession("calculationsId" -> id))
      status(taskList) shouldBe Status.OK
      contentAsString(taskList) should include("Your digital disclosure")
      contentAsString(taskList) should include("Prepare your disclosure")

    "show the question pack when it is editable" in:
      val start = controller.start(ArchitectureOption.RatesAndQuestions.id)(FakeRequest())
      val id = session(start).get("calculationsId").get
      val configResult = controller.config(FakeRequest().withSession("calculationsId" -> id))

      status(configResult) shouldBe Status.OK
      contentAsString(configResult) should include("Question pack (JSON)")

  "GET /calculations/graph" should:
    "render the extracted graph sections" in:
      val start = controller.start(ArchitectureOption.RatesAndQuestions.id)(FakeRequest())
      val id = session(start).get("calculationsId").get

      val graph = controller.graph(FakeRequest().withSession("calculationsId" -> id))
      status(graph) shouldBe Status.OK
      contentAsString(graph) should include("Journey formats to compare")
      contentAsString(graph) should include("Worked examples")
      contentAsString(graph) should include("What happens")
      contentAsString(graph) should include("If the result is less than £0")
      contentAsString(graph) should not include "max("
      contentAsString(graph) should include("Mermaid source")

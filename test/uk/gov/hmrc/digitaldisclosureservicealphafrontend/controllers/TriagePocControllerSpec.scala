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
import play.api.test.CSRFTokenHelper.*
import play.api.test.FakeRequest
import play.api.test.Helpers.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{NrsJourney, PaymentJourney, TriageHandoff, UploadJourney}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.{NrsJourneyRepository, PaymentJourneyRepository, UploadJourneyRepository}

import scala.concurrent.Future

class TriagePocControllerSpec
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

  private val controller = app.injector.instanceOf[TriagePocController]
  private val secret     = "alpha-poc-not-for-production"

  private def get(path: String, extraSession: (String, String)*) =
    FakeRequest(GET, path).withSession(extraSession*).withCSRFToken

  "GET /triage-poc" should:
    "show the options hub and clear PoC session keys" in:
      val request = get(
        "/triage-poc",
        TriageHandoff.DraftRegimeKey -> "income-tax",
        TriageHandoff.SessionRegimeKey -> "income-tax"
      )
      val result = controller.demo(request)
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Two entry URLs")
      session(result).get(TriageHandoff.DraftRegimeKey) shouldBe None
      session(result).get(TriageHandoff.SessionRegimeKey) shouldBe None

  "GOV.UK stand-in" should:
    "show the classifier for a known option" in:
      val result = controller.govuk("two-urls")(get("/triage-poc/two-urls/govuk"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Pretend this is www.gov.uk")

    "redirect unknown options to the hub" in:
      val result = controller.govuk("nope")(get("/triage-poc/nope/govuk"))
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).get should include("/triage-poc")

    "redirect a valid answer to the Start now page" in:
      val request = FakeRequest(POST, "/triage-poc/two-urls/govuk")
        .withFormUrlEncodedBody("regime" -> "income-tax")
        .withCSRFToken
      val result = controller.govukSubmit("two-urls")(request)
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).get should include("/two-urls/govuk/done/income-tax")

    "reject an empty answer" in:
      val request = FakeRequest(POST, "/triage-poc/query/govuk")
        .withFormUrlEncodedBody("regime" -> "")
        .withCSRFToken
      val result = controller.govukSubmit("query")(request)
      status(result) shouldBe Status.BAD_REQUEST

    "redirect unknown options on submit" in:
      val request = FakeRequest(POST, "/triage-poc/nope/govuk")
        .withFormUrlEncodedBody("regime" -> "income-tax")
        .withCSRFToken
      val result = controller.govukSubmit("nope")(request)
      status(result) shouldBe Status.SEE_OTHER

    "render Start now for each option and exit" in:
      val cases = Seq(
        ("two-urls", "income-tax", "/start/ready"),
        ("two-urls", "mixed", "/wrong-place"),
        ("two-urls", "skip", "/start"),
        ("confirm", "income-tax", "/start/confirm"),
        ("confirm", "mixed", "/wrong-place"),
        ("confirm", "skip", "/start"),
        ("query", "income-tax", "regimes=income-tax"),
        ("query", "mixed", "regimes=mixed"),
        ("query", "skip", "regimes=unknown"),
        ("path", "income-tax", "/start/path/income-tax"),
        ("path", "mixed", "/start/path/mixed"),
        ("path", "skip", "/start/path/untriaged"),
        ("token", "income-tax", "regime=income-tax"),
        ("token", "mixed", "regime=mixed"),
        ("token", "skip", "regime=untriaged")
      )
      cases.foreach { (option, exit, expected) =>
        val result = controller.govukDone(option, exit)(get(s"/triage-poc/$option/govuk/done/$exit"))
        status(result) shouldBe Status.OK
        contentAsString(result) should include(expected)
      }

    "redirect invalid done pages to the hub" in:
      status(controller.govukDone("nope", "income-tax")(get("/"))) shouldBe Status.SEE_OTHER
      status(controller.govukDone("two-urls", "cgt")(get("/"))) shouldBe Status.SEE_OTHER

  "pre-auth MDTP" should:
    "show the questions" in:
      val result = controller.preauth(get("/triage-poc/preauth"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("This is MDTP, not GOV.UK")

    "store the answer in session and continue" in:
      val request = FakeRequest(POST, "/triage-poc/preauth")
        .withFormUrlEncodedBody("regime" -> "income-tax")
        .withCSRFToken
      val result = controller.preauthSubmit(request)
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).get should include("/start/session")
      session(result).get(TriageHandoff.SessionRegimeKey) shouldBe Some("income-tax")

    "reject an empty pre-auth answer" in:
      val request = FakeRequest(POST, "/triage-poc/preauth")
        .withFormUrlEncodedBody("regime" -> "bogus")
        .withCSRFToken
      status(controller.preauthSubmit(request)) shouldBe Status.BAD_REQUEST

  "DDS landings" should:
    "ask routing questions when there is no signal" in:
      val result = controller.startDefault(get("/triage-poc/start"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Classifier questions")

    "skip routing on /start/ready" in:
      val result = controller.startReady(get("/triage-poc/start/ready"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Routing questions skipped")
      contentAsString(result) should include("tampered URL")

    "show confirmation on /start/confirm" in:
      val result = controller.startConfirm(get("/triage-poc/start/confirm"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Is that still right?")

    "interpret path exits" in:
      status(controller.startPath("income-tax")(get("/"))) shouldBe Status.OK
      contentAsString(controller.startPath("mixed")(get("/"))) should include("existing disclosure service")
      contentAsString(controller.startPath("untriaged")(get("/"))) should include("Classifier questions")
      contentAsString(controller.startPath("cgt")(get("/"))) should include("Classifier questions")

    "read query params" in:
      val ready = controller.startDefault(get("/triage-poc/start?origin=govuk&regimes=income-tax"))
      contentAsString(ready) should include("Routing questions skipped")
      contentAsString(ready) should include("tampered URL")
      contentAsString(controller.startDefault(get("/triage-poc/start?origin=govuk&regimes=mixed"))) should include(
        "existing disclosure service"
      )
      contentAsString(controller.startDefault(get("/triage-poc/start?origin=govuk&regimes=cgt"))) should include(
        "Classifier questions"
      )

    "read a Play session" in:
      val ready = controller.startSession(
        get("/triage-poc/start/session", TriageHandoff.SessionRegimeKey -> "income-tax")
      )
      contentAsString(ready) should include("Routing questions skipped")
      contentAsString(
        controller.startSession(get("/triage-poc/start/session", TriageHandoff.SessionRegimeKey -> "mixed"))
      ) should include("existing disclosure service")
      contentAsString(
        controller.startSession(get("/triage-poc/start/session", TriageHandoff.SessionRegimeKey -> "skip"))
      ) should include("Classifier questions")
      contentAsString(controller.startSession(get("/triage-poc/start/session"))) should include("Classifier questions")

    "verify a signed token" in:
      val token = TriageHandoff.sign("income-tax", secret)
      val ready = controller.startToken(get(s"/triage-poc/start/token?regime=income-tax&token=$token"))
      contentAsString(ready) should include("Routing questions skipped")
      contentAsString(ready) should include("tampered URL")

      val mixed = TriageHandoff.sign("mixed", secret)
      contentAsString(controller.startToken(get(s"/triage-poc/start/token?regime=mixed&token=$mixed"))) should include(
        "existing disclosure service"
      )

      val unknown = TriageHandoff.sign("untriaged", secret)
      contentAsString(controller.startToken(get(s"/triage-poc/start/token?regime=untriaged&token=$unknown"))) should include(
        "Classifier questions"
      )
      contentAsString(controller.startToken(get("/triage-poc/start/token?regime=income-tax&token=deadbeef"))) should include(
        "Classifier questions"
      )
      contentAsString(controller.startToken(get("/triage-poc/start/token"))) should include("Classifier questions")

    "render the dedicated wrong-place page" in:
      val result = controller.wrongPlace(get("/triage-poc/wrong-place"))
      status(result) shouldBe Status.OK
      contentAsString(result) should include("existing disclosure service")

  "POST routing / confirm / accept-hint" should:
    "record an in-DDS answer" in:
      val request = FakeRequest(POST, "/triage-poc/routing")
        .withFormUrlEncodedBody("regime" -> "income-tax")
        .withCSRFToken
      val result = controller.submitRouting(request)
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).get should include("/result")
      session(result).get(TriageHandoff.DraftSourceKey) shouldBe Some("answered-in-dds")
      session(result).get(TriageHandoff.DraftSkippedKey) shouldBe Some("false")

    "send mixed routing to the other service" in:
      val request = FakeRequest(POST, "/triage-poc/routing")
        .withFormUrlEncodedBody("regime" -> "mixed")
        .withCSRFToken
      redirectLocation(controller.submitRouting(request)).get should include("/wrong-place")

    "reject an empty routing answer" in:
      val request = FakeRequest(POST, "/triage-poc/routing")
        .withFormUrlEncodedBody("regime" -> "")
        .withCSRFToken
      status(controller.submitRouting(request)) shouldBe Status.BAD_REQUEST

    "record a confirmation" in:
      val request = FakeRequest(POST, "/triage-poc/confirm")
        .withFormUrlEncodedBody("stillRight" -> "yes")
        .withCSRFToken
      val result = controller.submitConfirm(request)
      session(result).get(TriageHandoff.DraftSourceKey) shouldBe Some("confirmation")
      session(result).get(TriageHandoff.DraftSkippedKey) shouldBe Some("true")

    "drop a rejected confirmation onto the default start" in:
      val request = FakeRequest(POST, "/triage-poc/confirm")
        .withFormUrlEncodedBody("stillRight" -> "no")
        .withCSRFToken
      redirectLocation(controller.submitConfirm(request)).get should include("/start")

    "reject an empty confirmation" in:
      val request = FakeRequest(POST, "/triage-poc/confirm")
        .withFormUrlEncodedBody("stillRight" -> "maybe")
        .withCSRFToken
      status(controller.submitConfirm(request)) shouldBe Status.BAD_REQUEST

    "accept a routing hint" in:
      val request = FakeRequest(POST, "/triage-poc/accept-hint")
        .withFormUrlEncodedBody("regime" -> "income-tax", "source" -> "session")
        .withCSRFToken
      val result = controller.acceptHint(request)
      session(result).get(TriageHandoff.DraftSourceKey) shouldBe Some("session")
      session(result).get(TriageHandoff.DraftSkippedKey) shouldBe Some("true")

    "default the hint source when it is omitted" in:
      val request = FakeRequest(POST, "/triage-poc/accept-hint")
        .withFormUrlEncodedBody("regime" -> "income-tax")
        .withCSRFToken
      session(controller.acceptHint(request)).get(TriageHandoff.DraftSourceKey) shouldBe Some("url-hint")

    "ignore a non-income-tax hint" in:
      val request = FakeRequest(POST, "/triage-poc/accept-hint")
        .withFormUrlEncodedBody("regime" -> "mixed")
        .withCSRFToken
      redirectLocation(controller.acceptHint(request)).get should include("/start")

    "redirect a missing hint payload to the default start" in:
      val request = FakeRequest(POST, "/triage-poc/accept-hint")
        .withFormUrlEncodedBody("regime" -> "")
        .withCSRFToken
      redirectLocation(controller.acceptHint(request)).get should include("/start")

  "GET /triage-poc/result" should:
    "show the stored draft" in:
      val request = get(
        "/triage-poc/result",
        TriageHandoff.DraftRegimeKey  -> "income-tax",
        TriageHandoff.DraftSourceKey  -> "url-hint",
        TriageHandoff.DraftSkippedKey -> "true"
      )
      val result = controller.result(request)
      status(result) shouldBe Status.OK
      contentAsString(result) should include("url-hint")
      contentAsString(result) should include("Yes")

    "default source and skipped when those keys are missing" in:
      val request = get("/triage-poc/result", TriageHandoff.DraftRegimeKey -> "income-tax")
      val result = controller.result(request)
      status(result) shouldBe Status.OK
      contentAsString(result) should include("url-hint")
      contentAsString(result) should include("answered in DDS")

    "redirect to the hub when there is no draft" in:
      val result = controller.result(get("/triage-poc/result"))
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).get should include("/triage-poc")

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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ArchitectureOption, QuestionType, ResolvedQuestion}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session.CalculationsSessionService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.{TaskListBuilder, TaskStatus}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{NrsJourney, PaymentJourney, UploadJourney}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.{
  NrsJourneyRepository,
  PaymentJourneyRepository,
  UploadJourneyRepository
}

import scala.annotation.tailrec
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
  private val sessions = app.injector.instanceOf[CalculationsSessionService]

  private def startSession(option: ArchitectureOption): String =
    session(controller.start(option.id)(FakeRequest())).get("calculationsId").get

  private def post(id: String, questionId: String, value: String, returnTo: Option[String] = None) =
    controller.submitQuestion(questionId, None, returnTo)(
      FakeRequest("POST", "/").withSession("calculationsId" -> id).withFormUrlEncodedBody("value" -> value)
    )

  private def neutral(q: ResolvedQuestion): String =
    q.questionType match
      case QuestionType.yesNo                                  => "no"
      case QuestionType.currency                               => "0"
      case QuestionType.singleChoice | QuestionType.checkboxes => q.options.head.value
      case QuestionType.text                                   => "x"

  /** Answers every question task by task, as a user would: the given answer where there is one, else a neutral one. */
  private def answerEverything(id: String, answers: Map[String, String]): Unit =
    @tailrec def loop(steps: Int): Unit =
      val state = sessions.get(id).get
      val open = TaskListBuilder.build(state).allItems.filter(i => i.status != TaskStatus.CannotStartYet && i.id != TaskListBuilder.FinalTaskId)
      open.flatMap(_.questionIds.find(q => !state.answers.contains(q))).headOption match
        case Some(questionId) if steps > 0 =>
          val question = QuestionEngine.find(state.questionPack, state.rateCatalog, state.answers, questionId).get
          status(post(id, questionId, answers.getOrElse(questionId, neutral(question)))) shouldBe Status.SEE_OTHER
          loop(steps - 1)
        case _ => ()
    loop(500)

  private def get(id: String) = FakeRequest().withSession("calculationsId" -> id)

  "GET /calculations" should:
    "return 200" in:
      val result = controller.home(FakeRequest())
      status(result) shouldBe Status.OK
      contentAsString(result) should include("Config-driven calculations")
      contentAsString(result) should include("Option 4")

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

  "GET /calculations/start/downstream-rates" should:
    "create a session from the GET stub and go to the task list" in:
      val result = controller.start(ArchitectureOption.DownstreamRates.id)(FakeRequest())
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).exists(_.endsWith("/calculations/task-list")) shouldBe true
      val id = session(result).get("calculationsId").get
      val taskList = controller.taskList(FakeRequest().withSession("calculationsId" -> id))
      status(taskList) shouldBe Status.OK
      val body = contentAsString(taskList)
      body should include("HIP 5294")
      body should include("2017-18")
      body should include("2015-16")
      val configResult = controller.config(FakeRequest().withSession("calculationsId" -> id))
      contentAsString(configResult) should include("11850")

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

  "POST /calculations/question/:id" should:
    "reject an invalid amount, then save a valid one and include it in the result" in:
      val id = startSession(ArchitectureOption.RatesOnly)
      status(post(id, "taxYears", "")) shouldBe Status.BAD_REQUEST
      answerEverything(id, Map("taxYears" -> "2017-18"))

      val invalid = post(id, "dividends__2017-18", "lots")
      status(invalid) shouldBe Status.BAD_REQUEST
      contentAsString(invalid) should include("govuk-error-message")

      status(post(id, "dividends__2017-18", "20,000")) shouldBe Status.SEE_OTHER
      // (20,000 − 11,500 personal allowance) × 20%
      contentAsString(controller.result(get(id))) should include("£1,700.00")

    "reject a value that is not one of the question's options" in:
      val id = startSession(ArchitectureOption.RatesOnly)
      status(post(id, "taxYears", "1999-00")) shouldBe Status.BAD_REQUEST

    "not save an answer to a question in a task that cannot start yet" in:
      val id = startSession(ArchitectureOption.RatesOnly)
      status(post(id, "taxYears", "2017-18")) shouldBe Status.SEE_OTHER
      status(post(id, "dividends__2017-18", "20000")) shouldBe Status.SEE_OTHER
      sessions.get(id).get.answers should not contain key("dividends__2017-18")

  "stored answers" should:
    val year = "2017-18"
    val useAllowance = s"selfEmploymentUseTradingAllowance__$year"
    val allowance = s"selfEmploymentTradingAllowance__$year"
    val expenses = s"selfEmploymentExpenses__$year"

    def selfEmployed(): String =
      val id = startSession(ArchitectureOption.FeelRules)
      answerEverything(id, Map(
        "taxYears" -> year,
        "incomeTypes" -> "selfEmployment",
        useAllowance -> "yes",
        allowance -> "1000",
        s"selfEmploymentTurnover__$year" -> "30000",
        s"penaltyBehaviour__$year" -> "reasonableExcuse"
      ))
      id

    "only calculate once every task is complete" in:
      val id = startSession(ArchitectureOption.FeelRules)
      val result = controller.result(get(id))
      status(result) shouldBe Status.SEE_OTHER
      redirectLocation(result).exists(_.endsWith("/calculations/task-list")) shouldBe true
      contentAsString(controller.cya(get(id))) should include("Complete every task before you can calculate")

    "after a change on check your answers, ask only the new question, then go back there" in:
      val id = selfEmployed()
      contentAsString(controller.cya(get(id))) should include("Calculate estimate")

      val change = post(id, useAllowance, "no", Some("check-answers"))
      redirectLocation(change).get should (include(s"/calculations/question/$expenses") and include("returnTo=check-answers"))

      val answered = post(id, expenses, "5000", Some("check-answers"))
      redirectLocation(answered).exists(_.endsWith("/calculations/cya")) shouldBe true

      val state = sessions.get(id).get
      state.answers should not contain key(allowance)
      state.storedAnswers.get(allowance) shouldBe Some("1000")
      // (30,000 turnover − 5,000 expenses − 11,500 personal allowance) × 20%: the allowance off the path is not deducted
      contentAsString(controller.result(get(id))) should include("£2,700.00")

    "count an earlier answer again when its question comes back" in:
      val id = selfEmployed()
      status(post(id, useAllowance, "no", Some("check-answers"))) shouldBe Status.SEE_OTHER
      status(post(id, expenses, "5000", Some("check-answers"))) shouldBe Status.SEE_OTHER

      val back = post(id, useAllowance, "yes", Some("check-answers"))
      redirectLocation(back).exists(_.endsWith("/calculations/cya")) shouldBe true

      val state = sessions.get(id).get
      state.answers.get(allowance) shouldBe Some("1000")
      state.answers should not contain key(expenses)
      state.storedAnswers.get(expenses) shouldBe Some("5000")

    "not save an answer from a page that no longer applies" in:
      val id = selfEmployed()
      status(post(id, "incomeTypes", "dividends")) shouldBe Status.SEE_OTHER
      val stale = post(id, allowance, "7000")
      redirectLocation(stale).exists(_.endsWith("/calculations/task-list")) shouldBe true
      flash(stale).get("calculations-info") shouldBe Some("calculations.flash.noLongerApplies")
      sessions.get(id).get.storedAnswers.get(allowance) shouldBe Some("1000")

      val page = contentAsString(controller.storedAnswers(get(id)))
      page should (include("Stored answers") and include("No: off the path"))

  "GET /calculations/graph" should:
    "render the extracted graph sections" in:
      val start = controller.start(ArchitectureOption.RatesAndQuestions.id)(FakeRequest())
      val id = session(start).get("calculationsId").get

      val graph = controller.graph(FakeRequest().withSession("calculationsId" -> id))
      status(graph) shouldBe Status.OK
      contentAsString(graph) should include("How the question journey works")
      contentAsString(graph) should include("Shown if the user ticks ‘Self-employment income’")
      contentAsString(graph) should include("Not used in estimate")
      contentAsString(graph) should not include "Journey formats to compare"
      contentAsString(graph) should include("Worked examples")
      contentAsString(graph) should include("How the calculation works")
      contentAsString(graph) should include("Rates used for each tax year")
      contentAsString(graph) should include("calculations-computation__row--total")
      contentAsString(graph) should include("What happens")
      contentAsString(graph) should include("If the result is less than £0")
      contentAsString(graph) should not include "max("
      contentAsString(graph) should include("Mermaid source")

  "Option 5, calculation rules in FEEL" should:
    def startFeel(): String =
      val start = controller.start(ArchitectureOption.FeelRules.id)(FakeRequest())
      status(start) shouldBe Status.SEE_OTHER
      redirectLocation(start).exists(_.endsWith("/calculations/task-list")) shouldBe true
      session(start).get("calculationsId").get

    "appear on the home page" in:
      contentAsString(controller.home(FakeRequest())) should include("Option 5 — Calculation rules in FEEL")

    "start on the task list, with a link to the rules in plain English" in:
      val id = startFeel()
      contentAsString(controller.taskList(FakeRequest().withSession("calculationsId" -> id))) should
        include("How the estimate is worked out (FEEL rules in plain English)")

    "set the rules out in plain English with the worked example’s figures" in:
      val id = startFeel()
      val rules = controller.rules(FakeRequest().withSession("calculationsId" -> id))
      status(rules) shouldBe Status.OK
      val body = contentAsString(rules)
      body should include("How the estimate is worked out")
      body should include("Total income − Personal allowance − Blind Person’s Allowance, not below zero")
      body should include("Show the FEEL rule")
      body should include("round half up(max(0, taxBeforePayments - taxAlreadyPaid), 2)")
      body should include("£231.00")

    "calculate the result with the FEEL rules" in:
      val id = startFeel()
      answerEverything(id, Map("taxYears" -> "2017-18", "incomeTypes" -> "dividends", "dividends__2017-18" -> "20000"))

      val result = contentAsString(controller.result(FakeRequest().withSession("calculationsId" -> id)))
      // (20,000 − 11,500 personal allowance) × 20%
      result should include("£1,700.00")
      result should include("income-tax-design-focus-feel 1.0.0 (FEEL)")

    "edit the FEEL rules on the config page, and reject a rule that is not valid FEEL" in:
      val id = startFeel()
      val configResult = controller.config(FakeRequest().withSession("calculationsId" -> id))
      status(configResult) shouldBe Status.OK
      contentAsString(configResult) should include("Calculation rules (FEEL, in JSON)")
      contentAsString(configResult) should include("Question pack (JSON)")
      contentAsString(configResult) should include("income-tax-design-focus-feel")

      val defaults = uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
      val config = defaults.defaultsFor(ArchitectureOption.FeelRules)
      def save(questionJson: String, calculationJson: String) =
        controller.saveConfig(
          FakeRequest("POST", "/")
            .withSession("calculationsId" -> id)
            .withFormUrlEncodedBody("rateJson" -> config.rateJson, "questionJson" -> questionJson, "calculationJson" -> calculationJson)
        )

      val broken = defaults.feelRules.json.replace("\"sum(groups.income)\"", "\"sum(groups.income\"")
      val badFeel = save(config.questionJson, broken)
      status(badFeel) shouldBe Status.BAD_REQUEST
      contentAsString(badFeel) should include("Not valid FEEL")

      // a rule that reads a question the edited journey no longer has is reported
      val renamed = config.questionJson.replace("\"id\" : \"dividends\"", "\"id\" : \"ukDividends\"")
      renamed should not be config.questionJson
      val missingQuestion = save(renamed, defaults.feelRules.json)
      status(missingQuestion) shouldBe Status.BAD_REQUEST
      contentAsString(missingQuestion) should include("&#x27;answers.dividends&#x27; is not a question in the question pack")

      status(save(config.questionJson, defaults.feelRules.json)) shouldBe Status.SEE_OTHER

    "send other options from the rules page to the graph page" in:
      val start = controller.start(ArchitectureOption.RatesOnly.id)(FakeRequest())
      val id = session(start).get("calculationsId").get
      redirectLocation(controller.rules(FakeRequest().withSession("calculationsId" -> id))).exists(_.endsWith("/calculations/graph")) shouldBe
        true

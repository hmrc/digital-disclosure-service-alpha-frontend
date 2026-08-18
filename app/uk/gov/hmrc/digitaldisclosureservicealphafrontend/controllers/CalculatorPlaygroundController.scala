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

import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Request, Result}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.playground.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.playground.*
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}

final case class ConfigFormData(rateJson: String, questionJson: String)

@Singleton
class CalculatorPlaygroundController @Inject()(
  mcc         : MessagesControllerComponents,
  sessionStore: PlaygroundSessionStore,
  homePage    : PlaygroundHomePage,
  configPage  : PlaygroundConfigPage,
  taskListPage: PlaygroundTaskListPage,
  questionPage: PlaygroundQuestionPage,
  cyaPage     : PlaygroundCyaPage,
  resultPage  : PlaygroundResultPage
) extends FrontendController(mcc) with I18nSupport:

  private val sessionKey = "playgroundId"

  private val configForm: Form[ConfigFormData] = Form(
    mapping(
      "rateJson"     -> nonEmptyText,
      "questionJson" -> text
    )(ConfigFormData.apply)(f => Some((f.rateJson, f.questionJson)))
  )

  val home: Action[AnyContent] =
    Action:
      implicit request =>
        Ok(homePage())

  def start(optionId: String): Action[AnyContent] =
    Action:
      implicit request =>
        PlaygroundOption.fromId(optionId) match
          case None => Redirect(routes.CalculatorPlaygroundController.home)
          case Some(PlaygroundOption.FullEngine) =>
            Redirect(routes.CalculatorPlaygroundController.home)
              .flashing("playground-info" -> "Option 3 is documented only — pick Option 1 or 2 to run the playground.")
          case Some(option) =>
            val state = sessionStore.create(option)
            Redirect(routes.CalculatorPlaygroundController.config)
              .withSession(request.session + (sessionKey -> state.id))

  val config: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(
            configPage(
              state,
              configForm.fill(ConfigFormData(state.rateJson, state.questionJson)),
              None
            )
          )

  val saveConfig: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          configForm
            .bindFromRequest()
            .fold(
              formWithErrors => BadRequest(configPage(state, formWithErrors, None)),
              data =>
                sessionStore.updateConfig(state.id, data.rateJson, data.questionJson) match
                  case Left(error) =>
                    BadRequest(
                      configPage(
                        state,
                        configForm.fill(data),
                        Some(error)
                      )
                    )
                  case Right(_) =>
                    Redirect(routes.CalculatorPlaygroundController.taskList)
                      .flashing("playground-info" -> "Configuration saved. Answers were cleared.")
            )

  val loadDefaults: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val (rateJson, questionJson, rates, questions) = DefaultConfigs.defaultsFor(state.option)
          sessionStore.save(
            state.copy(
              rateJson = rateJson,
              questionJson = questionJson,
              ratePack = rates,
              questionPack = questions,
              answers = Map.empty
            )
          )
          Redirect(routes.CalculatorPlaygroundController.config)
            .flashing("playground-info" -> "Default configuration restored.")

  val taskList: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val visible = QuestionEngine.visibleQuestions(state.questionPack, state.answers)
          Ok(taskListPage(state, visible))

  def question(id: String): Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          QuestionEngine.find(state.questionPack, id) match
            case None => Redirect(routes.CalculatorPlaygroundController.taskList)
            case Some(q) if !QuestionEngine.isVisible(q, state.answers) =>
              Redirect(routes.CalculatorPlaygroundController.taskList)
            case Some(q) =>
              val back = QuestionEngine.previousQuestionId(state.questionPack, state.answers, id)
              Ok(questionPage(state, q, answerFormFor(q, state.answers), back, None))

  def submitQuestion(id: String): Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          QuestionEngine.find(state.questionPack, id) match
            case None => Redirect(routes.CalculatorPlaygroundController.taskList)
            case Some(q) =>
              val back = QuestionEngine.previousQuestionId(state.questionPack, state.answers, id)
              bindAnswer(q).fold(
                formWithErrors => BadRequest(questionPage(state, q, formWithErrors, back, None)),
                raw =>
                  val value = raw.trim
                  if q.required && value.isEmpty then
                    BadRequest(
                      questionPage(
                        state,
                        q,
                        answerFormFor(q, state.answers).withError("value", "playground.error.required"),
                        back,
                        None
                      )
                    )
                  else if q.`type` == QuestionType.currency && value.nonEmpty && !isMoney(value) then
                    BadRequest(
                      questionPage(
                        state,
                        q,
                        answerFormFor(q, state.answers).withError("value", "playground.error.currency"),
                        back,
                        None
                      )
                    )
                  else
                    val updated = sessionStore.putAnswer(state.id, id, value).getOrElse(state)
                    QuestionEngine.nextQuestion(updated.questionPack, updated.answers, Some(id)) match
                      case Some(next) =>
                        Redirect(routes.CalculatorPlaygroundController.question(next.id))
                      case None =>
                        Redirect(routes.CalculatorPlaygroundController.cya)
              )

  val cya: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val rows = QuestionEngine
            .visibleQuestions(state.questionPack, state.answers)
            .map(q => q -> state.answers.getOrElse(q.id, ""))
          Ok(cyaPage(state, rows))

  val result: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val calc = SimpleLiabilityCalculator.calculate(state.ratePack, state.answers)
          Ok(resultPage(state, calc))

  val resetAnswers: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          sessionStore.clearAnswers(state.id)
          Redirect(routes.CalculatorPlaygroundController.taskList)
            .flashing("playground-info" -> "Answers cleared.")

  private def withState(block: PlaygroundState => Result)(implicit request: Request[?]): Result =
    request.session
      .get(sessionKey)
      .flatMap(sessionStore.get)
      .map(block)
      .getOrElse(Redirect(routes.CalculatorPlaygroundController.home))

  private def answerFormFor(q: ConfigQuestion, answers: Map[String, String]): Form[String] =
    val existing = answers.getOrElse(q.id, "")
    Form("value" -> text).fill(existing)

  private def bindAnswer(q: ConfigQuestion)(implicit request: Request[AnyContent]): Form[String] =
    q.`type` match
      case QuestionType.checkboxes =>
        val values = request.body.asFormUrlEncoded
          .map(_.getOrElse("value", Nil))
          .getOrElse(Nil)
          .filter(_.nonEmpty)
        Form("value" -> text).fill(QuestionEngine.joinMulti(values))
      case _ =>
        Form("value" -> text).bindFromRequest()

  private def isMoney(raw: String): Boolean =
    scala.util.Try(BigDecimal(raw.replace(",", "").trim)).isSuccess

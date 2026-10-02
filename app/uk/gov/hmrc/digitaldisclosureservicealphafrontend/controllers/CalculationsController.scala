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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.ConfigJsonHighlight
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{AnswerValidator, LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.GraphBuilder
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  ArchitectureOption,
  QuestionType,
  ResolvedQuestion,
  SessionState
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session.CalculationsSessionService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.{TaskListBuilder, TaskListItem, TaskStatus}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.calculations.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

final case class CalculationsConfigFormData(rateJson: String, questionJson: String, calculationJson: String)

@Singleton
class CalculationsController @Inject()(
  mcc         : MessagesControllerComponents,
  sessions    : CalculationsSessionService,
  homePage    : HomePage,
  configPage  : ConfigPage,
  taskListPage: TaskListPage,
  questionPage: QuestionPage,
  cyaPage     : CyaPage,
  resultPage  : ResultPage,
  graphPage   : GraphPage
)(implicit ec: ExecutionContext)
    extends FrontendController(mcc) with I18nSupport:

  private val sessionKey = "calculationsId"
  private val infoFlash = "calculations-info"

  private val configForm: Form[CalculationsConfigFormData] = Form(
    mapping(
      "rateJson"        -> nonEmptyText,
      "questionJson"    -> text,
      "calculationJson" -> nonEmptyText
    )(CalculationsConfigFormData.apply)(f => Some((f.rateJson, f.questionJson, f.calculationJson)))
  )

  val home: Action[AnyContent] =
    Action:
      implicit request =>
        Ok(homePage())

  def start(optionId: String): Action[AnyContent] =
    Action.async:
      implicit request =>
        given HeaderCarrier = HeaderCarrier()
        ArchitectureOption.fromId(optionId) match
          case None =>
            Future.successful(Redirect(routes.CalculationsController.home))
          case Some(option) if !option.implemented =>
            Future.successful(Redirect(routes.CalculationsController.home).flashing(infoFlash -> "calculations.flash.option3"))
          case Some(option) =>
            sessions.start(option).map: state =>
              val landing =
                if option.landsOnTaskList then routes.CalculationsController.taskList
                else routes.CalculationsController.config
              val redirect = Redirect(landing).withSession(request.session + (sessionKey -> state.id))
              if option.fetchesDownstreamRates then redirect.flashing(infoFlash -> "calculations.flash.downstreamFetched")
              else redirect

  val config: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val data = CalculationsConfigFormData(state.config.rateJson, state.config.questionJson, state.config.calculationJson)
          Ok(configPage(state, configForm.fill(data), Nil))

  val saveConfig: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          configForm
            .bindFromRequest()
            .fold(
              formWithErrors => BadRequest(configPage(state, formWithErrors, Nil)),
              data =>
                sessions.updateConfig(state, data.rateJson, data.questionJson, data.calculationJson) match
                  case Right(_) =>
                    Redirect(routes.CalculationsController.taskList).flashing(infoFlash -> "calculations.flash.configSaved")
                  case Left(violations) =>
                    val highlights =
                      ConfigJsonHighlight.build(data.rateJson, data.questionJson, data.calculationJson, violations)
                    val formWithFieldErrors = highlights.foldLeft(configForm.fill(data)): (form, field) =>
                      form.withError(field.fieldId, "calculations.config.validation.fieldError", field.violations.size)
                    BadRequest(configPage(state, formWithFieldErrors, highlights))
            )

  val loadDefaults: Action[AnyContent] =
    Action.async:
      implicit request =>
        given HeaderCarrier = HeaderCarrier()
        currentState match
          case None => Future.successful(Redirect(routes.CalculationsController.home))
          case Some(state) =>
            sessions.restoreDefaults(state).map: _ =>
              Redirect(routes.CalculationsController.config).flashing(infoFlash -> "calculations.flash.defaultsRestored")

  val taskList: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(taskListPage(state, TaskListBuilder.build(state)))

  def question(id: String, task: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        withQuestion(id, task): (state, question, item) =>
          if item.exists(_.status == TaskStatus.CannotStartYet) then Redirect(routes.CalculationsController.taskList)
          else Ok(questionPage(state, question, answerForm(question, state), backHref(item, id), item.map(_.id)))

  def submitQuestion(id: String, task: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        withQuestion(id, task): (state, question, item) =>
          def showError(form: Form[String]) =
            BadRequest(questionPage(state, question, form, backHref(item, id), item.map(_.id)))

          bindAnswer(question).fold(
            showError,
            raw =>
              AnswerValidator.validate(question, raw) match
                case Left(errorKey) =>
                  showError(answerForm(question, state).withError("value", errorKey))
                case Right(value) =>
                  sessions.saveAnswer(state, id, value)
                  item.flatMap(TaskListBuilder.nextQuestionId(_, id)) match
                    case Some(nextId) => Redirect(routes.CalculationsController.question(nextId, item.map(_.id)))
                    case None         => Redirect(routes.CalculationsController.taskList)
          )

  val cya: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val rows = QuestionEngine
            .visibleQuestions(state.questionPack, state.rateCatalog, state.answers, translate)
            .map(q => q -> state.answers.getOrElse(q.id, ""))
          Ok(cyaPage(state, rows))

  val result: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(resultPage(state, LiabilityCalculator.calculate(state.rateCatalog, state.calculationSpec, state.answers)))

  val graph: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(graphPage(state, GraphBuilder.build(state, translate)))

  val resetAnswers: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          sessions.clearAnswers(state)
          Redirect(routes.CalculationsController.taskList).flashing(infoFlash -> "calculations.flash.answersCleared")

  private def translate(implicit request: Request[?]): String => String =
    val msgs = messagesApi.preferred(request)
    key => msgs(key)

  private def currentState(implicit request: Request[?]): Option[SessionState] =
    request.session.get(sessionKey).flatMap(sessions.get)

  private def withState(block: SessionState => Result)(implicit request: Request[?]): Result =
    currentState.map(block).getOrElse(Redirect(routes.CalculationsController.home))

  /** Finds a visible question and the task it is being answered in (the given task, else the one that holds it). */
  private def withQuestion(id: String, task: Option[String])(
    block: (SessionState, ResolvedQuestion, Option[TaskListItem]) => Result
  )(implicit request: Request[?]): Result =
    withState: state =>
      QuestionEngine.find(state.questionPack, state.rateCatalog, state.answers, id, translate) match
        case None => Redirect(routes.CalculationsController.taskList)
        case Some(question) =>
          val model = TaskListBuilder.build(state)
          val item = task.flatMap(taskId => model.allItems.find(_.id == taskId)).orElse(model.itemForQuestion(id))
          block(state, question, item)

  private def backHref(item: Option[TaskListItem], questionId: String): String =
    item
      .flatMap(TaskListBuilder.previousQuestionId(_, questionId))
      .map(previous => routes.CalculationsController.question(previous, item.map(_.id)).url)
      .getOrElse(routes.CalculationsController.taskList.url)

  private def answerForm(question: ResolvedQuestion, state: SessionState): Form[String] =
    Form("value" -> text).fill(state.answers.getOrElse(question.id, ""))

  private def bindAnswer(question: ResolvedQuestion)(implicit request: Request[AnyContent]): Form[String] =
    question.questionType match
      case QuestionType.checkboxes =>
        val values = request.body.asFormUrlEncoded.flatMap(_.get("value")).getOrElse(Nil)
        Form("value" -> text).fill(Answers.joinMulti(values))
      case _ =>
        Form("value" -> text).bindFromRequest()

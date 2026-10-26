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
import play.api.mvc.{Action, AnyContent, Call, MessagesControllerComponents, Request, Result}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{ConfigJsonHighlight, DefaultConfigs}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{
  AnswerLogPageBuilder,
  AnswerValidator,
  LiabilityCalculator,
  QuestionEngine
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel.{FeelCalculator, FeelRulesPageBuilder}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.GraphBuilder
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  ArchitectureOption,
  LogEntry,
  QuestionType,
  ResolvedQuestion,
  SessionState,
  Via,
  YearAnswers
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session.CalculationsSessionService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.{TaskListBuilder, TaskListItem, TaskStatus}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.calculations.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import java.time.{Clock, Instant}
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

final case class CalculationsConfigFormData(rateJson: String, questionJson: String, calculationJson: String)

/** Where a question page goes once its task has nothing left to ask. A closed set, so a link can't redirect elsewhere. */
enum ReturnTo(val key: String):
  case TaskList     extends ReturnTo("task-list")
  case CheckAnswers extends ReturnTo("check-answers")

  /** The query string value, left out for the task list. */
  def param: Option[String] = Option.when(this != TaskList)(key)

object ReturnTo:
  def from(key: Option[String]): ReturnTo = key.flatMap(k => values.find(_.key == k)).getOrElse(TaskList)

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
  graphPage   : GraphPage,
  rulesPage   : RulesPage,
  confirmPage : ConfirmAnswersPage,
  logPage     : AnswerLogPage,
  clock       : Clock
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
          val calculation = state.feelRules.fold(state.config.calculationJson)(_.json)
          val data = CalculationsConfigFormData(state.config.rateJson, state.config.questionJson, calculation)
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

  def question(id: String, task: Option[String], returnTo: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        val back = ReturnTo.from(returnTo)
        withQuestion(id, task): (state, question, item) =>
          if item.exists(_.status == TaskStatus.CannotStartYet) then Redirect(routes.CalculationsController.taskList)
          else Ok(questionPage(state, question, answerForm(question, state), backHref(item, id, back), item.map(_.id), back.param, prefilled(state, question)))

  /**
    * Checks the answer and adds it to the log. The next page is worked out from the answers just saved, so a change
    * that opens a branch asks the new questions, then returns to check your answers or the task list.
    */
  def submitQuestion(id: String, task: Option[String], returnTo: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        val back = ReturnTo.from(returnTo)
        withState: state =>
          QuestionEngine.find(state.questionPack, state.rateCatalog, state.answers, id, translate) match
            case None => notOnPath(state, id, back)
            case Some(question) =>
              val item = taskFor(state, id, task)
              if item.exists(_.status == TaskStatus.CannotStartYet) then Redirect(routes.CalculationsController.taskList)
              else
                def showError(form: Form[String]) =
                  BadRequest(questionPage(state, question, form, backHref(item, id, back), item.map(_.id), back.param, false))

                bindAnswer(question).fold(
                  showError,
                  raw =>
                    AnswerValidator.validate(question, raw) match
                      case Left(errorKey) =>
                        showError(bindAnswer(question).withError("value", errorKey))
                      case Right(value) =>
                        val updated = sessions.append(state, LogEntry.AnswerGiven(id, value, Instant.now(clock), via(back)))
                        Redirect(next(updated, item.map(_.id), Some(id), back))
                )

  val cya: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val model = TaskListBuilder.build(state)
          val rows = QuestionEngine
            .visibleQuestions(state.questionPack, state.rateCatalog, state.answers, translate)
            .map(q => (q, state.answers.getOrElse(q.id, ""), model.itemForQuestion(q.id).map(_.id)))
          Ok(cyaPage(state, rows, model.readyToCalculate))

  /** The estimate, once every task is complete. Before that it would count missing answers as £0. */
  val result: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          if !TaskListBuilder.build(state).readyToCalculate then
            Redirect(routes.CalculationsController.taskList).flashing(infoFlash -> "calculations.flash.notReady")
          else
            val result = state.feelRules match
              case Some(feel) =>
                FeelCalculator.calculate(state.rateCatalog, state.questionPack, feel.compiled, state.answers, translate)
              case None =>
                LiabilityCalculator.calculate(state.rateCatalog, state.calculationSpec, state.answers)
            Ok(resultPage(state, result))

  /** A task's earlier answers that are back on the path, to confirm together (its `onReturn` is `confirmAsGroup`). */
  def confirmAnswers(task: String, returnTo: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        val back = ReturnTo.from(returnTo)
        withTask(task): (state, item) =>
          val ids = TaskListBuilder.toConfirm(item, state.journey)
          if ids.isEmpty then Redirect(next(state, Some(task), None, back))
          else
            val questions = QuestionEngine
              .visibleQuestions(state.questionPack, state.rateCatalog, state.answers, translate)
              .filter(q => ids.contains(q.id))
            Ok(confirmPage(state, item, questions.map(q => q -> state.journey.lapsed.getOrElse(q.id, "")), back.param))

  def submitConfirmAnswers(task: String, returnTo: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        val back = ReturnTo.from(returnTo)
        withTask(task): (state, item) =>
          val ids = TaskListBuilder.toConfirm(item, state.journey)
          val updated =
            if ids.isEmpty then state
            else sessions.append(state, LogEntry.AnswersConfirmed(ids, Instant.now(clock), Via.ConfirmPage))
          Redirect(next(updated, Some(task), None, back))

  /** Every entry on the session's answer log, with what each one did. */
  val answerLog: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(logPage(state, AnswerLogPageBuilder.build(state, translate)))

  val graph: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(graphPage(state, GraphBuilder.build(state, translate)))

  /** Option 5's FEEL rules written out for stakeholders. Other options explain themselves on the graph page. */
  val rules: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          state.feelRules match
            case Some(feel) =>
              Ok(rulesPage(state, FeelRulesPageBuilder.build(state.config, feel, DefaultConfigs.workedExamples, translate)))
            case None =>
              Redirect(routes.CalculationsController.graph)

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
        case None           => Redirect(routes.CalculationsController.taskList)
        case Some(question) => block(state, question, taskFor(state, id, task))

  private def withTask(task: String)(block: (SessionState, TaskListItem) => Result)(implicit request: Request[?]): Result =
    withState: state =>
      TaskListBuilder.build(state).allItems.find(_.id == task).filter(_.status != TaskStatus.CannotStartYet) match
        case None       => Redirect(routes.CalculationsController.taskList)
        case Some(item) => block(state, item)

  /**
    * The task a question is answered in: the given task, else the one that usually holds it, preferring one that can
    * be started. A question can sit in more than one task, so it is locked only when every task holding it is.
    */
  private def taskFor(state: SessionState, questionId: String, task: Option[String]): Option[TaskListItem] =
    val model = TaskListBuilder.build(state)
    val open = model.allItems.filter(i => i.questionIds.contains(questionId) && i.status != TaskStatus.CannotStartYet)
    val named = task.flatMap(taskId => model.allItems.find(_.id == taskId))
    val usual = model.itemForQuestion(questionId)
    named.filter(open.contains).orElse(usual.filter(open.contains)).orElse(open.headOption).orElse(named).orElse(usual)

  /**
    * A post for a question that is not on the user's path, for example from a page left open in another tab. It goes
    * on the log, where it is ignored, so the log shows what happened. Ids that aren't questions at all are not logged.
    */
  private def notOnPath(state: SessionState, id: String, back: ReturnTo)(implicit request: Request[AnyContent]): Result =
    if state.questionPack.questions.exists(_.id == YearAnswers.parse(id)._1) then
      val posted = Answers.joinMulti(request.body.asFormUrlEncoded.flatMap(_.get("value")).getOrElse(Nil)).take(500)
      sessions.append(state, LogEntry.AnswerGiven(id, posted, Instant.now(clock), via(back)))
    Redirect(routes.CalculationsController.taskList).flashing(infoFlash -> "calculations.flash.noLongerApplies")

  /**
    * Where to go after a save, worked out from the answers just saved. Look in the task the user is in, then in any
    * other open task that holds the question they changed: first for earlier answers to confirm, then for the first
    * question still to answer. With nothing left, go back where the user came from.
    */
  private def next(state: SessionState, taskId: Option[String], changed: Option[String], back: ReturnTo): Call =
    val open = TaskListBuilder.build(state).allItems.filter(i => i.status != TaskStatus.CannotStartYet && i.id != TaskListBuilder.FinalTaskId)
    val tasks = (taskId.flatMap(id => open.find(_.id == id)).toSeq ++ changed.toSeq.flatMap(id => open.filter(_.questionIds.contains(id)))).distinct
    val step = tasks.view.flatMap: task =>
      if TaskListBuilder.toConfirm(task, state.journey).nonEmpty then
        Some(routes.CalculationsController.confirmAnswers(task.id, back.param))
      else
        task.questionIds
          .find(id => !state.answers.contains(id))
          .map(id => routes.CalculationsController.question(id, Some(task.id), back.param))
    step.headOption.getOrElse(returnCall(back))

  private def returnCall(back: ReturnTo): Call =
    back match
      case ReturnTo.TaskList     => routes.CalculationsController.taskList
      case ReturnTo.CheckAnswers => routes.CalculationsController.cya

  private def via(back: ReturnTo): Via =
    back match
      case ReturnTo.TaskList     => Via.QuestionPage
      case ReturnTo.CheckAnswers => Via.CheckAnswers

  private def backHref(item: Option[TaskListItem], questionId: String, back: ReturnTo): String =
    item
      .flatMap(TaskListBuilder.previousQuestionId(_, questionId))
      .map(previous => routes.CalculationsController.question(previous, item.map(_.id), back.param).url)
      .getOrElse(returnCall(back).url)

  /** The answer that counts, else the one given before the question left the path, to check it is still right. */
  private def answerForm(question: ResolvedQuestion, state: SessionState): Form[String] =
    Form("value" -> text).fill(state.answers.get(question.id).orElse(state.journey.lapsed.get(question.id)).getOrElse(""))

  private def prefilled(state: SessionState, question: ResolvedQuestion): Boolean =
    !state.answers.contains(question.id) && state.journey.lapsed.contains(question.id)

  private def bindAnswer(question: ResolvedQuestion)(implicit request: Request[AnyContent]): Form[String] =
    question.questionType match
      case QuestionType.checkboxes =>
        val values = request.body.asFormUrlEncoded.flatMap(_.get("value")).getOrElse(Nil)
        Form("value" -> text).fill(Answers.joinMulti(values))
      case _ =>
        Form("value" -> text).bindFromRequest()

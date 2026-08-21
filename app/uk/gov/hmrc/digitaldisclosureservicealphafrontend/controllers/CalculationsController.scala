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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{ConfigJsonHighlight, DefaultConfigs}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.GraphBuilder
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  QuestionType,
  ResolvedQuestion,
  SessionState
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.{TaskListBuilder, TaskStatus}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session.SessionStore
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.calculations.*
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}

final case class CalculationsConfigFormData(rateJson: String, questionJson: String, calculationJson: String)

@Singleton
class CalculationsController @Inject()(
  mcc         : MessagesControllerComponents,
  sessionStore: SessionStore,
  homePage    : HomePage,
  configPage  : ConfigPage,
  taskListPage: TaskListPage,
  questionPage: QuestionPage,
  cyaPage     : CyaPage,
  resultPage  : ResultPage,
  graphPage   : GraphPage
) extends FrontendController(mcc) with I18nSupport:

  private val sessionKey = "calculationsId"

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
    Action:
      implicit request =>
        ArchitectureOption.fromId(optionId) match
          case None => Redirect(routes.CalculationsController.home)
          case Some(ArchitectureOption.FullEngine) =>
            Redirect(routes.CalculationsController.home)
              .flashing("calculations-info" -> "calculations.flash.option3")
          case Some(option) =>
            val state = sessionStore.create(option)
            val landing =
              if option.landsOnTaskList then routes.CalculationsController.taskList
              else routes.CalculationsController.config
            Redirect(landing)
              .withSession(request.session + (sessionKey -> state.id))

  val config: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(
            configPage(
              state,
              configForm.fill(CalculationsConfigFormData(state.rateJson, state.questionJson, state.calculationJson)),
              Nil
            )
          )

  val saveConfig: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          configForm
            .bindFromRequest()
            .fold(
              formWithErrors => BadRequest(configPage(state, formWithErrors, Nil)),
              data =>
                sessionStore.updateConfig(state.id, data.rateJson, data.questionJson, data.calculationJson) match
                  case Left(violations) =>
                    val highlights = ConfigJsonHighlight.build(
                      data.rateJson,
                      data.questionJson,
                      data.calculationJson,
                      violations
                    )
                    val formWithFieldErrors =
                      highlights.foldLeft(configForm.fill(data)): (f, field) =>
                        f.withError(field.fieldId, "calculations.config.validation.fieldError", field.violations.size)
                    BadRequest(configPage(state, formWithFieldErrors, highlights))
                  case Right(_) =>
                    Redirect(routes.CalculationsController.taskList)
                      .flashing("calculations-info" -> "calculations.flash.configSaved")
            )

  val loadDefaults: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val defaults = DefaultConfigs.defaultsFor(state.option)
          sessionStore.save(
            state.copy(
              rateJson = defaults.rateJson,
              questionJson = defaults.questionJson,
              calculationJson = defaults.calculationJson,
              rateCatalog = defaults.catalog,
              questionPack = defaults.questions,
              calculationSpec = defaults.calculation,
              answers = Map.empty
            )
          )
          Redirect(routes.CalculationsController.config)
            .flashing("calculations-info" -> "calculations.flash.defaultsRestored")

  val taskList: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(taskListPage(state, TaskListBuilder.build(state, translate)))

  def question(id: String, task: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val model = TaskListBuilder.build(state, translate)
          val item = task.flatMap(tid => model.allItems.find(_.id == tid))
            .orElse(model.itemForQuestion(id))
          QuestionEngine.find(state.questionPack, state.rateCatalog, state.answers, id, translate) match
            case None => Redirect(routes.CalculationsController.taskList)
            case Some(q) =>
              if item.exists(_.status == TaskStatus.CannotStartYet) then
                Redirect(routes.CalculationsController.taskList)
              else
                val backHref = item
                  .flatMap(TaskListBuilder.previousQuestionId(_, id))
                  .map(prev => routes.CalculationsController.question(prev, item.map(_.id)).url)
                  .getOrElse(routes.CalculationsController.taskList.url)
                Ok(questionPage(state, q, answerFormFor(q, state.answers), backHref, item.map(_.id)))

  def submitQuestion(id: String, task: Option[String]): Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          val model = TaskListBuilder.build(state, translate)
          val item = task.flatMap(tid => model.allItems.find(_.id == tid))
            .orElse(model.itemForQuestion(id))
          QuestionEngine.find(state.questionPack, state.rateCatalog, state.answers, id, translate) match
            case None => Redirect(routes.CalculationsController.taskList)
            case Some(q) =>
              val backHref = item
                .flatMap(TaskListBuilder.previousQuestionId(_, id))
                .map(prev => routes.CalculationsController.question(prev, item.map(_.id)).url)
                .getOrElse(routes.CalculationsController.taskList.url)
              bindAnswer(q).fold(
                formWithErrors => BadRequest(questionPage(state, q, formWithErrors, backHref, item.map(_.id))),
                raw =>
                  val value = raw.trim
                  if q.required && value.isEmpty then
                    BadRequest(
                      questionPage(
                        state,
                        q,
                        answerFormFor(q, state.answers).withError("value", "calculations.error.required"),
                        backHref,
                        item.map(_.id)
                      )
                    )
                  else if q.questionType == QuestionType.currency && value.nonEmpty && !isMoney(value) then
                    BadRequest(
                      questionPage(
                        state,
                        q,
                        answerFormFor(q, state.answers).withError("value", "calculations.error.currency"),
                        backHref,
                        item.map(_.id)
                      )
                    )
                  else
                    sessionStore.putAnswer(state.id, id, value)
                    item.flatMap(TaskListBuilder.nextQuestionId(_, id)) match
                      case Some(nextId) =>
                        Redirect(routes.CalculationsController.question(nextId, item.map(_.id)))
                      case None =>
                        Redirect(routes.CalculationsController.taskList)
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
          val calc = LiabilityCalculator.calculate(state.rateCatalog, state.calculationSpec, state.answers)
          Ok(resultPage(state, calc))

  val graph: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          Ok(graphPage(state, GraphBuilder.build(state, translate)))

  val resetAnswers: Action[AnyContent] =
    Action:
      implicit request =>
        withState: state =>
          sessionStore.clearAnswers(state.id)
          Redirect(routes.CalculationsController.taskList)
            .flashing("calculations-info" -> "calculations.flash.answersCleared")

  private def translate(implicit request: Request[?]): String => String =
    val msgs = messagesApi.preferred(request)
    key => msgs(key)

  private def withState(block: SessionState => Result)(implicit request: Request[?]): Result =
    request.session
      .get(sessionKey)
      .flatMap(sessionStore.get)
      .map(block)
      .getOrElse(Redirect(routes.CalculationsController.home))

  private def answerFormFor(q: ResolvedQuestion, answers: Map[String, String]): Form[String] =
    Form("value" -> text).fill(answers.getOrElse(q.id, ""))

  private def bindAnswer(q: ResolvedQuestion)(implicit request: Request[AnyContent]): Form[String] =
    q.questionType match
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

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

import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Result}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.calculations.*
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class PenaltiesController @Inject()(
                                     mcc: MessagesControllerComponents,
                                     penaltiesQuestionPage: PenaltiesQuestionPage,
                                     penaltiesCYAPage: PenaltiesCYAPage,
                                     penaltiesResultPage: PenaltiesResultPage,
                                     penaltiesGraphPage: PenaltiesGraphPage
                                   )(implicit ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport:

  private val PENALTIES_SESSION_KEY = "penaltiesAnswers"

  def penaltiesGraph: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesGraphPage()): Result
    }

  def penaltiesBehaviour: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "behaviour")): Result
    }

  def submitPenaltiesBehaviour: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesTelling)
    }

  def penaltiesTelling: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "telling")): Result
    }

  def submitPenaltiesTelling: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesNature)
    }

  def penaltiesNature: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "nature")): Result
    }

  def submitPenaltiesNature: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesExtent)
    }

  def penaltiesExtent: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "extent")): Result
    }

  def submitPenaltiesExtent: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesTiming)
    }

  def penaltiesTiming: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "timing")): Result
    }

  def submitPenaltiesTiming: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesCYA)
    }

  def penaltiesResult: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesResultPage(taxOwed = BigDecimal("0.00"), penalty = BigDecimal("0.00"))): Result
    }

  def penaltiesView: Action[AnyContent] =
    Action { implicit request =>
      Ok("Penalties View")
    }

  def penaltiesQuestion(id: String): Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = id)): Result
    }

  def submitPenaltiesQuestion(id: String): Action[AnyContent] =
    Action { implicit request =>
      val answer = request.body.asFormUrlEncoded.flatMap(_.get("answer")).flatMap(_.headOption).getOrElse("")

      if (answer.isEmpty) {
        Redirect(routes.PenaltiesController.penaltiesQuestion(id))
          .flashing("error" -> "")
          .withSession(request.session)
      } else {
        val updatedSession = request.session + (s"${PENALTIES_SESSION_KEY}_$id" -> answer)

        id match
          case "behaviour" => Redirect(routes.PenaltiesController.penaltiesTelling).withSession(updatedSession)
          case "telling"   => Redirect(routes.PenaltiesController.penaltiesNature).withSession(updatedSession)
          case "nature"    => Redirect(routes.PenaltiesController.penaltiesExtent).withSession(updatedSession)
          case "extent"    => Redirect(routes.PenaltiesController.penaltiesTiming).withSession(updatedSession)
          case "timing"    => Redirect(routes.PenaltiesController.penaltiesCYA).withSession(updatedSession)
          case _           => Redirect(routes.CalculationsController.taskList).withSession(updatedSession)
      }
    }

  def penaltiesCYA: Action[AnyContent] =
    Action { implicit request =>
      val answers = Map(
        "behaviour" -> request.session.get(s"${PENALTIES_SESSION_KEY}_behaviour").getOrElse(""),
        "telling"   -> request.session.get(s"${PENALTIES_SESSION_KEY}_telling").getOrElse(""),
        "nature"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_nature").getOrElse(""),
        "extent"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_extent").getOrElse(""),
        "timing"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_timing").getOrElse("")
      )
      Ok(penaltiesCYAPage(answers)): Result
    }

  def submitPenaltiesCYA: Action[AnyContent] =
    Action { implicit request =>
      // Mark penalties as completed
      val updatedSession = request.session + ("penaltiesCompleted" -> "true")
      Redirect(routes.CalculationsController.taskList).withSession(updatedSession)
    }

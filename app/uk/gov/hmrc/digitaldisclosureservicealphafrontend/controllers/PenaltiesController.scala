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
      Ok(penaltiesQuestionPage(questionId = "penaltyBehaviour")): Result
    }

  def submitPenaltiesBehaviour: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesTelling)
    }

  def penaltiesTelling: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "penaltyTelling")): Result
    }

  def submitPenaltiesTelling: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesNature)
    }

  def penaltiesNature: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "penaltyNature")): Result
    }

  def submitPenaltiesNature: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesExtent)
    }

  def penaltiesExtent: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "penaltyExtent")): Result
    }

  def submitPenaltiesExtent: Action[AnyContent] =
    Action { implicit request =>
      Redirect(routes.PenaltiesController.penaltiesTiming)
    }

  def penaltiesTiming: Action[AnyContent] =
    Action { implicit request =>
      Ok(penaltiesQuestionPage(questionId = "penaltyTiming")): Result
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
          case "penaltyBehaviour" => Redirect(routes.PenaltiesController.penaltiesTelling).withSession(updatedSession)
          case "penaltyTelling"   => Redirect(routes.PenaltiesController.penaltiesNature).withSession(updatedSession)
          case "penaltyNature"    => Redirect(routes.PenaltiesController.penaltiesExtent).withSession(updatedSession)
          case "penaltyExtent"    => Redirect(routes.PenaltiesController.penaltiesTiming).withSession(updatedSession)
          case "penaltyTiming"    => Redirect(routes.CalculationsController.taskList).withSession(updatedSession + ("penaltiesCompleted" -> "true"))
          case _                  => Redirect(routes.CalculationsController.taskList).withSession(updatedSession)
      }
    }

  def penaltiesCYA: Action[AnyContent] =
    Action { implicit request =>
      val answers = Map(
        "penaltyBehaviour" -> request.session.get(s"${PENALTIES_SESSION_KEY}_penaltyBehaviour").getOrElse(""),
        "penaltyTelling"   -> request.session.get(s"${PENALTIES_SESSION_KEY}_penaltyTelling").getOrElse(""),
        "penaltyNature"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_penaltyNature").getOrElse(""),
        "penaltyExtent"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_penaltyExtent").getOrElse(""),
        "penaltyTiming"    -> request.session.get(s"${PENALTIES_SESSION_KEY}_penaltyTiming").getOrElse("")
      )
      Ok(penaltiesCYAPage(answers)): Result
    }

  def submitPenaltiesCYA: Action[AnyContent] =
    Action { implicit request =>
      val updatedSession = request.session + ("penaltiesCompleted" -> "true")
      Redirect(routes.CalculationsController.taskList).withSession(updatedSession)
    }

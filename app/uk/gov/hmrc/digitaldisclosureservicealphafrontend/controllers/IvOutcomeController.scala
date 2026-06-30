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

import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.IdentityVerificationFrontendConnector
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.IndividualPocEnabledAction
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.IvJourneyResult
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.IvOutcomePage
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IvOutcomeController @Inject()(
  mcc                         : MessagesControllerComponents,
  defaultAction               : play.api.mvc.DefaultActionBuilder,
  individualPocEnabled        : IndividualPocEnabledAction,
  identityVerificationConnector: IdentityVerificationFrontendConnector,
  appConfig                   : AppConfig,
  outcomePage                 : IvOutcomePage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with Logging:

  private val outcomeAction = defaultAction andThen individualPocEnabled

  val complete: Action[AnyContent] = outcomeAction.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)
      processOutcome(success = true)

  val failed: Action[AnyContent] = outcomeAction.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)
      processOutcome(success = false)

  private def processOutcome(success: Boolean)(using request: play.api.mvc.Request[AnyContent]): Future[play.api.mvc.Result] =
    val continueUrl = request.getQueryString("continueUrl").getOrElse("/digital-disclosure-service-alpha-frontend/individual-poc/session")
    val journeyId   = List(request.getQueryString("journeyId"), request.getQueryString("token")).flatten.headOption

    journeyId match
      case Some(id) =>
        identityVerificationConnector.getJourneyStatus(id).map {
          case Some(IvJourneyResult.Success) if success =>
            Redirect(continueUrl)
          case Some(result) =>
            Ok(outcomePage(result.toString, continueUrl, appConfig, success = false))
          case None if success =>
            // IV stub may redirect before status is readable; allow retry of protected page
            Redirect(continueUrl)
          case None =>
            Ok(outcomePage("Unknown", continueUrl, appConfig, success = false))
        }
      case None =>
        Future.successful(BadRequest(outcomePage("MissingJourneyId", continueUrl, appConfig, success = false)))

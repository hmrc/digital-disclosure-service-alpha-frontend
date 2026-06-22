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
import play.api.mvc.{Action, AnyContent, DefaultActionBuilder, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.{AgentPocEnabledAction, AuthenticatedAction}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{AgentInvitationStatus, AgentPocJourneyOption}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.AgentInvitationRepository
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.RelationshipWriterSelector
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.ClientConsentPage
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ClientConsentController @Inject()(
  mcc                 : MessagesControllerComponents,
  defaultAction       : DefaultActionBuilder,
  agentPocEnabled     : AgentPocEnabledAction,
  authenticate        : AuthenticatedAction,
  invitationRepository: AgentInvitationRepository,
  writerSelector      : RelationshipWriterSelector,
  appConfig           : AppConfig,
  consentPage         : ClientConsentPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with Logging:

  private val clientAction = defaultAction andThen agentPocEnabled andThen authenticate
  private val pocAction    = defaultAction andThen agentPocEnabled

  def viewInvitation(token: String): Action[AnyContent] =
    clientAction.async:
      implicit request =>
      invitationRepository.findByToken(token).map {
        case Some(invitation) =>
          Ok(consentPage(invitation, appConfig, isStrideAccept = false))
        case None =>
          NotFound("Invitation not found")
      }

  def acceptInvitation(token: String): Action[AnyContent] =
    clientAction.async:
      implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      invitationRepository.findByToken(token).flatMap {
        case None =>
          Future.successful(NotFound("Invitation not found"))
        case Some(invitation) =>
          val journey = AgentPocJourneyOption.fromString(invitation.journeyOption)
            .getOrElse(AgentPocJourneyOption.Option2)
          val writer  = writerSelector.forJourney(journey)

          for
            steps <- writer.writeRelationship(invitation)
            _     <- invitationRepository.updateStatus(token, AgentInvitationStatus.Accepted.toString)
          yield
            Ok(consentPage(
              invitation.copy(status = AgentInvitationStatus.Accepted.toString),
              appConfig,
              isStrideAccept = false,
              outcomeSteps   = steps
            ))
      }

  def claimEnrolment(token: String): Action[AnyContent] =
    clientAction.async:
      implicit request =>
      invitationRepository.findByToken(token).flatMap {
        case None =>
          Future.successful(NotFound("Invitation not found"))
        case Some(invitation) =>
          invitationRepository.updateStatus(token, AgentInvitationStatus.Claimed.toString).map { _ =>
            Ok(consentPage(
              invitation.copy(status = AgentInvitationStatus.Claimed.toString),
              appConfig,
              isStrideAccept = false,
              outcomeSteps   = Seq(
                "Client claimed the Unclaimed enrolment via the invitation link",
                "Production: ASA marks the principal enrolment as claimed by this client"
              )
            ))
          }
      }

  def strideAccept(token: String): Action[AnyContent] =
    pocAction.async:
      implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      invitationRepository.findByToken(token).flatMap {
        case None =>
          Future.successful(NotFound("Invitation not found"))
        case Some(invitation) =>
          val writer = writerSelector.forJourney(AgentPocJourneyOption.DigitallyExcluded)
          for
            steps <- writer.writeRelationship(invitation)
            _     <- invitationRepository.updateStatus(token, AgentInvitationStatus.Accepted.toString)
          yield
            Ok(consentPage(
              invitation.copy(status = AgentInvitationStatus.Accepted.toString),
              appConfig,
              isStrideAccept = true,
              outcomeSteps   = steps
            ))
      }

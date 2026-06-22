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
import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, DefaultActionBuilder, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.AgentInvitationRepository
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.{AgentRelationshipGateService, RelationshipWriterSelector}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.{AgentPocGateResultPage, AgentPocInvitePage}
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.time.Instant
import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentJourneyController @Inject()(
  mcc                 : MessagesControllerComponents,
  defaultAction       : DefaultActionBuilder,
  agentPocEnabled     : AgentPocEnabledAction,
  agentIdentifier     : AgentIdentifierAction,
  invitationRepository: AgentInvitationRepository,
  writerSelector      : RelationshipWriterSelector,
  gateService         : AgentRelationshipGateService,
  appConfig           : AppConfig,
  invitePage          : AgentPocInvitePage,
  gateResultPage      : AgentPocGateResultPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with Logging:

  private val inviteForm: Form[InviteClientForm] = Form(
    mapping(
      "clientId"     -> nonEmptyText,
      "clientIdType" -> nonEmptyText,
      "verifier"     -> nonEmptyText
    )(InviteClientForm.apply)(f => Some((f.clientId, f.clientIdType, f.verifier)))
  )

  private val agentAction = defaultAction andThen agentPocEnabled andThen agentIdentifier

  private def journeyLanding(journey: AgentPocJourneyOption): Action[AnyContent] =
    agentAction:
      implicit request =>
        Ok(invitePage(journey, inviteForm, request.arn, None, None))

  val option1: Action[AnyContent] = journeyLanding(AgentPocJourneyOption.Option1)
  val option2: Action[AnyContent] = journeyLanding(AgentPocJourneyOption.Option2)
  val digitallyExcluded: Action[AnyContent] = journeyLanding(AgentPocJourneyOption.DigitallyExcluded)

  private def submitInvite(journey: AgentPocJourneyOption): Action[AnyContent] =
    agentAction.async:
      implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      inviteForm.bindFromRequest().fold(
        formWithErrors =>
          Future.successful(
            BadRequest(invitePage(journey, formWithErrors, request.arn, None, None))
          ),
        form =>
          val token = UUID.randomUUID().toString
          val invitation = AgentInvitation(
            id            = UUID.randomUUID().toString,
            token         = token,
            arn           = request.arn,
            clientId      = form.clientId,
            clientIdType  = form.clientIdType,
            verifier      = form.verifier,
            journeyOption = journeyLabel(journey),
            status        = AgentInvitationStatus.Pending.toString,
            createdAt     = Instant.now()
          )

          for
            _ <- invitationRepository.upsert(invitation)
            consentUrl = s"${appConfig.ddsBaseUrl}/agent-poc/consent/$token"
            backRoute = journeyBackRoute(journey)
          yield
            Redirect(backRoute)
              .flashing(
                "agentPocConsentUrl" -> consentUrl,
                "agentPocClientId"   -> form.clientId,
                "agentPocClientType" -> form.clientIdType
              )
      )

  private def journeyLabel(journey: AgentPocJourneyOption): String = journey match
    case AgentPocJourneyOption.Option1         => "option-1"
    case AgentPocJourneyOption.Option2         => "option-2"
    case AgentPocJourneyOption.DigitallyExcluded => "digitally-excluded"

  private def journeyBackRoute(journey: AgentPocJourneyOption) = journey match
    case AgentPocJourneyOption.Option1         => routes.AgentJourneyController.option1
    case AgentPocJourneyOption.Option2         => routes.AgentJourneyController.option2
    case AgentPocJourneyOption.DigitallyExcluded => routes.AgentJourneyController.digitallyExcluded

  val submitOption1: Action[AnyContent] = submitInvite(AgentPocJourneyOption.Option1)
  val submitOption2: Action[AnyContent] = submitInvite(AgentPocJourneyOption.Option2)
  val submitDigitallyExcluded: Action[AnyContent] = submitInvite(AgentPocJourneyOption.DigitallyExcluded)

  val gateCheck: Action[AnyContent] =
    agentAction.async:
      implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      val clientId     = request.getQueryString("clientId").getOrElse("")
      val clientIdType = request.getQueryString("clientIdType").getOrElse(appConfig.agentPocDefaultClientIdType)

      if clientId.isEmpty then
        Future.successful(BadRequest("clientId query parameter is required"))
      else
        gateService.checkRelationship(request.arn, clientId, clientIdType).map { result =>
          Ok(gateResultPage(result, request.arn, clientId, clientIdType, appConfig))
        }

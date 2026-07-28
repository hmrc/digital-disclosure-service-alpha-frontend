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
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Result}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.NrsConnector
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.AuthenticatedAction
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.NrsJourneyRepository
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.NrsEvidenceService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class NrsSubmitForm(
  fullName           : String,
  regime             : String,
  amountPence        : Long,
  declarationConsent : Boolean,
  simulateFailure    : String
)

@Singleton
class NrsController @Inject()(
  mcc               : MessagesControllerComponents,
  authenticate      : AuthenticatedAction,
  nrsConnector      : NrsConnector,
  evidenceService   : NrsEvidenceService,
  journeyRepository : NrsJourneyRepository,
  appConfig         : AppConfig,
  demoPage          : NrsDemoPage,
  startPage         : NrsStartPage,
  resultPage        : NrsResultPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with Logging:

  private val declarationText: String =
    "I declare that the information I have given is correct and complete to the best of my knowledge."

  private val submitForm: Form[NrsSubmitForm] = Form(
    mapping(
      "fullName"           -> nonEmptyText,
      "regime"             -> nonEmptyText,
      "amountPence"        -> longNumber(min = 1),
      "declarationConsent" -> default(boolean, false),
      "simulateFailure"    -> text
    )(NrsSubmitForm.apply)(f =>
      Some((f.fullName, f.regime, f.amountPence, f.declarationConsent, f.simulateFailure))
    )
  )

  val demo: Action[AnyContent] = Action:
    implicit request =>
      Ok(demoPage())

  val start: Action[AnyContent] = authenticate:
    implicit request =>
      Ok(
        startPage(
          submitForm.fill(
            NrsSubmitForm(
              fullName           = "Alex Example",
              regime             = "Income Tax",
              amountPence        = 150000,
              declarationConsent = false,
              simulateFailure    = "none"
            )
          ),
          declarationText
        )
      )

  val submit: Action[AnyContent] = authenticate.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      submitForm.bindFromRequest().fold(
        formWithErrors =>
          Future.successful(BadRequest(startPage(formWithErrors, declarationText))),
        form =>
          if !form.declarationConsent then
            val withError = submitForm.fill(form).withError("declarationConsent", "nrs.start.declaration.error")
            Future.successful(BadRequest(startPage(withError, declarationText)))
          else
            submitEvidence(form, journeyId = UUID.randomUUID().toString, nrSubmissionId = UUID.randomUUID().toString)
      )

  def result(id: String): Action[AnyContent] = authenticate.async:
    implicit request =>
      journeyRepository.get(id).map:
        case None         => Ok(resultPage(None))
        case Some(journey) => Ok(resultPage(Some(journey)))

  def retry(id: String): Action[AnyContent] = authenticate.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      journeyRepository.get(id).flatMap:
        case None =>
          Future.successful(Redirect(routes.NrsController.start))
        case Some(journey) =>
          val form = NrsSubmitForm(
            fullName           = journey.fullName,
            regime             = journey.regime,
            amountPence        = journey.amountPence,
            declarationConsent = journey.declarationConsent,
            simulateFailure    = "none"
          )
          // Same nrSubmissionId on retry — demonstrates idempotent evidence keying.
          submitEvidence(form, journeyId = journey.id, nrSubmissionId = journey.nrSubmissionId)

  private def submitEvidence(
    form          : NrsSubmitForm,
    journeyId     : String,
    nrSubmissionId: String
  )(using HeaderCarrier, play.api.mvc.Request[?]): Future[Result] =
    val disclosureId = f"DDS-${100000 + scala.util.Random.nextInt(900000)}"
    val evidence = DisclosureEvidence(
      disclosureId       = disclosureId,
      fullName           = form.fullName,
      regime             = form.regime,
      amountPence        = form.amountPence,
      declarationText    = declarationText,
      declarationConsent = form.declarationConsent,
      journeyVersion     = appConfig.nrsJourneyVersion
    )

    val simulate = NrsSimulateFailure.fromForm(form.simulateFailure)
    val built0   = evidenceService.build(evidence, nrSubmissionId = nrSubmissionId)
    val built    =
      if simulate == NrsSimulateFailure.Checksum then evidenceService.withCorruptChecksum(built0)
      else built0

    val pending = NrsJourney(
      id                    = journeyId,
      disclosureId          = disclosureId,
      nrSubmissionId        = nrSubmissionId,
      payloadSha256Checksum = built.payloadSha256Checksum,
      fullName              = form.fullName,
      regime                = form.regime,
      amountPence           = form.amountPence,
      declarationConsent    = form.declarationConsent,
      state                 = NrsState.Submitting
    )

    for
      _      <- journeyRepository.upsert(pending)
      result <- nrsConnector.submit(built.request, simulate)
      updated = result match
        case NrsSubmissionResult.Accepted(id) =>
          pending.copy(state = NrsState.Submitted, nrSubmissionId = id, failureReason = None)
        case NrsSubmissionResult.Unavailable(msg) =>
          pending.copy(state = NrsState.Queued, failureReason = Some(msg))
        case NrsSubmissionResult.ChecksumFailed(msg) =>
          pending.copy(state = NrsState.Failed, failureReason = Some(msg))
        case NrsSubmissionResult.PermanentFailure(msg) =>
          pending.copy(state = NrsState.Failed, failureReason = Some(msg))
      _ <- journeyRepository.upsert(updated)
    yield
      logger.info(s"NRS PoC journeyId=$journeyId state=${updated.state} nrSubmissionId=${updated.nrSubmissionId}")
      Redirect(routes.NrsController.result(journeyId))

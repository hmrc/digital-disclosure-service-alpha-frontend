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
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.{ChargeNotificationConnector, EtmpChargeConnector, PaymentsConnector}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.AuthenticatedAction
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.PaymentJourneyRepository
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.util.UUID
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class PaymentStartForm(disclosureId: String, amountPence: Long)

@Singleton
class PaymentsController @Inject()(
  mcc                        : MessagesControllerComponents,
  authenticate               : AuthenticatedAction,
  paymentsConnector          : PaymentsConnector,
  etmpChargeConnector        : EtmpChargeConnector,
  chargeNotificationConnector: ChargeNotificationConnector,
  paymentJourneyRepository   : PaymentJourneyRepository,
  appConfig                  : AppConfig,
  startPage                  : PaymentsStartPage,
  returnPage                 : PaymentReturnPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport with Logging:

  private val basePath = "/digital-disclosure-service-alpha-frontend/payments"

  // Sample disclosure liability. In production this comes from the disclosure
  // the user has just submitted (tax owed plus interest and penalties).
  private def stubDisclosure(): StubDisclosure =
    StubDisclosure(
      id            = f"DDS-${100000 + scala.util.Random.nextInt(900000)}",
      taxOwedPence  = 120000,
      interestPence = 7500,
      penaltyPence  = 22500
    )

  private val paymentForm: Form[PaymentStartForm] = Form(
    mapping(
      "disclosureId" -> nonEmptyText,
      "amountPence"  -> longNumber(min = 1)
    )(PaymentStartForm.apply)(f => Some((f.disclosureId, f.amountPence)))
  )

  val start: Action[AnyContent] = authenticate:
    implicit request =>
      Ok(startPage(stubDisclosure()))

  val startPayment: Action[AnyContent] = authenticate.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      paymentForm.bindFromRequest().fold(
        _ =>
          Future.successful(Redirect(routes.PaymentsController.start)),
        form =>
          for
            // 1. Raise the charge on the corporate tier (stubbed ETMP) and take
            //    the charge reference it returns. This is the reference we carry
            //    through the payment and use to reconcile it later.
            chargeReference <- etmpChargeConnector.raiseCharge(form.disclosureId, form.amountPence)

            paymentId = UUID.randomUUID().toString
            returnUrl = s"${appConfig.ddsBaseUrl}$basePath/return/$paymentId"
            backUrl   = s"${appConfig.ddsBaseUrl}$basePath/start"

            // 2. Start the payment journey, supplying the charge reference, the
            //    amount and the return URL. The user types none of these on
            //    pay-frontend.
            spjRequest = SpjRequest(
                           chargeReference = chargeReference,
                           amountInPence   = form.amountPence,
                           returnUrl       = returnUrl,
                           backUrl         = backUrl
                         )
            response <- paymentsConnector.startJourney(spjRequest)

            // 3. Persist the journey so the outcome survives the round trip and
            //    the notification can be made idempotent.
            _ <- paymentJourneyRepository.upsert(
                   PaymentJourney(
                     id              = paymentId,
                     disclosureId    = form.disclosureId,
                     chargeReference = chargeReference,
                     amountInPence   = form.amountPence,
                     payApiJourneyId = response.journeyId,
                     state           = PaymentState.PendingPayment
                   )
                 )
          yield
            logger.info(s"Started payment journey paymentId=$paymentId payApiJourneyId=${response.journeyId}")
            Redirect(response.nextUrl)
      )

  def paymentReturn(paymentId: String): Action[AnyContent] = authenticate.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      paymentJourneyRepository.get(paymentId).flatMap:
        case None =>
          Future.successful(Ok(returnPage(None, None, None)))

        case Some(journey) =>
          paymentsConnector.journeyStatus(journey.payApiJourneyId).flatMap: maybeStatus =>
            val payApiStatus = maybeStatus.map(_.status)
            val newState = payApiStatus match
              case Some("Successful") => PaymentState.Paid
              case Some("Failed")     => PaymentState.Failed
              case Some("Cancelled")  => PaymentState.Cancelled
              case _                  => PaymentState.PendingPayment

            if newState == PaymentState.Paid && !journey.notified then
              // Confirmed paid and not yet reported: notify the corporate tier
              // exactly once, then record that we did so.
              val notification = ChargeRefNotification(
                taxType         = appConfig.paymentsChargeTaxType,
                chargeRefNumber = journey.chargeReference,
                amountPaid      = BigDecimal(journey.amountInPence) / 100
              )

              chargeNotificationConnector.notifyChargePaid(notification).flatMap: outcome =>
                paymentJourneyRepository
                  .upsert(journey.copy(state = PaymentState.Paid, notified = true))
                  .map(_ => Ok(returnPage(Some(journey.copy(state = PaymentState.Paid)), payApiStatus, Some(outcome))))
            else
              val updated = journey.copy(state = newState)
              val outcome =
                if newState == PaymentState.Paid && journey.notified then
                  Some("Already recorded — the charge-reference notification is only sent once.")
                else None

              paymentJourneyRepository
                .upsert(updated)
                .map(_ => Ok(returnPage(Some(updated), payApiStatus, outcome)))

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
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.{ChargeNotificationConnector, PaymentsConnector}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

case class PaymentFormData(amount: String)

@Singleton
class PaymentsController @Inject()(
  mcc                        : MessagesControllerComponents,
  paymentsConnector          : PaymentsConnector,
  chargeNotificationConnector: ChargeNotificationConnector,
  appConfig                  : AppConfig,
  startPage                  : PaymentsStartPage,
  returnPage                 : PaymentReturnPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with Logging:

  private val SessionKey   = "paymentJourneyId"
  private val AmountKey    = "paymentAmountPence"
  private val ChargeRefKey = "paymentChargeRef"

  // Stand-in for the charge reference associated with the ETMP charge in production,
  // used here as the correlation key. (The SDD does not pin down where that reference
  // is generated.) Ends in a non-digit so the payments-stubs DES stub returns a clean
  // 200 (the stub uses a trailing digit to simulate retry/error scenarios).
  private def demoChargeReference(): String =
    f"XDDS${scala.util.Random.nextInt(100000000)}%08dD"

  private val paymentForm: Form[PaymentFormData] = Form(
    mapping(
      "amount" -> nonEmptyText.verifying(
        "payments.start.amount.error.invalid",
        amount => Try(BigDecimal(amount.trim)).toOption.exists(_ > 0)
      )
    )(PaymentFormData.apply)(d => Some(d.amount))
  )

  val start: Action[AnyContent] = Action:
    implicit request =>
      Ok(startPage(paymentForm.fill(PaymentFormData("1500.00"))))

  val startPayment: Action[AnyContent] = Action.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      paymentForm.bindFromRequest().fold(
        formWithErrors =>
          Future.successful(BadRequest(startPage(formWithErrors))),
        formData =>
          val amountInPence = (BigDecimal(formData.amount.trim) * 100).toLong

          val returnUrl = s"${appConfig.ddsBaseUrl}/digital-disclosure-service-alpha-frontend/payments/return"
          val backUrl   = s"${appConfig.ddsBaseUrl}/digital-disclosure-service-alpha-frontend/payments/start"

          // No payment reference is sent: the PoC uses the generic PfOther origin, which collects
          // the reference (an XRef) from the user on pay-frontend. A production Dds origin would
          // instead carry the charge reference associated with the ETMP charge here (see SDD
          // "OPS - ENHANCE" / "ETMP Payments - ENHANCE"); its exact origin is not yet pinned down.
          val spjRequest = SpjRequest(
            amountInPence = amountInPence,
            returnUrl     = returnUrl,
            backUrl       = backUrl
          )

          // Generated up front so it is stable for the whole journey and acts as the
          // correlation key carried into the charge-reference notification on return.
          val chargeReference = demoChargeReference()

          paymentsConnector.startJourney(spjRequest).map: response =>
            logger.info(s"Started payment journey journeyId=${response.journeyId}, redirecting to pay-frontend")
            Redirect(response.nextUrl).addingToSession(
              SessionKey   -> response.journeyId,
              AmountKey    -> amountInPence.toString,
              ChargeRefKey -> chargeReference
            )
      )

  val paymentReturn: Action[AnyContent] = Action.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      request.session.get(SessionKey) match
        case Some(journeyId) =>
          val chargeReference = request.session.get(ChargeRefKey)

          paymentsConnector.journeyStatus(journeyId).flatMap: maybeStatus =>
            val status = maybeStatus.map(_.status)

            // Only notify the corporate tier on a confirmed successful payment
            // (the browser redirect alone is not proof of payment).
            if status.contains("Successful") then
              val amountPaid = request.session.get(AmountKey)
                .flatMap(p => Try(BigDecimal(p) / 100).toOption)
                .getOrElse(BigDecimal(0))

              val notification = ChargeRefNotification(
                taxType         = appConfig.paymentsChargeTaxType,
                chargeRefNumber = chargeReference.getOrElse("UNKNOWN"),
                amountPaid      = amountPaid
              )

              chargeNotificationConnector.notifyChargePaid(notification).map: outcome =>
                Ok(returnPage(Some(journeyId), status, chargeReference, Some(outcome)))
            else
              Future.successful(Ok(returnPage(Some(journeyId), status, chargeReference, None)))
        case None =>
          Future.successful(Ok(returnPage(None, None, None, None)))

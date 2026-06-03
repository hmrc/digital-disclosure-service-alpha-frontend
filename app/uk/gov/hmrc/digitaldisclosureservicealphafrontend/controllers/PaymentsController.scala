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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.PaymentsConnector
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
  mcc             : MessagesControllerComponents,
  paymentsConnector: PaymentsConnector,
  appConfig       : AppConfig,
  startPage       : PaymentsStartPage,
  returnPage      : PaymentReturnPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with Logging:

  private val SessionKey = "paymentJourneyId"

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

          val spjRequest = SpjRequest(
            amountInPence = amountInPence,
            returnUrl     = returnUrl,
            backUrl       = backUrl
          )

          paymentsConnector.startJourney(spjRequest).map: response =>
            logger.info(s"Started payment journey journeyId=${response.journeyId}, redirecting to pay-frontend")
            Redirect(response.nextUrl).addingToSession(SessionKey -> response.journeyId)
      )

  val paymentReturn: Action[AnyContent] = Action.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      request.session.get(SessionKey) match
        case Some(journeyId) =>
          paymentsConnector.journeyStatus(journeyId).map: maybeStatus =>
            Ok(returnPage(Some(journeyId), maybeStatus.map(_.status)))
        case None =>
          Future.successful(Ok(returnPage(None, None)))

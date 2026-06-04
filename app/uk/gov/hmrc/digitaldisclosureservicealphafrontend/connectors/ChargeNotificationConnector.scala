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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors

import play.api.Logging
import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

/** Reports a confirmed payment to the corporate tier.
  *
  * After DDS has confirmed (server-side) that a payment succeeded, it notifies
  * the corporate tier that the charge has been paid, so ETMP can record
  * settlement and a caseworker can see the disclosure is paid. Locally this is
  * pointed at the payments-stubs DES endpoint
  * (`POST /cross-regime/payments/card/notification`), which models the real DES
  * contract.
  *
  * In a production integration this same notification may instead be sent by OPS
  * itself once the payment succeeds, in which case DDS would not own this call at
  * all — the payload is identical, only the sender differs.
  *
  * This is best-effort: a failure to notify must never break the user's return
  * journey, so errors are logged and returned as a message rather than thrown.
  * Production would instead use a durable, idempotent, retried mechanism.
  */
@Singleton
class ChargeNotificationConnector @Inject()(
  httpClient    : HttpClientV2,
  servicesConfig: ServicesConfig
)(using ec: ExecutionContext) extends Logging:

  private val baseUrl: String = servicesConfig.baseUrl("charge-notification")

  private val notificationPath: String =
    servicesConfig.getString("payments.charge-notification-path")

  // Combined into one string then parsed, for the same reason as PaymentsConnector:
  // the url"..." interpolator cannot separate two adjacent interpolated values.
  private val notificationUrl: URI = URI.create(s"$baseUrl$notificationPath")

  def notifyChargePaid(notification: ChargeRefNotification)(using HeaderCarrier): Future[String] =
    httpClient
      .post(notificationUrl.toURL)
      .withBody(Json.toJson(notification))
      .execute[HttpResponse]
      .map { response =>
        logger.info(
          s"Charge-ref notification for ${notification.chargeRefNumber} returned ${response.status}"
        )
        s"Sent to $notificationUrl — response ${response.status}"
      }
      .recover {
        case NonFatal(e) =>
          logger.warn(s"Charge-ref notification failed: ${e.getMessage}")
          s"Could not reach the charge-notification endpoint (${e.getMessage}). Is payments-stubs running?"
      }

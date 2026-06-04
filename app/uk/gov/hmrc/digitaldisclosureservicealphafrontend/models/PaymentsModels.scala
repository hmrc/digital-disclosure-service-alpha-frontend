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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.models

import play.api.libs.json.*

/** Start Payment Journey (SPJ) request sent to pay-api.
  *
  * This matches the contract of a service-initiated OPS origin: the service
  * supplies the charge reference, the amount to pay (in pence) and the URLs
  * pay-frontend uses to send the user back to us. The user never types a
  * reference or an amount on pay-frontend.
  *
  * The PoC reuses an existing service origin to demonstrate this shape. A
  * production DDS integration would use a dedicated `Dds` origin (owned by the
  * OPS team) carrying the same fields.
  */
case class SpjRequest(
  chargeReference: String,
  amountInPence  : Long,
  returnUrl      : String,
  backUrl        : String
)

object SpjRequest:
  given OWrites[SpjRequest] = Json.writes[SpjRequest]

/** Response returned by every pay-api `journey/start` endpoint.
  *
  * `journeyId` identifies the journey in pay-api (used to confirm status later);
  * `nextUrl` is where we redirect the user's browser to continue on pay-frontend.
  */
case class SpjResponse(
  journeyId: String,
  nextUrl  : String
)

object SpjResponse:
  given Reads[SpjResponse] = Json.reads[SpjResponse]

/** Minimal view of a pay-api journey, used when confirming payment status on
  * return. Reads the journey `status` and the charge reference from the
  * origin-specific journey data, so a journey can be matched back to the charge
  * it was started for (the correlation key).
  */
case class PaymentJourneyStatus(
  status         : String,
  chargeReference: Option[String]
)

object PaymentJourneyStatus:
  import play.api.libs.functional.syntax.*
  given Reads[PaymentJourneyStatus] = (
    (__ \ "status").read[String] and
    (__ \ "journeySpecificData" \ "chargeReference").readNullable[String]
  )(PaymentJourneyStatus.apply)

/** Charge-reference notification — the message that makes the corporate tier
  * (DES / ETMP) aware a charge has been paid. Mirrors OPS's own
  * `ChargeRefNotificationDesRequest` { taxType, chargeRefNumber, amountPaid }.
  *
  * The same payload is used whether the notification is sent by OPS itself once
  * payment succeeds, or sent by this service after it confirms the journey status
  * (as this PoC does). The payload is identical; only the sender differs.
  */
case class ChargeRefNotification(
  taxType        : String,
  chargeRefNumber: String,
  amountPaid     : BigDecimal
)

object ChargeRefNotification:
  given OWrites[ChargeRefNotification] = Json.writes[ChargeRefNotification]

/** A stand-in for a submitted disclosure that has a liability to pay.
  *
  * In production the amount comes from the disclosure the user has just
  * completed (tax owed plus interest and penalties). Here it is generated so the
  * payment journey starts from a disclosure context rather than a free-text
  * amount box — the user confirms what they owe and pays, they do not type a
  * figure.
  */
case class StubDisclosure(
  id           : String,
  taxOwedPence : Long,
  interestPence: Long,
  penaltyPence : Long
):
  val totalPence: Long = taxOwedPence + interestPence + penaltyPence

  private def pounds(pence: Long): String = "\u00a3%,.2f".format(BigDecimal(pence) / 100)

  def taxFormatted     : String = pounds(taxOwedPence)
  def interestFormatted: String = pounds(interestPence)
  def penaltyFormatted : String = pounds(penaltyPence)
  def totalFormatted   : String = pounds(totalPence)

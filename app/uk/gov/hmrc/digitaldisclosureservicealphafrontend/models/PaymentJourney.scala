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
import uk.gov.hmrc.mongo.play.json.formats.MongoJavatimeFormats

import java.time.Instant

/** The lifecycle of a payment as DDS tracks it. The browser redirect back from
  * pay-frontend is not proof of payment, so the state is only advanced to `Paid`
  * once pay-api confirms the journey succeeded.
  */
enum PaymentState:
  case PendingPayment, Paid, Failed, Cancelled

object PaymentState:
  given Format[PaymentState] = new Format[PaymentState]:
    def reads(json: JsValue): JsResult[PaymentState] =
      json.validate[String].flatMap: s =>
        PaymentState.values
          .find(_.toString == s)
          .map(JsSuccess(_))
          .getOrElse(JsError(s"Unknown PaymentState: $s"))
    def writes(state: PaymentState): JsValue = JsString(state.toString)

/** Durable record of a payment journey, persisted in MongoDB.
  *
  * Persisting this (rather than relying on the browser session) means the
  * payment outcome survives the round trip to pay-frontend, lets the return
  * handler be keyed by a DDS-owned id, and makes the charge-reference
  * notification idempotent via the `notified` flag.
  */
case class PaymentJourney(
  id             : String,
  disclosureId   : String,
  chargeReference: String,
  amountInPence  : Long,
  payApiJourneyId: String,
  state          : PaymentState,
  notified       : Boolean         = false,
  createdAt      : Instant         = Instant.now()
)

object PaymentJourney:
  given instantFormat: Format[Instant]      = MongoJavatimeFormats.instantFormat
  given format: OFormat[PaymentJourney]      = Json.format[PaymentJourney]

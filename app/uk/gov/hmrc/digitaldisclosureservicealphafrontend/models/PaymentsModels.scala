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
  * This mirrors the common core of the OPS `SpjRequest` contract that every
  * service-initiated origin shares: the amount to pay (in pence) and the URLs
  * pay-frontend uses to send the user back to us. A production DDS integration
  * would use a dedicated `Dds` origin (owned by the OPS team) which may carry
  * additional disclosure reference data.
  */
case class SpjRequest(
  amountInPence: Long,
  returnUrl    : String,
  backUrl      : String
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
  * return via `GET /pay-api/journey/:journeyId`.
  */
case class PaymentJourneyStatus(
  status: String
)

object PaymentJourneyStatus:
  given Reads[PaymentJourneyStatus] = Json.reads[PaymentJourneyStatus]

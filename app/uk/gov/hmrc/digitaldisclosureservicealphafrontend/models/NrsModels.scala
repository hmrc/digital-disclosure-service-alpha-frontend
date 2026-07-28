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

import play.api.libs.json.{Json, OFormat}

/** Demo failure modes so the PoC can show checksum and unavailable paths
  * without a real NRS environment.
  */
enum NrsSimulateFailure:
  case None, Unavailable, Checksum

object NrsSimulateFailure:
  def fromForm(value: String): NrsSimulateFailure =
    value.toLowerCase match
      case "unavailable" => NrsSimulateFailure.Unavailable
      case "checksum"    => NrsSimulateFailure.Checksum
      case _             => NrsSimulateFailure.None

/** Structured disclosure snapshot that becomes the NRS payload (JSON → UTF-8 →
  * SHA-256 → base64). In production this is the full disclosure artefact.
  */
case class DisclosureEvidence(
  disclosureId      : String,
  fullName          : String,
  regime            : String,
  amountPence       : Long,
  declarationText   : String,
  declarationConsent: Boolean,
  journeyVersion    : String
)

object DisclosureEvidence:
  given format: OFormat[DisclosureEvidence] = Json.format[DisclosureEvidence]

case class NrsIdentityData(
  internalId     : String,
  confidenceLevel: Int,
  affinityGroup  : String
)

object NrsIdentityData:
  given format: OFormat[NrsIdentityData] = Json.format[NrsIdentityData]

case class NrsDeclaration(
  declarationText  : String,
  declarationName  : String,
  declarationConsent: Boolean
)

object NrsDeclaration:
  given format: OFormat[NrsDeclaration] = Json.format[NrsDeclaration]

case class NrsReceiptData(
  language   : String,
  declaration: NrsDeclaration
)

object NrsReceiptData:
  given format: OFormat[NrsReceiptData] = Json.format[NrsReceiptData]

case class NrsMetadata(
  businessId             : String,
  notableEvent           : String,
  payloadContentType     : String,
  payloadSha256Checksum  : String,
  nrSubmissionId         : String,
  userSubmissionTimestamp: String,
  identityData           : NrsIdentityData,
  searchKeys             : Map[String, String],
  receiptData            : NrsReceiptData
)

object NrsMetadata:
  given format: OFormat[NrsMetadata] = Json.format[NrsMetadata]

/** Request body matching the platform NRS submission contract used by other
  * HMRC services (e.g. VAT returns).
  */
case class NrsSubmissionRequest(
  payload : String,
  metadata: NrsMetadata
)

object NrsSubmissionRequest:
  given format: OFormat[NrsSubmissionRequest] = Json.format[NrsSubmissionRequest]

case class NrsSuccessResponse(nrSubmissionId: String)

object NrsSuccessResponse:
  given format: OFormat[NrsSuccessResponse] = Json.format[NrsSuccessResponse]

sealed trait NrsSubmissionResult

object NrsSubmissionResult:
  case class Accepted(nrSubmissionId: String) extends NrsSubmissionResult
  case class ChecksumFailed(message: String)  extends NrsSubmissionResult
  case class Unavailable(message: String)     extends NrsSubmissionResult
  case class PermanentFailure(message: String) extends NrsSubmissionResult

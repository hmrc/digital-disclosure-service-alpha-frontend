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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.services

import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.{Base64, UUID}
import javax.inject.{Inject, Singleton}

case class BuiltNrsEvidence(
  request              : NrsSubmissionRequest,
  payloadJson          : String,
  payloadSha256Checksum: String,
  nrSubmissionId       : String
)

/** Builds a DDS-shaped NRS evidence request: JSON payload → SHA-256 → base64,
  * plus metadata (businessId, notableEvent, identity, searchKeys, receipt).
  *
  * Placeholders (`businessId`, `notableEvent`) must be confirmed with the NRS
  * team before a production write.
  */
@Singleton
class NrsEvidenceService @Inject()(appConfig: AppConfig):

  def build(
    evidence     : DisclosureEvidence,
    nrSubmissionId: String = UUID.randomUUID().toString,
    submittedAt  : Instant = Instant.now()
  ): BuiltNrsEvidence =
    val payloadJson = Json.prettyPrint(Json.toJson(evidence))
    val payloadBytes = payloadJson.getBytes(StandardCharsets.UTF_8)
    val checksum     = sha256Hex(payloadBytes)
    val payloadB64   = Base64.getEncoder.encodeToString(payloadBytes)

    val metadata = NrsMetadata(
      businessId              = appConfig.nrsBusinessId,
      notableEvent            = appConfig.nrsNotableEvent,
      payloadContentType      = "application/json",
      payloadSha256Checksum   = checksum,
      nrSubmissionId          = nrSubmissionId,
      userSubmissionTimestamp = submittedAt.toString,
      identityData = NrsIdentityData(
        internalId      = "poc-internal-id",
        confidenceLevel = 250,
        affinityGroup   = "Individual"
      ),
      searchKeys = Map(
        "disclosureId" -> evidence.disclosureId,
        "regime"       -> evidence.regime
      ),
      receiptData = NrsReceiptData(
        language = "en",
        declaration = NrsDeclaration(
          declarationText    = evidence.declarationText,
          declarationName    = evidence.fullName,
          declarationConsent = evidence.declarationConsent
        )
      )
    )

    BuiltNrsEvidence(
      request               = NrsSubmissionRequest(payload = payloadB64, metadata = metadata),
      payloadJson           = payloadJson,
      payloadSha256Checksum = checksum,
      nrSubmissionId        = nrSubmissionId
    )

  /** Deliberately corrupts the checksum so the stub can return 419. */
  def withCorruptChecksum(built: BuiltNrsEvidence): BuiltNrsEvidence =
    val corrupted = "0" * 64
    built.copy(
      payloadSha256Checksum = corrupted,
      request = built.request.copy(
        metadata = built.request.metadata.copy(payloadSha256Checksum = corrupted)
      )
    )

  def sha256Hex(bytes: Array[Byte]): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(bytes)
      .map("%02x".format(_))
      .mkString

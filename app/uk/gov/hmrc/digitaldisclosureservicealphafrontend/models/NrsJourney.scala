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

/** Lifecycle of an NRS evidencing attempt as DDS tracks it.
  *
  * Production would advance to `Submitted` only once NRS accepts the evidence
  * write (or a durable queue owns retry). `Queued` stands in for "NRS down —
  * submission recorded locally pending retry".
  */
enum NrsState:
  case Submitting, Submitted, Queued, Failed

object NrsState:
  given Format[NrsState] = new Format[NrsState]:
    def reads(json: JsValue): JsResult[NrsState] =
      json.validate[String].flatMap: s =>
        NrsState.values
          .find(_.toString == s)
          .map(JsSuccess(_))
          .getOrElse(JsError(s"Unknown NrsState: $s"))
    def writes(state: NrsState): JsValue = JsString(state.toString)

/** Durable record of an NRS submission attempt, persisted in MongoDB. */
case class NrsJourney(
  id                   : String,
  disclosureId         : String,
  nrSubmissionId       : String,
  payloadSha256Checksum: String,
  fullName             : String,
  regime               : String,
  amountPence          : Long,
  declarationConsent   : Boolean,
  state                : NrsState,
  failureReason        : Option[String] = None,
  createdAt            : Instant        = Instant.now()
)

object NrsJourney:
  given instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
  given format: OFormat[NrsJourney]    = Json.format[NrsJourney]

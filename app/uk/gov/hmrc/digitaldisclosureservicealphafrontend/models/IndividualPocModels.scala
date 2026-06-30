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
import uk.gov.hmrc.auth.core.{AffinityGroup, ConfidenceLevel, Enrolment}

case class StubVerifier(key: String, value: String)

object StubVerifier:
  given format: OFormat[StubVerifier] = Json.format[StubVerifier]

case class StubKnownFactsRequest(
  enrolmentKey: String,
  verifiers   : Seq[StubVerifier]
)

object StubKnownFactsRequest:
  given format: OFormat[StubKnownFactsRequest] = Json.format[StubKnownFactsRequest]

case class IndividualAuthContext(
  nino            : String,
  maskedNino      : String,
  confidenceLevel : Int,
  affinityGroup   : Option[AffinityGroup],
  enrolments      : Seq[EnrolmentSummary],
  hasStandInEnrolment: Boolean
)

case class EnrolmentSummary(
  key        : String,
  identifiers: Seq[(String, String)],
  state      : String
)

object IndividualAuthContext:

  def maskNino(nino: String): String =
    if nino.length >= 3 then s"${nino.take(2)}******${nino.takeRight(1)}"
    else "********"

  def from(
    nino            : String,
    confidenceLevel : ConfidenceLevel,
    affinityGroup   : Option[AffinityGroup],
    enrolments      : Set[Enrolment],
    standInService  : String
  ): IndividualAuthContext =
    IndividualAuthContext(
      nino               = nino,
      maskedNino         = maskNino(nino),
      confidenceLevel    = confidenceLevel.level,
      affinityGroup      = affinityGroup,
      enrolments         = enrolments.toSeq.sortBy(_.key).map { e =>
        EnrolmentSummary(
          key         = e.key,
          identifiers = e.identifiers.map(i => i.key -> i.value),
          state       = e.state
        )
      },
      hasStandInEnrolment = enrolments.exists(_.key == standInService)
    )

enum IvJourneyResult:
  case Success, Incomplete, FailedMatching, InsufficientEvidence, LockedOut,
    UserAborted, Timeout, TechnicalIssue, PreconditionFailed, Unknown

object IvJourneyResult:
  def fromString(value: String): IvJourneyResult = value match
    case "Success"              => Success
    case "Incomplete"           => Incomplete
    case "FailedMatching"       => FailedMatching
    case "InsufficientEvidence" => InsufficientEvidence
    case "LockedOut"            => LockedOut
    case "UserAborted"          => UserAborted
    case "Timeout"              => Timeout
    case "TechnicalIssue"       => TechnicalIssue
    case "PreconditionFailed"   => PreconditionFailed
    case _                      => Unknown

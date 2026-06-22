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

enum AgentPocJourneyOption:
  case Option1, Option2, DigitallyExcluded

object AgentPocJourneyOption:
  def fromString(value: String): Option[AgentPocJourneyOption] = value.toLowerCase match
    case "option1" | "option-1"            => Some(Option1)
    case "option2" | "option-2"            => Some(Option2)
    case "digitally-excluded" | "excluded" => Some(DigitallyExcluded)
    case _                                 => None

enum AgentInvitationStatus:
  case Pending, Accepted, Rejected, Claimed

object AgentInvitationStatus:
  def fromString(value: String): AgentInvitationStatus = value.toLowerCase match
    case "accepted" => Accepted
    case "rejected" => Rejected
    case "claimed"  => Claimed
    case _          => Pending

case class AgentInvitation(
  id           : String,
  token        : String,
  arn          : String,
  clientId     : String,
  clientIdType : String,
  verifier     : String,
  journeyOption: String,
  status       : String,
  createdAt    : Instant
)

object AgentInvitation:
  given instantFormat: Format[Instant] = MongoJavatimeFormats.instantFormat
  given format: OFormat[AgentInvitation] = Json.format[AgentInvitation]

case class AgentPocOptionCard(
  id          : String,
  title       : String,
  description : String,
  pros        : Seq[String],
  cons        : Seq[String],
  agentPath   : String,
  clientPath  : Option[String]
)

case class RelationshipGateResult(
  authorised : Boolean,
  mechanism  : String,
  detail     : String
)

case class InviteClientForm(
  clientId    : String,
  clientIdType: String,
  verifier    : String
)

case class ClientRegistrationForm(
  mtdItId: String,
  nino   : String
)

case class StubRelationshipRecord(
  regime       : String,
  agentARN     : String,
  clientId     : String,
  clientIdType : String,
  relationship : String = "Authorised"
)

object StubRelationshipRecord:
  given OFormat[StubRelationshipRecord] = Json.format

case class StubKnownFactsRequest(
  enrolmentKey: String,
  verifiers   : Seq[StubVerifier]
)

case class StubVerifier(
  key  : String,
  value: String
)

object StubKnownFactsRequest:
  given OFormat[StubKnownFactsRequest] = Json.format

object StubVerifier:
  given OFormat[StubVerifier] = Json.format

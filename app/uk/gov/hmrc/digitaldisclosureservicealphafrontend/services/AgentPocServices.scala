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

import play.api.Logging
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class AgentRelationshipGateService @Inject()(
  appConfig: AppConfig,
  acr      : AgentClientRelationshipsConnector,
  aac      : AgentAccessControlConnector
)(using ec: ExecutionContext) extends Logging:

  def checkRelationship(
    arn          : String,
    clientId     : String,
    clientIdType : String
  )(using HeaderCarrier): Future[RelationshipGateResult] =
    val service   = appConfig.agentPocStandInService
    val authRule  = appConfig.agentPocStandInAuthRule
    val mechanism = appConfig.agentPocGateMechanism

    mechanism.toLowerCase match
      case "aac" =>
        aac.delegatedAuthAllowed(authRule, arn, clientId).map { allowed =>
          RelationshipGateResult(
            authorised = allowed,
            mechanism  = s"Agent Access Control ($authRule)",
            detail     = if allowed then "AAC returned 200" else "AAC returned NO_RELATIONSHIP"
          )
        }
      case _ =>
        acr.relationshipExists(arn, service, clientIdType, clientId).map { allowed =>
          RelationshipGateResult(
            authorised = allowed,
            mechanism  = s"Agent Client Relationships ($service)",
            detail     = if allowed then "ACR relationship found" else "ACR returned 404"
          )
        }

trait RelationshipWriter:
  def writeRelationship(invitation: AgentInvitation)(using HeaderCarrier): Future[Seq[String]]

@Singleton
class UnclaimedEnrolmentWriter @Inject()(
  appConfig: AppConfig,
  stubs    : AgentsExternalStubsConnector,
  acr      : AgentClientRelationshipsConnector
)(using ec: ExecutionContext) extends RelationshipWriter:

  override def writeRelationship(invitation: AgentInvitation)(using HeaderCarrier): Future[Seq[String]] =
    val service      = appConfig.agentPocStandInService
    val regime       = appConfig.agentPocStandInRegime
    val enrolmentKey = s"$service~${invitation.clientIdType}~${invitation.clientId}"

    val knownFacts = Json.toJson(
      StubKnownFactsRequest(
        enrolmentKey = enrolmentKey,
        verifiers    = Seq(StubVerifier("NINO", invitation.verifier))
      )
    )

    val relationship = Json.toJson(
      StubRelationshipRecord(
        regime       = regime,
        agentARN     = invitation.arn,
        clientId     = invitation.clientId,
        clientIdType = invitation.clientIdType
      )
    )

    for
      kfOk <- stubs.createKnownFacts(knownFacts)
      relOk <- stubs.storeRelationship(relationship)
      acrOk <- acr.createTestRelationship(
                 invitation.arn,
                 service,
                 invitation.clientIdType,
                 invitation.clientId
               )
    yield Seq(
      s"Unclaimed principal enrolment known facts created: $kfOk (enrolment key $enrolmentKey)",
      s"ETMP relationship record stored: $relOk (regime $regime)",
      s"Delegated enrolment via ACR test-only: $acrOk",
      "Production: client accept claims the Unclaimed enrolment and allocates delegated enrolment to the agent"
    )

@Singleton
class StandardHandshakeWriter @Inject()(
  appConfig: AppConfig,
  acr      : AgentClientRelationshipsConnector,
  stubs    : AgentsExternalStubsConnector
)(using ec: ExecutionContext) extends RelationshipWriter:

  override def writeRelationship(invitation: AgentInvitation)(using HeaderCarrier): Future[Seq[String]] =
    val service  = appConfig.agentPocStandInService
    val regime   = appConfig.agentPocStandInRegime
    val relationship = Json.toJson(
      StubRelationshipRecord(
        regime       = regime,
        agentARN     = invitation.arn,
        clientId     = invitation.clientId,
        clientIdType = invitation.clientIdType
      )
    )

    for
      relOk <- stubs.storeRelationship(relationship)
      acrOk <- acr.createTestRelationship(
                 invitation.arn,
                 service,
                 invitation.clientIdType,
                 invitation.clientId
               )
    yield Seq(
      s"Standard handshake relationship stored: $relOk",
      s"ACR test-only relationship: $acrOk",
      "Production: ASA allocates delegated enrolment and writes ETMP relationship on accept"
    )

@Singleton
class DigitallyExcludedWriter @Inject()(
  standard: StandardHandshakeWriter
)(using ec: ExecutionContext) extends RelationshipWriter:

  override def writeRelationship(invitation: AgentInvitation)(using HeaderCarrier): Future[Seq[String]] =
    standard.writeRelationship(invitation).map { steps =>
      steps :+ "Digitally excluded: agent help desk accepted on the client's behalf (Stride in production)"
    }

@Singleton
class RelationshipWriterSelector @Inject()(
  unclaimed: UnclaimedEnrolmentWriter,
  standard : StandardHandshakeWriter,
  excluded : DigitallyExcludedWriter
):
  def forJourney(journey: AgentPocJourneyOption): RelationshipWriter = journey match
    case AgentPocJourneyOption.Option1            => unclaimed
    case AgentPocJourneyOption.Option2            => standard
    case AgentPocJourneyOption.DigitallyExcluded    => excluded

@Singleton
class AgentPocOptionCatalog @Inject()():

  def allOptions: Seq[AgentPocOptionCard] = Seq(
    AgentPocOptionCard(
      id          = "option-1",
      title       = "Option 1: Unclaimed enrolment",
      description = "Agent creates the invitation. ASA creates an Unclaimed principal enrolment. The client accepts and claims via the invitation link; only then is the delegated enrolment allocated and the relationship written.",
      pros        = Seq(
        "Client does not need a DDS registration journey before the agent can invite",
        "Uses existing Unclaimed enrolment pattern",
        "Durable record in EACD/ETMP from accept"
      ),
      cons        = Seq(
        "Agent must wait for the client to accept and claim before disclosing",
        "Identifier and known-facts match must be strong at invitation time"
      ),
      agentPath   = "/agent-poc/option-1",
      clientPath  = None
    ),
    AgentPocOptionCard(
      id          = "option-2",
      title       = "Option 2: Client registers for DDS first",
      description = "The client registers and owns their principal enrolment, then accepts the agent's invitation through the standard handshake.",
      pros        = Seq(
        "Client owns principal enrolment from the start",
        "Standard durable path used by most MTD services"
      ),
      cons        = Seq(
        "Adds a registration step before any agent can act",
        "More friction for a one-time service"
      ),
      agentPath   = "/agent-poc/option-2",
      clientPath  = Some("/agent-poc/option-2/register")
    ),
    AgentPocOptionCard(
      id          = "digitally-excluded",
      title       = "Digitally excluded handshake",
      description = "Same handshake for both options, but the agent help desk accepts on the client's behalf instead of the client signing in.",
      pros        = Seq(
        "Covers clients who cannot engage digitally",
        "Same handshake regardless of option"
      ),
      cons        = Seq(
        "Client must still be matchable on a HOD",
        "Help desk acceptance is a privileged operation"
      ),
      agentPath   = "/agent-poc/digitally-excluded",
      clientPath  = None
    )
  )

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

import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.NrsEvidenceService
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

class InProcessNrsConnectorSpec extends AnyWordSpec with Matchers with ScalaFutures:

  private val config = Configuration(
    "appName"                                              -> "digital-disclosure-service-alpha-frontend",
    "auth.sign-in-url"                                     -> "http://localhost:9949/auth-login-stub/gg-sign-in",
    "upscan.max-file-size"                                 -> 10485760L,
    "nrs.business-id"                                      -> "dds",
    "nrs.notable-event"                                    -> "dds-disclosure-submission",
    "nrs.journey-version"                                  -> "alpha-poc-1",
    "microservice.services.dds-frontend.host"              -> "localhost",
    "microservice.services.dds-frontend.port"              -> 9000,
    "microservice.services.dds-frontend.protocol"          -> "http"
  )

  private val appConfig = new AppConfig(config, new ServicesConfig(config))
  private val evidenceService = new NrsEvidenceService(appConfig)
  private val connector = new InProcessNrsConnector(evidenceService)
  private given HeaderCarrier = HeaderCarrier()

  private val evidence = DisclosureEvidence(
    disclosureId       = "DDS-999999",
    fullName           = "Alex Example",
    regime             = "Income Tax",
    amountPence        = 100,
    declarationText    = "I declare...",
    declarationConsent = true,
    journeyVersion     = "alpha-poc-1"
  )

  "InProcessNrsConnector" should:
    "accept a valid evidence request" in:
      val built = evidenceService.build(evidence, nrSubmissionId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
      connector.submit(built.request).futureValue shouldBe
        NrsSubmissionResult.Accepted("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")

    "return the same acceptance on idempotent replay" in:
      val built = evidenceService.build(evidence, nrSubmissionId = "bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")
      connector.submit(built.request).futureValue shouldBe
        NrsSubmissionResult.Accepted("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")
      connector.submit(built.request).futureValue shouldBe
        NrsSubmissionResult.Accepted("bbbbbbbb-bbbb-cccc-dddd-eeeeeeeeeeee")

    "fail checksum validation when the hash does not match the payload" in:
      val built = evidenceService.withCorruptChecksum(evidenceService.build(evidence))
      connector.submit(built.request).futureValue match
        case NrsSubmissionResult.ChecksumFailed(_) => succeed
        case other => fail(s"Expected ChecksumFailed, got $other")

    "simulate unavailable" in:
      val built = evidenceService.build(evidence)
      connector.submit(built.request, NrsSimulateFailure.Unavailable).futureValue match
        case NrsSubmissionResult.Unavailable(_) => succeed
        case other => fail(s"Expected Unavailable, got $other")

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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.Configuration
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.DisclosureEvidence
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.nio.charset.StandardCharsets
import java.util.Base64

class NrsEvidenceServiceSpec extends AnyWordSpec with Matchers:

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
  private val service   = new NrsEvidenceService(appConfig)

  private val evidence = DisclosureEvidence(
    disclosureId       = "DDS-123456",
    fullName           = "Alex Example",
    regime             = "Income Tax",
    amountPence        = 150000,
    declarationText    = "I declare...",
    declarationConsent = true,
    journeyVersion     = "alpha-poc-1"
  )

  "NrsEvidenceService.build" should:
    "base64-encode the JSON payload and set a matching SHA-256 checksum" in:
      val built   = service.build(evidence, nrSubmissionId = "11111111-1111-1111-1111-111111111111")
      val decoded = new String(Base64.getDecoder.decode(built.request.payload), StandardCharsets.UTF_8)

      decoded shouldBe built.payloadJson
      built.payloadSha256Checksum shouldBe service.sha256Hex(decoded.getBytes(StandardCharsets.UTF_8))
      built.request.metadata.payloadSha256Checksum shouldBe built.payloadSha256Checksum
      built.request.metadata.businessId shouldBe "dds"
      built.request.metadata.notableEvent shouldBe "dds-disclosure-submission"
      built.request.metadata.nrSubmissionId shouldBe "11111111-1111-1111-1111-111111111111"
      built.request.metadata.searchKeys("disclosureId") shouldBe "DDS-123456"

    "corrupt the checksum when withCorruptChecksum is used" in:
      val built    = service.build(evidence)
      val corrupted = service.withCorruptChecksum(built)

      corrupted.payloadSha256Checksum shouldBe "0" * 64
      corrupted.request.metadata.payloadSha256Checksum shouldBe "0" * 64
      corrupted.request.payload shouldBe built.request.payload

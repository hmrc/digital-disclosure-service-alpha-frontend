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

import play.api.Logging
import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.services.NrsEvidenceService
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** Boundary to the Non-Repudiation Store. Local PoC defaults to an in-process
  * stub that models the platform contract (202 / 419 / 5xx). Flip
  * `nrs.use-in-process-stub` to false to hit a real or WireMock host.
  */
trait NrsConnector:
  def submit(
    request : NrsSubmissionRequest,
    simulate: NrsSimulateFailure = NrsSimulateFailure.None
  )(using HeaderCarrier): Future[NrsSubmissionResult]

@Singleton
class InProcessNrsConnector @Inject()(
  evidenceService: NrsEvidenceService
) extends NrsConnector with Logging:

  // Idempotency: same nrSubmissionId returns the first accepted outcome.
  private val acceptedIds = new ConcurrentHashMap[String, String]()

  def submit(
    request : NrsSubmissionRequest,
    simulate: NrsSimulateFailure = NrsSimulateFailure.None
  )(using HeaderCarrier): Future[NrsSubmissionResult] =
    Future.successful:
      simulate match
        case NrsSimulateFailure.Unavailable =>
          logger.warn(s"[STUB NRS] Simulated unavailable for ${request.metadata.nrSubmissionId}")
          NrsSubmissionResult.Unavailable("NRS stub simulated service unavailable (503)")

        case NrsSimulateFailure.Checksum =>
          logger.warn(s"[STUB NRS] Simulated checksum failure for ${request.metadata.nrSubmissionId}")
          NrsSubmissionResult.ChecksumFailed("NRS stub simulated checksum failure (419)")

        case NrsSimulateFailure.None =>
          Option(acceptedIds.get(request.metadata.nrSubmissionId)) match
            case Some(existing) =>
              logger.info(s"[STUB NRS] Idempotent replay for nrSubmissionId=$existing")
              NrsSubmissionResult.Accepted(existing)
            case None =>
              validateChecksum(request) match
                case Left(msg) =>
                  logger.warn(s"[STUB NRS] Checksum failed: $msg")
                  NrsSubmissionResult.ChecksumFailed(msg)
                case Right(_) =>
                  val id = request.metadata.nrSubmissionId
                  acceptedIds.put(id, id)
                  logger.info(s"[STUB NRS] Accepted nrSubmissionId=$id")
                  NrsSubmissionResult.Accepted(id)

  private def validateChecksum(request: NrsSubmissionRequest): Either[String, Unit] =
    try
      val decoded = Base64.getDecoder.decode(request.payload)
      val actual  = evidenceService.sha256Hex(decoded)
      if actual == request.metadata.payloadSha256Checksum then Right(())
      else
        Left(
          s"Checksum Failed: metadata.payloadSha256Checksum does not match SHA-256 of decoded payload " +
            s"(expected ${request.metadata.payloadSha256Checksum}, actual $actual)"
        )
    catch
      case _: IllegalArgumentException =>
        Left("Checksum Failed: payload is not valid base64")

@Singleton
class HttpNrsConnector @Inject()(
  httpClient    : HttpClientV2,
  servicesConfig: ServicesConfig
)(using ec: ExecutionContext) extends NrsConnector with Logging:

  private val baseUrl: String = servicesConfig.baseUrl("non-repudiation")
  private val submissionPath: String =
    servicesConfig.getString("nrs.submission-path")
  private val submissionUrl: URI = URI.create(s"$baseUrl$submissionPath")

  def submit(
    request : NrsSubmissionRequest,
    simulate: NrsSimulateFailure = NrsSimulateFailure.None
  )(using HeaderCarrier): Future[NrsSubmissionResult] =
    // Force-failure modes are for the in-process stub; over HTTP we only send
    // the platform body. Callers should corrupt the checksum in the request
    // itself when testing 419 against a real stub.
    val _ = simulate
    httpClient
      .post(submissionUrl.toURL)
      .withBody(Json.toJson(request))
      .execute[HttpResponse]
      .map: response =>
        response.status match
          case 202 | 200 =>
            response.json.asOpt[NrsSuccessResponse]
              .map(r => NrsSubmissionResult.Accepted(r.nrSubmissionId))
              .getOrElse(NrsSubmissionResult.Accepted(request.metadata.nrSubmissionId))
          case 419 =>
            NrsSubmissionResult.ChecksumFailed(response.body)
          case status if status >= 500 =>
            NrsSubmissionResult.Unavailable(s"NRS returned $status")
          case status =>
            NrsSubmissionResult.PermanentFailure(s"NRS returned $status: ${response.body}")

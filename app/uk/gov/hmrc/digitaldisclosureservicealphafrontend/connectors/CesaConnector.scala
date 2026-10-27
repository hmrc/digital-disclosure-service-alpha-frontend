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
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import java.net.{URI, URLEncoder}
import java.nio.charset.StandardCharsets
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

/** One GET made to CESA (locally, cesa-stub), kept raw so the PoC page can show exactly what came back. */
final case class CesaCall(
  label : String,
  url   : String,
  status: Option[Int],
  body  : String,
  error : Option[String] = None
):
  lazy val json: Option[JsValue] = Try(Json.parse(body)).toOption
  def prettyBody: String         = json.map(Json.prettyPrint).getOrElse(body)
  def isSuccess: Boolean         = status.exists(s => s >= 200 && s < 300)

/** GETs Self Assessment data from CESA. Locally this points at cesa-stub (`sbt "run 8084"`).
  *
  * `cesa.path-prefix` lets the same connector call the ODS routes (`/ods-sa/v1`) the stub also serves.
  * Responses are returned raw (status + body): this PoC is about proving the integration, not modelling
  * every CESA payload.
  */
@Singleton
class CesaConnector @Inject() (
  http          : HttpClientV2,
  servicesConfig: ServicesConfig,
  appConfig     : AppConfig
)(using ec: ExecutionContext)
    extends Logging:

  val baseUrl: String = servicesConfig.baseUrl("cesa")

  private val prefix: String = appConfig.cesaPathPrefix

  private def individualPath(utr: String): String =
    s"$prefix/self-assessment/individual/${URLEncoder.encode(utr, StandardCharsets.UTF_8)}"

  /** Root resource: a `links` object pointing at account summary and designatory details. */
  def individual(utr: String)(using HeaderCarrier): Future[CesaCall] =
    get("Individual (links)", individualPath(utr))

  def calendar(utr: String)(using HeaderCarrier): Future[CesaCall] =
    get("Return calendar", s"${individualPath(utr)}/calendar")

  def futureLiabilities(utr: String)(using HeaderCarrier): Future[CesaCall] =
    get("Future liabilities", s"${individualPath(utr)}/account/futureliabilities")

  /** Follow a `links` href returned by CESA. Only relative paths are followed, always against [[baseUrl]]. */
  def followLink(label: String, href: String)(using HeaderCarrier): Future[CesaCall] =
    if href.startsWith("/") && !href.startsWith("//") then get(label, href)
    else Future.successful(CesaCall(label, href, None, "", Some(s"Not following non-relative link '$href'")))

  private def get(label: String, path: String)(using HeaderCarrier): Future[CesaCall] =
    val fullUrl = s"$baseUrl$path"
    http
      .get(URI.create(fullUrl).toURL)
      .execute[HttpResponse]
      .map: response =>
        if response.status >= 400 then
          logger.info(s"[CESA GET] ${response.status} from $fullUrl")
        CesaCall(label, fullUrl, Some(response.status), response.body)
      .recover:
        case ex =>
          logger.warn(s"[CESA GET] Failed $fullUrl", ex)
          CesaCall(
            label,
            fullUrl,
            None,
            "",
            Some(s"Could not reach CESA at $baseUrl (${ex.getMessage}). Is cesa-stub running? sbt \"run 8084\"")
          )

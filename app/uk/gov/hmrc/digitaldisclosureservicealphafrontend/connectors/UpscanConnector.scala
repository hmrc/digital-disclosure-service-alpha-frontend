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

import play.api.libs.json.Json
import play.api.libs.ws.WSClient
import play.api.libs.ws.writeableOf_JsValue
import play.api.mvc.MultipartFormData.{DataPart, FilePart}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class UpscanConnector @Inject()(
  httpClient    : HttpClientV2,
  wsClient      : WSClient,
  servicesConfig: ServicesConfig
)(using ec: ExecutionContext):

  private val baseUrl: String = servicesConfig.baseUrl("upscan-initiate")

  def initiate(request: UpscanInitiateRequest)(using HeaderCarrier): Future[UpscanInitiateResponse] =
    httpClient
      .post(url"$baseUrl/upscan/v2/initiate")
      .withBody(Json.toJson(request))
      .execute[HttpResponse]
      .map(_.json.as[UpscanInitiateResponse])

  def uploadFile(
    uploadUrl: String,
    fields   : Map[String, String],
    fileName : String,
    mimeType : String,
    content  : Array[Byte]
  ): Future[Int] =
    val dataParts = fields.map((k, v) => DataPart(k, v)).toList
    val filePart  = FilePart("file", fileName, Some(mimeType), Source.single(ByteString(content)))
    val allParts  = Source(dataParts :+ filePart)

    wsClient
      .url(uploadUrl)
      .post(allParts)
      .map(_.status)

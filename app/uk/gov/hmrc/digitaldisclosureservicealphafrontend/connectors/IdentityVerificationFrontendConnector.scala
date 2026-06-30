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

import play.api.libs.json.JsValue
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.IvJourneyResult
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.http.HttpReads.Implicits.*

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class IdentityVerificationFrontendConnector @Inject()(
  httpClient: HttpClientV2,
  appConfig : AppConfig
)(using ec: ExecutionContext):

  def getJourneyStatus(journeyId: String)(using HeaderCarrier): Future[Option[IvJourneyResult]] =
    httpClient
      .get(url"${appConfig.ivJourneyStatusUrl}/$journeyId")
      .execute[HttpResponse]
      .map(parseJourneyResult)
      .recover { case _ => None }

  private def parseJourneyResult(response: HttpResponse): Option[IvJourneyResult] =
    if response.status == 200 then
      val json: JsValue = response.json
      val status        =
        (json \ "journeyResult").asOpt[String]
          .orElse((json \ "result").asOpt[String])
      status.map(IvJourneyResult.fromString)
    else None

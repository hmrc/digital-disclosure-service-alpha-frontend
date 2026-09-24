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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ExistingCalculation
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse, StringContextOps}
import uk.gov.hmrc.http.HttpReads.Implicits.readRaw
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.Using

sealed trait CalcRetrieveError:
  def message: String

object CalcRetrieveError:
  case class UnsupportedYear(taxYear: String) extends CalcRetrieveError:
    val message = s"MTD retrieve is not available for $taxYear (minimum 2017-18)."

  case class NotFound(taxYear: String) extends CalcRetrieveError:
    val message = s"No stored calculation for $taxYear."

  case class Failed(detail: String) extends CalcRetrieveError:
    val message = detail

/** GET an existing ITSA calculation. Do not POST trigger or crystallise. */
trait IncomeTaxCalculationConnector:
  def getCalculationDetails(nino: String, taxYear: String)(using
    HeaderCarrier
  ): Future[Either[CalcRetrieveError, ExistingCalculation]]

  def sourceLabel: String

@Singleton
class InProcessIncomeTaxCalculationConnector @Inject() ()
    extends IncomeTaxCalculationConnector with Logging:

  private val minYear = "2017-18"

  private val canned2017_18: String =
    Using.resource(Source.fromResource("calculations/stubs/hip-5294-2017-18.json"))(_.mkString)

  def sourceLabel: String = "in-process stub (HIP 5294 retrieve shape, GET only)"

  def getCalculationDetails(nino: String, taxYear: String)(using
    HeaderCarrier
  ): Future[Either[CalcRetrieveError, ExistingCalculation]] =
    Future.successful:
      if taxYear < minYear then Left(CalcRetrieveError.UnsupportedYear(taxYear))
      else if taxYear == minYear then
        Json.parse(canned2017_18) match
          case json =>
            ExistingCalculation.fromRetrieveJson(json, taxYear).left.map(CalcRetrieveError.Failed(_))
      else Left(CalcRetrieveError.NotFound(taxYear))

@Singleton
class HttpIncomeTaxCalculationConnector @Inject() (
  http          : HttpClientV2,
  servicesConfig: ServicesConfig,
  appConfig     : AppConfig
)(implicit ec: ExecutionContext)
    extends IncomeTaxCalculationConnector with Logging:

  private val baseUrl = servicesConfig.baseUrl("income-tax-calculation")

  def sourceLabel: String = s"HTTP GET $baseUrl (income-tax-calculation)"

  def getCalculationDetails(nino: String, taxYear: String)(using
    hc: HeaderCarrier
  ): Future[Either[CalcRetrieveError, ExistingCalculation]] =
    if taxYear < "2017-18" then
      Future.successful(Left(CalcRetrieveError.UnsupportedYear(taxYear)))
    else
      val url =
        url"$baseUrl/income-tax-calculation/income-tax/nino/$nino/calculation-details?taxYear=$taxYear"
      http
        .get(url)
        .setHeader("mtditid" -> appConfig.calculationsMtdRetrieveMtditid)
        .execute[HttpResponse]
        .map: response =>
          response.status match
            case 200 =>
              ExistingCalculation.fromRetrieveJson(response.json, taxYear).left.map(CalcRetrieveError.Failed(_))
            case 404 =>
              Left(CalcRetrieveError.NotFound(taxYear))
            case status =>
              logger.warn(s"[ITSA GET] $status from $url: ${response.body.take(200)}")
              Left(CalcRetrieveError.Failed(s"Unexpected status $status from calculation retrieve"))
        .recover:
          case ex =>
            logger.warn(s"[ITSA GET] Failed $url", ex)
            Left(CalcRetrieveError.Failed(ex.getMessage))

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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine

import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  DownstreamFetchSummary,
  ExistingCalculation,
  RateCatalog
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.IncomeTaxCalculationConnector
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

final case class PreparedRates(
  catalog    : RateCatalog,
  rateJson   : String,
  downstream : Option[DownstreamFetchSummary]
)

@Singleton
class DownstreamRateCatalogService @Inject() (
  connector: IncomeTaxCalculationConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext):

  def prepare(option: ArchitectureOption)(using HeaderCarrier): Future[PreparedRates] =
    val defaults = DefaultConfigs.defaultsFor(option)
    if option != ArchitectureOption.DownstreamRates then
      Future.successful(PreparedRates(defaults.catalog, defaults.rateJson, None))
    else overlay(defaults.catalog)

  private def overlay(base: RateCatalog)(using HeaderCarrier): Future[PreparedRates] =
    val nino = appConfig.calculationsMtdRetrieveNino
    val fetches: Seq[Future[(String, Option[ExistingCalculation])]] =
      base.years.map: pack =>
        connector.getCalculationDetails(nino, pack.taxYear).map:
          case Right(calc) => pack.taxYear -> Some(calc)
          case Left(_)     => pack.taxYear -> None

    Future.sequence(fetches).map: results =>
      val byYear = results.toMap
      val years = base.years.map: pack =>
        byYear.get(pack.taxYear).flatten match
          case Some(calc) => ExistingCalculation.overlay(pack, calc)
          case None       => pack
      val catalog = base.copy(version = s"${base.version}+downstream", years = years)
      val fetched = results.collect { case (year, Some(_)) => year }
      val unchanged = results.collect { case (year, None) => year }
      val calcId = results.collectFirst { case (_, Some(calc)) => calc.calculationId }.flatten
      PreparedRates(
        catalog = catalog,
        rateJson = Json.prettyPrint(Json.toJson(catalog)),
        downstream = Some(
          DownstreamFetchSummary(
            nino = nino,
            source = connector.sourceLabel,
            yearsFetched = fetched,
            yearsUnchanged = unchanged,
            calculationId = calcId
          )
        )
      )

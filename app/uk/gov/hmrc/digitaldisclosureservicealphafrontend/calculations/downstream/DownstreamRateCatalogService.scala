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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.downstream

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{DownstreamFetchSummary, RateCatalog}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.IncomeTaxCalculationConnector
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

final case class DownstreamRates(catalog: RateCatalog, summary: DownstreamFetchSummary)

/** Option 4: GETs an existing MTD calculation per tax year and overlays its figures. Years it cannot fetch stay as they are. */
@Singleton
class DownstreamRateCatalogService @Inject() (
  connector: IncomeTaxCalculationConnector,
  appConfig: AppConfig
)(implicit ec: ExecutionContext):

  def overlay(base: RateCatalog)(using HeaderCarrier): Future[DownstreamRates] =
    val nino = appConfig.calculationsMtdRetrieveNino
    val fetches = base.years.map: pack =>
      connector.getCalculationDetails(nino, pack.taxYear).map(result => pack.taxYear -> result.toOption)

    Future.sequence(fetches).map: results =>
      val byYear = results.toMap
      val years = base.years.map: pack =>
        byYear.get(pack.taxYear).flatten.fold(pack)(ExistingCalculation.overlay(pack, _))
      DownstreamRates(
        catalog = base.copy(version = s"${base.version}+downstream", years = years),
        summary = DownstreamFetchSummary(
          nino = nino,
          source = connector.sourceLabel,
          yearsFetched = results.collect { case (year, Some(_)) => year },
          yearsUnchanged = results.collect { case (year, None) => year },
          calculationId = results.collectFirst { case (_, Some(calc)) => calc.calculationId }.flatten
        )
      )

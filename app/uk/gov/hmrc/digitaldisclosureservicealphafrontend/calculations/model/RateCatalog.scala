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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model

import play.api.libs.json.{Format, Json}

/** Allowances and bands for a single tax year. */
final case class RatePack(
  taxYear              : String,
  version              : String,
  personalAllowance    : BigDecimal,
  taperThreshold       : BigDecimal,
  blindPersonsAllowance: BigDecimal,
  basicRateBand        : BigDecimal,
  basicRate            : BigDecimal,
  higherRate           : BigDecimal
)

object RatePack:
  given Format[RatePack] = Json.format[RatePack]

/** Versioned catalogue of rate packs by tax year. */
final case class RateCatalog(
  version: String,
  years  : Seq[RatePack]
):
  def taxYears: Seq[String] = years.map(_.taxYear)

  def forYear(taxYear: String): Option[RatePack] =
    years.find(_.taxYear == taxYear)

object RateCatalog:
  given Format[RateCatalog] = Json.format[RateCatalog]

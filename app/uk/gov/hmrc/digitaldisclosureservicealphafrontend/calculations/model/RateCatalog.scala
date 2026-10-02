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

import play.api.libs.json.{Format, JsNumber, JsObject, JsPath, JsResult, JsSuccess, Json, Reads, Writes}

import scala.collection.immutable.ListMap

/** Whether a rate is an amount in pounds or a fraction such as 0.2 (shown as 20%). */
enum RateKind:
  case amount, percentage

object RateKind:
  given Format[RateKind] = EnumJson.format(RateKind.values, "rate kind")

/**
  * A rate every tax year must provide, e.g. `personalAllowance`. The calculation spec refers to it by `key`.
  *
  * @param label message key for the rate's name in a sentence, e.g. "higher rate"
  */
final case class RateDefinition(
  key  : String,
  label: String,
  kind : RateKind
)

object RateDefinition:
  given Format[RateDefinition] = Json.format[RateDefinition]

/** The values of every declared rate for a single tax year, keyed by rate key in the order they were written. */
final case class RatePack(
  taxYear: String,
  version: String,
  values : ListMap[String, BigDecimal]
):
  def value(key: String): BigDecimal = values.getOrElse(key, BigDecimal(0))

object RatePack:
  private val valuesReads: Reads[ListMap[String, BigDecimal]] = Reads: json =>
    json.validate[JsObject].flatMap: obj =>
      obj.fields.foldLeft[JsResult[ListMap[String, BigDecimal]]](JsSuccess(ListMap.empty)): (acc, field) =>
        val (key, value) = field
        acc.flatMap(values => value.validate[BigDecimal].repath(JsPath \ key).map(values.updated(key, _)))

  private val valuesWrites: Writes[ListMap[String, BigDecimal]] =
    Writes(values => JsObject(values.toSeq.map((key, value) => key -> JsNumber(value))))

  private given Format[ListMap[String, BigDecimal]] = Format(valuesReads, valuesWrites)

  given Format[RatePack] = Json.format[RatePack]

/** Versioned catalogue of rate packs by tax year. */
final case class RateCatalog(
  version: String,
  rates  : Seq[RateDefinition],
  years  : Seq[RatePack]
):
  def taxYears: Seq[String] = years.map(_.taxYear)

  def forYear(taxYear: String): Option[RatePack] =
    years.find(_.taxYear == taxYear)

  def definition(key: String): Option[RateDefinition] =
    rates.find(_.key == key)

object TaxYear:
  /** "2017-18" → "2017 to 2018". */
  def display(taxYear: String): String =
    taxYear.split('-').toList match
      case start :: end :: Nil if start.length == 4 && end.length == 2 => s"$start to 20$end"
      case _                                                          => taxYear

object RateCatalog:
  given Format[RateCatalog] = Json.format[RateCatalog]

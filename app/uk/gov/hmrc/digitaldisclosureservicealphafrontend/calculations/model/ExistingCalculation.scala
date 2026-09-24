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

import play.api.libs.json.{JsArray, JsNumber, JsObject, JsString, JsValue}

/** Person-applied figures from an existing MTD retrieve (GET). Not a statutory catalogue. */
final case class ExistingCalculation(
  taxYear                : String,
  calculationId          : Option[String],
  personalAllowance      : Option[BigDecimal],
  blindPersonsAllowance  : Option[BigDecimal],
  basicRateBand          : Option[BigDecimal],
  basicRate              : Option[BigDecimal],
  higherRate             : Option[BigDecimal]
)

object ExistingCalculation:

  def fromRetrieveJson(json: JsValue, fallbackYear: String): Either[String, ExistingCalculation] =
    val root = json.asOpt[JsObject].getOrElse(JsObject.empty)
    val calculation = (root \ "calculation").asOpt[JsObject].orElse(root.asOpt[JsObject]).getOrElse(JsObject.empty)
    val allowances = (calculation \ "allowancesAndDeductions").asOpt[JsObject].getOrElse(JsObject.empty)
    val bands = payPensionsProfitBands(calculation)
    val taxYear =
      stringAt(root, "metadata", "taxYear")
        .orElse(stringAt(root, "taxYear"))
        .getOrElse(fallbackYear)

    Right(
      ExistingCalculation(
        taxYear = taxYear,
        calculationId = stringAt(root, "metadata", "calculationId").orElse(stringAt(root, "calculationId")),
        personalAllowance = numberAt(allowances, "personalAllowance"),
        blindPersonsAllowance = numberAt(allowances, "blindPersonsAllowance"),
        basicRateBand = bandLimit(bands, "basic-rate"),
        basicRate = bandRate(bands, "basic-rate"),
        higherRate = bandRate(bands, "higher-rate")
      )
    )

  def overlay(pack: RatePack, calc: ExistingCalculation): RatePack =
    pack.copy(
      version = s"${pack.taxYear}.downstream",
      personalAllowance = calc.personalAllowance.getOrElse(pack.personalAllowance),
      blindPersonsAllowance = calc.blindPersonsAllowance.getOrElse(pack.blindPersonsAllowance),
      basicRateBand = calc.basicRateBand.getOrElse(pack.basicRateBand),
      basicRate = calc.basicRate.getOrElse(pack.basicRate),
      higherRate = calc.higherRate.getOrElse(pack.higherRate)
    )

  private def payPensionsProfitBands(calculation: JsObject): Seq[JsObject] =
    val incomeTax = (calculation \ "taxCalculation" \ "incomeTax").asOpt[JsObject].getOrElse(JsObject.empty)
    val ppp = (incomeTax \ "payPensionsProfit").asOpt[JsObject].getOrElse(JsObject.empty)
    (ppp \ "taxBands").asOpt[JsArray].toSeq.flatMap(_.value.flatMap(_.asOpt[JsObject]))

  private def bandNamed(bands: Seq[JsObject], name: String): Option[JsObject] =
    bands.find(b => (b \ "name").asOpt[String].contains(name))

  private def bandLimit(bands: Seq[JsObject], name: String): Option[BigDecimal] =
    bandNamed(bands, name).flatMap(b => numberAt(b, "bandLimit"))

  /** Hub `rate` is a percentage (20). RatePack stores a fraction (0.20). */
  private def bandRate(bands: Seq[JsObject], name: String): Option[BigDecimal] =
    bandNamed(bands, name).flatMap(b => numberAt(b, "rate")).map: raw =>
      if raw > BigDecimal(1) then raw / BigDecimal(100) else raw

  private def stringAt(json: JsValue, path: String*): Option[String] =
    path
      .foldLeft(Option(json)): (cur, key) =>
        cur.flatMap(v => (v \ key).toOption)
      .flatMap(_.asOpt[JsString].map(_.value))

  private def numberAt(json: JsValue, field: String): Option[BigDecimal] =
    (json \ field).asOpt[JsNumber].map(_.value)

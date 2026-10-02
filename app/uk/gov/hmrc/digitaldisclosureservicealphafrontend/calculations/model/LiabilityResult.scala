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

/** One calculation step with the operation shown using concrete amounts.
  * `working` is a short arithmetic form (e.g. "£6,000.00 − £500.00") for compact computation views.
  */
final case class CalcExplanation(
  label    : String,
  operation: String,
  result   : String,
  working  : Option[String] = None
)

final case class LiabilityResult(
  taxYear        : String,
  ratePackVersion: String,
  totalIncome    : BigDecimal,
  /** Amount given for each allowance in the calculation spec, by allowance id. */
  allowancesUsed : Map[String, BigDecimal],
  taxableIncome  : BigDecimal,
  taxDue         : BigDecimal,
  explanations   : Seq[CalcExplanation]
):
  def breakdown: Seq[(String, String)] = explanations.map(e => e.label -> e.result)

final case class MultiYearLiabilityResult(
  catalogVersion    : String,
  calculationVersion: String,
  years             : Seq[LiabilityResult],
  totalTaxDue       : BigDecimal
)

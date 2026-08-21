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

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue, Json}

/** One calculation step with the operation shown using concrete amounts. */
final case class CalcExplanation(
  label    : String,
  operation: String,
  result   : String
)

final case class LiabilityResult(
  taxYear              : String,
  ratePackVersion      : String,
  totalIncome          : BigDecimal,
  personalAllowanceUsed: BigDecimal,
  blindAllowanceUsed   : BigDecimal,
  taxableIncome        : BigDecimal,
  taxDue               : BigDecimal,
  breakdown            : Seq[(String, String)],
  explanations         : Seq[CalcExplanation] = Seq.empty
)

final case class MultiYearLiabilityResult(
  catalogVersion    : String,
  calculationVersion: String,
  years             : Seq[LiabilityResult],
  totalTaxDue       : BigDecimal
)

/** How answers + rate packs are turned into a liability estimate. */
final case class CalculationSpec(
  id              : String,
  version         : String,
  description     : Option[String] = None,
  incomeComponents: Seq[IncomeComponent],
  allowances      : Seq[AllowanceRule],
  tax             : TaxRules
)

object CalculationSpec:
  given Format[CalculationSpec] = Json.using[Json.WithDefaultValues].format[CalculationSpec]

enum IncomeComponentKind:
  case amount, net

object IncomeComponentKind:
  given Format[IncomeComponentKind] = new Format[IncomeComponentKind]:
    def reads(json: JsValue): JsResult[IncomeComponentKind] =
      json.validate[String].flatMap:
        case "amount" => JsSuccess(IncomeComponentKind.amount)
        case "net"    => JsSuccess(IncomeComponentKind.net)
        case other    => JsError(s"Unknown income component kind: $other")
    def writes(k: IncomeComponentKind): JsValue = JsString(k.toString)

final case class IncomeComponent(
  id            : String,
  /** Message key: English words joined with underscores. */
  label         : String,
  kind          : IncomeComponentKind,
  field         : Option[String] = None,
  grossField    : Option[String] = None,
  deductField   : Option[String] = None,
  /** Optional second deduction (e.g. trading allowance vs expenses); summed with deductField. */
  altDeductField: Option[String] = None,
  floorAtZero   : Boolean = true
)

object IncomeComponent:
  given Format[IncomeComponent] = Json.using[Json.WithDefaultValues].format[IncomeComponent]

enum AllowanceKind:
  case personalAllowance, conditionalAmount

object AllowanceKind:
  given Format[AllowanceKind] = new Format[AllowanceKind]:
    def reads(json: JsValue): JsResult[AllowanceKind] =
      json.validate[String].flatMap:
        case "personalAllowance" => JsSuccess(AllowanceKind.personalAllowance)
        case "conditionalAmount" => JsSuccess(AllowanceKind.conditionalAmount)
        case other               => JsError(s"Unknown allowance kind: $other")
    def writes(k: AllowanceKind): JsValue = JsString(k.toString)

final case class TaperRule(
  thresholdRateKey: String,
  reduceBy        : BigDecimal,
  forEvery        : BigDecimal
)

object TaperRule:
  given Format[TaperRule] = Json.format[TaperRule]

final case class AllowanceRule(
  id     : String,
  /** Message key: English words joined with underscores. */
  label  : String,
  kind   : AllowanceKind,
  rateKey: String,
  taper  : Option[TaperRule] = None,
  when   : Option[ShowIf] = None
)

object AllowanceRule:
  given Format[AllowanceRule] = Json.using[Json.WithDefaultValues].format[AllowanceRule]

final case class TaxBandRule(
  rateKey    : String,
  /** Message key: English words joined with underscores. */
  label      : String,
  upToRateKey: Option[String] = None
)

object TaxBandRule:
  given Format[TaxBandRule] = Json.using[Json.WithDefaultValues].format[TaxBandRule]

final case class TaxRules(
  bands   : Seq[TaxBandRule],
  scale   : Int = 2,
  rounding: String = "halfUp"
)

object TaxRules:
  given Format[TaxRules] = Json.using[Json.WithDefaultValues].format[TaxRules]

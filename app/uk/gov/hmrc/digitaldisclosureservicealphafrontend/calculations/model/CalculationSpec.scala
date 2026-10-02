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

import scala.math.BigDecimal.RoundingMode

/** How answers + rate packs are turned into a liability estimate. */
final case class CalculationSpec(
  id              : String,
  version         : String,
  description     : Option[String] = None,
  incomeComponents: Seq[IncomeComponent],
  allowances      : Seq[AllowanceRule],
  /** Answer fields summed as tax already paid or deducted at source, then subtracted from the tax estimate. */
  taxPaidFields   : Seq[String] = Nil,
  tax             : TaxRules
)

object CalculationSpec:
  given Format[CalculationSpec] = Json.using[Json.WithDefaultValues].format[CalculationSpec]

enum IncomeComponentKind:
  case amount, net

object IncomeComponentKind:
  given Format[IncomeComponentKind] = EnumJson.format(IncomeComponentKind.values, "income component kind")

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
  given Format[AllowanceKind] = EnumJson.format(AllowanceKind.values, "allowance kind")

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
  rounding: Rounding = Rounding.halfUp
):
  def round(amount: BigDecimal): BigDecimal = amount.setScale(scale, rounding.mode)

/** How the final tax figure is rounded. Values match `tax.rounding` in the calculation spec. */
enum Rounding:
  case halfUp, up, down, floor, ceiling

  def mode: RoundingMode.Value =
    this match
      case Rounding.down | Rounding.floor => RoundingMode.DOWN
      case Rounding.up | Rounding.ceiling => RoundingMode.UP
      case Rounding.halfUp                => RoundingMode.HALF_UP

  /** Wording used when the graph describes this rule. */
  def description: String =
    this match
      case Rounding.down | Rounding.floor => "rounding down"
      case Rounding.up | Rounding.ceiling => "rounding up"
      case Rounding.halfUp                => "rounding half up"

object Rounding:
  given Format[Rounding] = EnumJson.format(Rounding.values, "rounding")

object TaxRules:
  given Format[TaxRules] = Json.using[Json.WithDefaultValues].format[TaxRules]

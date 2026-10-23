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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel

import org.camunda.feel.syntaxtree.{Exp, ParsedExpression}
import play.api.libs.json.{Format, Json}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.EnumJson

/** Which part of the result a step belongs to: the tax computation or the penalty. */
enum FeelPart:
  case tax, penalty

object FeelPart:
  given Format[FeelPart] = EnumJson.format(FeelPart.values, "step part")

/** How a step's value is written for people: £1,100.00, 21%, 0.5 or Yes. */
enum FeelValueFormat:
  case money, percentage, number, yesNo

object FeelValueFormat:
  given Format[FeelValueFormat] = EnumJson.format(FeelValueFormat.values, "value format")

/**
  * One named step of the calculation, worked out for each tax year in order.
  *
  * @param label  message key for the step's name
  * @param feel   a FEEL expression. It can read `answers.<question id>`, `rates.<rate key>`,
  *               `groups.<group>` and any earlier step by its id
  * @param group  adds this step's value to `groups.<group>` for later steps, e.g. every income line
  */
final case class FeelStep(
  id    : String,
  label : String,
  feel  : String,
  part  : FeelPart = FeelPart.tax,
  group : Option[String] = None,
  format: FeelValueFormat = FeelValueFormat.money
)

object FeelStep:
  given Format[FeelStep] = Json.using[Json.WithDefaultValues].format[FeelStep]

/** Which steps give the figures the result page reports. */
final case class FeelOutputs(
  totalIncome    : String,
  taxableIncome  : String,
  taxDue         : String,
  allowances     : Seq[String] = Nil,
  penalty        : Option[String] = None,
  penaltyAnswered: Option[String] = None
):
  def stepIds: Seq[String] = Seq(totalIncome, taxableIncome, taxDue) ++ allowances ++ penalty ++ penaltyAnswered

object FeelOutputs:
  given Format[FeelOutputs] = Json.using[Json.WithDefaultValues].format[FeelOutputs]

/** The calculation as named FEEL steps: the alternative to the calculation spec used by Option 5. */
final case class FeelRules(
  id         : String,
  version    : String,
  description: Option[String] = None,
  steps      : Seq[FeelStep],
  outputs    : FeelOutputs
)

object FeelRules:
  given Format[FeelRules] = Json.using[Json.WithDefaultValues].format[FeelRules]

/** Rules that passed validation, with every step's expression parsed once. */
final case class CompiledFeelRules(rules: FeelRules, parsed: Map[String, ParsedExpression]):
  def steps: Seq[FeelStep] = rules.steps
  def expression(step: FeelStep): Exp = parsed(step.id).expression

/** The FEEL rules document as edited on the config page, alongside its compiled form. */
final case class FeelRulesConfig(json: String, compiled: CompiledFeelRules)

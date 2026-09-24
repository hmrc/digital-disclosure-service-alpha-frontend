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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph

final case class GraphScreen(
  id        : String,
  title     : String,
  screenType: String,
  perTaxYear: Boolean,
  condition : Option[String],
  feeds     : Option[String]
)

/** One conditional lane hanging off the shared journey (e.g. an income type). */
final case class JourneyBranch(
  id     : String,
  label  : String,
  screens: Seq[GraphScreen]
)

/** Format B model: always-on screens plus parallel showIf lanes. */
final case class JourneyBranchMap(
  trunk   : Seq[GraphScreen],
  branches: Seq[JourneyBranch]
)

/** A question as it appears inside a task-list task. `depth` indents follow-up questions under their parent. */
final case class JourneyQuestion(
  id        : String,
  title     : String,
  answerType: String,
  options   : Seq[String],
  perTaxYear: Boolean,
  condition : Option[String],
  depth     : Int,
  usedIn    : Seq[String],
  isAmount  : Boolean
):
  def notInEstimate: Boolean = isAmount && usedIn.isEmpty

final case class JourneyTask(
  id       : String,
  title    : String,
  trigger  : Option[String],
  questions: Seq[JourneyQuestion]
)

final case class JourneySection(
  id   : String,
  title: String,
  intro: Option[String],
  tasks: Seq[JourneyTask]
)

/** The question pack grouped the way the task list presents it. */
final case class JourneyMap(
  sections: Seq[JourneySection],
  unplaced: Seq[JourneyQuestion]
):
  def allQuestions: Seq[JourneyQuestion] = sections.flatMap(_.tasks).flatMap(_.questions) ++ unplaced
  def notInEstimate: Seq[JourneyQuestion] = allQuestions.filter(_.notInEstimate)

/** One item within a calculation stage. `source` names the config fields or rate keys it reads. */
final case class CalcRule(
  label : String,
  rule  : String,
  source: Option[String] = None,
  /** Several items sharing one rule, as (label, config field). */
  items : Seq[(String, String)] = Nil
)

/** A stage of the liability calculation, e.g. "Work out total income". */
final case class CalcStage(
  id     : String,
  title  : String,
  formula: String,
  rules  : Seq[CalcRule]
)

final case class RateTableRow(
  label : String,
  values: Seq[String]
)

/** Rate catalogue values with one column per tax year. */
final case class RateTable(
  years: Seq[String],
  rows : Seq[RateTableRow]
)

enum ComputationRowKind:
  case item, deduction, subtotal, total

final case class ComputationCell(
  amount : String,
  working: Option[String] = None
)

final case class ComputationRow(
  label: String,
  kind : ComputationRowKind,
  cells: Seq[ComputationCell]
)

/** A worked example laid out as a tax computation with one column per tax year. */
final case class Computation(
  years    : Seq[String],
  rows     : Seq[ComputationRow],
  zeroItems: Seq[String]
)

final case class ExampleJourneyStep(
  id   : String,
  title: String,
  note : String
)

final case class ExampleCalcOp(
  label    : String,
  operation: String,
  result   : String
)

final case class ExampleYearCalc(
  taxYear: String,
  ops    : Seq[ExampleCalcOp],
  taxDue : String
)

final case class GraphExample(
  id          : String,
  titleKey    : String,
  summaryKey  : String,
  answers     : Seq[(String, String)],
  journeySteps: Seq[ExampleJourneyStep],
  computation : Computation,
  yearCalcs   : Seq[ExampleYearCalc],
  totalTaxDue : String
)

final case class GraphModel(
  architectureMermaid: String,
  journeyMermaid     : String,
  calculationMermaid : String,
  journeyMap         : JourneyMap,
  calcScope          : Option[String],
  calcStages         : Seq[CalcStage],
  rateTable          : RateTable,
  examples           : Seq[GraphExample],
  rateYears          : Seq[String],
  catalogVersion     : String,
  calculationVersion : String,
  selectedYears      : Seq[String]
)

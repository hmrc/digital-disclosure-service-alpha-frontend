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

final case class GraphCalcStep(
  id       : String,
  label    : String,
  operation: String,
  detail   : String
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
  yearCalcs   : Seq[ExampleYearCalc],
  totalTaxDue : String
)

final case class GraphModel(
  architectureMermaid: String,
  journeyMermaid     : String,
  calculationMermaid : String,
  screens            : Seq[GraphScreen],
  journeyBranches    : JourneyBranchMap,
  calcSteps          : Seq[GraphCalcStep],
  examples           : Seq[GraphExample],
  rateYears          : Seq[String],
  catalogVersion     : String,
  calculationVersion : String,
  selectedYears      : Seq[String]
)

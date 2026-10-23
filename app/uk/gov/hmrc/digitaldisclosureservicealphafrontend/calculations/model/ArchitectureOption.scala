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

enum ArchitectureOption(
  val id               : String,
  val questionsEditable: Boolean,
  val landsOnTaskList  : Boolean
):
  case RatesOnly extends ArchitectureOption("rates-only", questionsEditable = false, landsOnTaskList = true)
  case RatesAndQuestions
      extends ArchitectureOption("rates-and-questions", questionsEditable = true, landsOnTaskList = false)
  case DownstreamRates
      extends ArchitectureOption("downstream-rates", questionsEditable = false, landsOnTaskList = true)
  case FullEngine extends ArchitectureOption("full-engine", questionsEditable = true, landsOnTaskList = false)
  case FeelRules extends ArchitectureOption("feel-rules", questionsEditable = false, landsOnTaskList = true)

  def configBackToTaskList: Boolean = landsOnTaskList
  def implemented: Boolean = this != FullEngine

  /** Option 4 overlays rates from an existing MTD calculation onto the default catalogue. */
  def fetchesDownstreamRates: Boolean = this == DownstreamRates

  /** Option 5 calculates with FEEL rules instead of the calculation spec. */
  def usesFeelRules: Boolean = this == FeelRules

  /** The full pack has every question the calculation reads; the fixed pack has a subset. */
  def usesFullQuestionPack: Boolean = questionsEditable || usesFeelRules

object ArchitectureOption:
  def fromId(id: String): Option[ArchitectureOption] =
    values.find(_.id == id)

  val demoOptions: Seq[ArchitectureOption] =
    Seq(RatesOnly, RatesAndQuestions, DownstreamRates, FeelRules, FullEngine)

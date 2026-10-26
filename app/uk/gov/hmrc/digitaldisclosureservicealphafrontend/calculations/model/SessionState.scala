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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{Journey, JourneyState}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel.FeelRulesConfig

/**
  * @param log       everything the user has answered, in order. Only ever added to; see [[LogEntry]].
  * @param feelRules set for Option 5, which calculates with these FEEL rules instead of the calculation spec
  */
final case class SessionState(
  id        : String,
  option    : ArchitectureOption,
  config    : CalculationsConfig,
  log       : Vector[LogEntry] = Vector.empty,
  downstream: Option[DownstreamFetchSummary] = None,
  feelRules : Option[FeelRulesConfig] = None
):
  def rateCatalog    : RateCatalog     = config.rateCatalog
  def questionPack   : QuestionPack    = config.questionPack
  def calculationSpec: CalculationSpec = config.calculationSpec

  /** The log replayed against this session's config. Worked out once per state, never stored. */
  lazy val journey: JourneyState = Journey.project(questionPack, rateCatalog, log)

  /** The answers that count. */
  def answers: Map[String, String] = journey.effective

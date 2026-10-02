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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.SessionState

/** Everything the graph page shows for a session's config. */
object GraphBuilder:

  def build(state: SessionState, translate: String => String = identity): GraphModel =
    GraphModel(
      architectureMermaid = MermaidDiagrams.architecture(state),
      journeyMermaid = MermaidDiagrams.journey(state.questionPack, translate),
      calculationMermaid = MermaidDiagrams.calculation(state.calculationSpec, state.rateCatalog, translate),
      journeyMap = JourneyMapBuilder.build(state.questionPack, state.rateCatalog, state.calculationSpec, translate),
      calcScope = state.calculationSpec.description,
      calcStages = CalculationStagesBuilder.stages(state.calculationSpec, state.rateCatalog, translate),
      rateTable = CalculationStagesBuilder.rateTable(state.rateCatalog, translate),
      examples = WorkedExamplesBuilder.build(state, translate),
      rateYears = state.rateCatalog.taxYears,
      catalogVersion = state.rateCatalog.version,
      calculationVersion = state.calculationSpec.version,
      selectedYears = QuestionEngine.selectedTaxYears(state.answers)
    )

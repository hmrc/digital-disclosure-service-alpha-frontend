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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption

class MermaidDiagramsSpec extends AnyWordSpec with Matchers:

  "MermaidDiagrams.architecture" should:
    "show the MTD GET only for Option 4" in:
      MermaidDiagrams.architecture(stateFor(ArchitectureOption.RatesAndQuestions)) should include("Stage 1")
      MermaidDiagrams.architecture(stateFor(ArchitectureOption.RatesAndQuestions)) should not include "HIP 5294"
      MermaidDiagrams.architecture(stateFor(ArchitectureOption.DownstreamRates)) should include("HIP 5294")

  "MermaidDiagrams.journey" should:
    "hang a lane off the trunk for each income type" in:
      val journey = MermaidDiagrams.journey(fullPack, identity)
      journey should startWith("flowchart TD")
      journey should include("n_lane_incomeTypes_selfEmployment")
      journey should include("n_incomeTypes --> n_lane_incomeTypes_selfEmployment")
      journey should include("|selfEmploymentUseTradingAllowance=no| n_selfEmploymentExpenses")

    "draw a straight line for a pack with no conditions" in:
      MermaidDiagrams.journey(fixedPack, identity) should not include "lane_"

  "MermaidDiagrams.calculation" should:
    "take tax paid off the banded tax" in:
      val calculation = MermaidDiagrams.calculation(spec, catalog, identity)
      calculation should include("totalIncome")
      calculation should include("taxPaid -->|minus| taxDue")
      calculation should not include "max("

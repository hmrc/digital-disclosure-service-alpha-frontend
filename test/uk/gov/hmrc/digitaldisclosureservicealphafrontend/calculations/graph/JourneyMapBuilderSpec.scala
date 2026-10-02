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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.QuestionPack

class JourneyMapBuilderSpec extends AnyWordSpec with Matchers:

  private def journeyFor(pack: QuestionPack) = JourneyMapBuilder.build(pack.questions, catalog, spec, identity)

  "JourneyMapBuilder" should:
    "group the full pack the way the task list presents it" in:
      val journey = journeyFor(fullPack)
      journey.sections.map(_.id) shouldBe Seq("prepare", "income-types", "gain-types", "year")
      journey.unplaced shouldBe empty

      val tasks = journey.sections.flatMap(_.tasks).map(t => t.id -> t).toMap
      tasks("about-disclosure").questions.map(_.id) shouldBe Seq("taxYears", "incomeTypes")
      tasks("about-disclosure").questions.head.options shouldBe catalog.taxYears
      tasks("year-declared").questions.map(_.id) shouldBe Seq("alreadyDeclaredIncome", "taxAlreadyPaid")

    "show conditions and indent follow-up questions" in:
      val tasks = journeyFor(fullPack).sections.flatMap(_.tasks).map(t => t.id -> t).toMap
      val selfEmployment = tasks("income-selfEmployment").questions.map(q => q.id -> q).toMap
      selfEmployment("selfEmploymentTurnover").condition shouldBe None
      selfEmployment("selfEmploymentExpenses").condition shouldBe defined
      selfEmployment("selfEmploymentExpenses").depth shouldBe 1

    "say which calculation lines read each answer" in:
      val journey = journeyFor(fullPack)
      val tasks = journey.sections.flatMap(_.tasks).map(t => t.id -> t).toMap
      tasks("income-selfEmployment").questions.find(_.id == "selfEmploymentTurnover").map(_.usedIn) shouldBe
        Some(Seq("Self_employment_profit (calculations.graph.journey.usage.gross)"))
      tasks("allowances").questions.find(_.id == "blindPersonEligible").map(_.usedIn.size) shouldBe Some(1)
      tasks("year-declared").questions.find(_.id == "taxAlreadyPaid").map(_.usedIn) shouldBe Some(Seq("Tax_already_paid"))
      journey.notInEstimate.map(_.id) should contain allOf ("alreadyDeclaredIncome", "foreignTaxPaid", "cgtResidentialProperty")
      journey.notInEstimate.map(_.id) should not contain "dividends"

    "place Option 1's fixed per-year questions in a year task" in:
      val journey = journeyFor(fixedPack)
      journey.unplaced shouldBe empty
      val yearTasks = journey.sections.find(_.id == "year").get.tasks
      yearTasks.map(_.id) shouldBe Seq("year-declared", "year-other")
      yearTasks.find(_.id == "year-other").get.questions.map(_.id) should contain allOf ("bankInterest", "dividends", "capitalGains")

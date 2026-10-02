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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption

class TaskListBuilderSpec extends AnyWordSpec with Matchers:

  private def taskList(answers: Map[String, String], option: ArchitectureOption = ArchitectureOption.RatesAndQuestions) =
    TaskListBuilder.build(stateFor(option, answers))

  private val dividendsIn2015 = aboutYou ++ Map("taxYears" -> "2015-16", "incomeTypes" -> "dividends")

  "TaskListBuilder.build" should:
    "ask Option 1's fixed income questions in a per-year task" in:
      val model = taskList(Map("taxYears" -> "2015-16"), ArchitectureOption.RatesOnly)
      val other = model.allItems.find(_.id == "year-2015-16-other").get
      other.questionIds should contain allOf ("bankInterest__2015-16", "dividends__2015-16", "selfEmploymentTurnover__2015-16")
      other.questionIds should not contain "taxAlreadyPaid__2015-16"
      model.allItems.filter(_.id != "year-2015-16-other").flatMap(_.questionIds) should not contain "bankInterest__2015-16"

    "not add an extra per-year task when every question has a task" in:
      taskList(Map("taxYears" -> "2015-16", "incomeTypes" -> "dividends")).allItems.map(_.id) should not contain "year-2015-16-other"

    "show prepare tasks before income types are chosen" in:
      val model = taskList(Map.empty)
      model.sections.find(_.id == "prepare").get.items.map(_.id) should contain("about-disclosure")
      model.allItems.find(_.id == "about-disclosure").get.status shouldBe TaskStatus.NotStarted

    "lock year tasks until prepare and income-type tasks are complete" in:
      val model = taskList(dividendsIn2015)
      model.sections.map(_.id) should contain allOf ("income-types", "year-2015-16")
      model.allItems.find(_.id == "income-dividends").get.status shouldBe TaskStatus.NotStarted
      model.allItems.find(_.id.startsWith("year-2015-16")).get.status shouldBe TaskStatus.CannotStartYet

  "TaskListBuilder.nextQuestionId" should:
    "return the next question within a task" in:
      val item = taskList(dividendsIn2015).allItems.find(_.id == "income-dividends").get
      item.questionIds should not be empty
      TaskListBuilder.nextQuestionId(item, item.questionIds.head) shouldBe item.questionIds.lift(1)

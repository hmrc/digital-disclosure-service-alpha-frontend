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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ArchitectureOption, SessionState}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.{TaskListBuilder, TaskStatus}

class TaskListBuilderSpec extends AnyWordSpec with Matchers:

  private def stateWith(answers: Map[String, String]): SessionState =
    val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
    SessionState(
      id = "test",
      option = ArchitectureOption.RatesAndQuestions,
      rateJson = defaults.rateJson,
      questionJson = defaults.questionJson,
      calculationJson = defaults.calculationJson,
      rateCatalog = defaults.catalog,
      questionPack = defaults.questions,
      calculationSpec = defaults.calculation,
      answers = answers
    )

  "TaskListBuilder" should:
    "show prepare tasks before income types are chosen" in:
      val model = TaskListBuilder.build(stateWith(Map.empty))
      model.sections.map(_.id) should contain("prepare")
      model.sections.find(_.id == "prepare").get.items.map(_.id) should contain("about-disclosure")
      model.allItems.find(_.id == "about-disclosure").get.status shouldBe TaskStatus.NotStarted

    "lock year tasks until prepare and income-type tasks are complete" in:
      val answers = Map(
        "taxYears" -> "2015-16",
        "incomeTypes" -> "dividends",
        "ageBand" -> "under65",
        "marriedOrCivilPartnership" -> "no",
        "blindPersonEligible" -> "no"
      )
      val model = TaskListBuilder.build(stateWith(answers))
      model.sections.map(_.id) should contain("income-types")
      model.sections.map(_.id) should contain("year-2015-16")
      model.allItems.find(_.id == "income-dividends").get.status shouldBe TaskStatus.NotStarted
      model.allItems.find(_.id.startsWith("year-2015-16")).get.status shouldBe TaskStatus.CannotStartYet

    "return to the next question within a task" in:
      val item = TaskListBuilder
        .build(
          stateWith(
            Map(
              "taxYears" -> "2015-16",
              "incomeTypes" -> "dividends",
              "ageBand" -> "under65",
              "marriedOrCivilPartnership" -> "no",
              "blindPersonEligible" -> "no"
            )
          )
        )
        .allItems
        .find(_.id == "income-dividends")
        .get
      item.questionIds should not be empty
      val first = item.questionIds.head
      TaskListBuilder.nextQuestionId(item, first) shouldBe item.questionIds.lift(1)

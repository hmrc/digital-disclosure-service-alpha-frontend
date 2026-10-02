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
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.ConfigValidator
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  CategoryTask,
  ConfigQuestion,
  QuestionOption,
  QuestionType,
  ShowIf
}

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
      model.sections.map(_.id) should contain allOf ("income", "year-2015-16")
      model.allItems.find(_.id == "income-dividends").get.status shouldBe TaskStatus.NotStarted
      model.allItems.find(_.id.startsWith("year-2015-16")).get.status shouldBe TaskStatus.CannotStartYet

    "give a new income type its own tasks from config alone" in:
      val lottery = ConfigQuestion(
        id = "lotteryWinnings",
        `type` = QuestionType.currency,
        title = "Lottery_winnings",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("lottery"))),
        perTaxYear = true
      )
      val questions = fullPack.questions.map: q =>
        if q.id == "incomeTypes" then q.copy(options = q.options.map(_ :+ QuestionOption("lottery", "Lottery"))) else q
      val sections = fullPack.taskList.sections.map: section =>
        if section.id != "income" then section
        else section.copy(forEachSelected = section.forEachSelected.map(c => c.copy(tasks = c.tasks :+ CategoryTask("lottery", "Lottery", Some("Add_lottery")))))
      val pack = fullPack.copy(questions = questions :+ lottery, taskList = fullPack.taskList.copy(sections = sections))
      val config = ConfigValidator
        .validate(fullConfig.rateJson, Json.prettyPrint(Json.toJson(pack)), fullConfig.calculationJson)
        .fold(errors => fail(ConfigValidator.formatErrors(errors)), identity)
      val answers = aboutYou ++ Map("taxYears" -> "2015-16", "incomeTypes" -> "dividends,lottery")
      val model = TaskListBuilder.build(stateFor(ArchitectureOption.RatesAndQuestions, answers).copy(config = config))

      model.sections.find(_.id == "income").get.items.map(_.id) shouldBe Seq("income-dividends", "income-lottery")
      model.allItems.find(_.id == "year-2015-16-income-lottery").map(_.questionIds) shouldBe Some(Seq("lotteryWinnings__2015-16"))

  "TaskListBuilder.nextQuestionId" should:
    "return the next question within a task" in:
      val item = taskList(dividendsIn2015).allItems.find(_.id == "income-dividends").get
      item.questionIds should not be empty
      TaskListBuilder.nextQuestionId(item, item.questionIds.head) shouldBe item.questionIds.lift(1)

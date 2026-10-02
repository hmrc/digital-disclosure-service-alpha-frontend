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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  EachYearConfig,
  ForEachSelected,
  Lane,
  ResolvedQuestion,
  SessionState,
  TaskConfig,
  TaxYear,
  YearAnswers
}

enum TaskStatus:
  case Completed, NotStarted, CannotStartYet

final case class TaskListItem(
  id         : String,
  titleKey   : String,
  titleArgs  : Seq[String] = Nil,
  hintKey    : Option[String] = None,
  status     : TaskStatus,
  questionIds: Seq[String]
)

final case class TaskListSection(
  id       : String,
  titleKey : String,
  titleArgs: Seq[String] = Nil,
  items    : Seq[TaskListItem]
):
  def isComplete: Boolean = items.forall(_.status == TaskStatus.Completed)

final case class TaskListModel(
  sections: Seq[TaskListSection]
):
  def allItems: Seq[TaskListItem] = sections.flatMap(_.items)

  def itemForQuestion(questionId: String): Option[TaskListItem] =
    val matches = allItems.filter(_.questionIds.contains(questionId))
    YearAnswers.parse(questionId)._2
      .flatMap(year => matches.find(_.id.startsWith(s"year-$year")))
      .orElse(matches.headOption)

/** Lays out the visible questions using the question pack's `taskList`, and works out which tasks can start. */
object TaskListBuilder:

  def build(state: SessionState): TaskListModel =
    val pack = state.questionPack
    val layout = pack.taskList
    val answers = state.answers
    val visible = QuestionEngine.visibleQuestions(pack, state.rateCatalog, answers)
    val laneOf = pack.questions.map(q => q.id -> Lane.of(q, pack)).toMap

    def fixedIds(task: TaskConfig, year: Option[String]): Seq[String] =
      task.questions.flatMap(base => visible.filter(q => q.template.id == base && inYear(q, year)).map(_.id))

    def laneIds(lane: Lane, year: Option[String]): Seq[String] =
      visible.filter(q => laneOf.get(q.template.id).flatten.contains(lane) && inYear(q, year)).map(_.id)

    def selected(category: ForEachSelected) =
      val ticked = Answers.values(answers, category.question)
      category.tasks.filter(t => ticked.contains(t.value))

    val built = layout.sections.foldLeft(Seq.empty[TaskListSection]): (done, section) =>
      val locked = !section.startsAfter.forall(id => done.find(_.id == id).forall(_.isComplete))
      val fixed = section.tasks.map(t => pending(t.id, t.title, t.hint, fixedIds(t, None)))
      val categoryTasks = section.forEachSelected.toSeq.flatMap: category =>
        selected(category).map(t => pending(s"${section.id}-${t.value}", t.title, None, laneIds(Lane(category.question, t.value), None)))
      val items = withStatus(fixed ++ categoryTasks, answers, locked, section.inOrder)
      done :+ TaskListSection(id = section.id, titleKey = section.title, items = items)

    val sections = built.filter(_.items.nonEmpty)
    val claimedOutsideYears = sections.flatMap(_.items).flatMap(_.questionIds).toSet

    def yearSection(year: String, eachYear: EachYearConfig, locked: Boolean): TaskListSection =
      def fixed(tasks: Seq[TaskConfig]) =
        tasks.map(t => pending(s"year-$year-${t.id}", t.title, t.hint, fixedIds(t, Some(year))))
      val before = fixed(eachYear.before)
      val after = fixed(eachYear.after)
      val categoryTasks =
        for
          section  <- layout.sections
          category <- section.forEachSelected.toSeq
          task     <- selected(category)
          title    <- task.yearTitle
        yield pending(s"year-$year-${section.id}-${task.value}", title, None, laneIds(Lane(category.question, task.value), Some(year)))
      val claimed = claimedOutsideYears ++ (before ++ after ++ categoryTasks).flatMap(_.questionIds)
      val leftover = eachYear.leftover.toSeq.map: t =>
        pending(s"year-$year-${t.id}", t.title, t.hint, visible.filter(q => q.taxYear.contains(year) && !claimed.contains(q.id)).map(_.id))
      TaskListSection(
        id = s"year-$year",
        titleKey = eachYear.title,
        titleArgs = Seq(TaxYear.display(year)),
        items = withStatus(before ++ categoryTasks ++ leftover ++ after, answers, locked, inOrder = false)
      )

    val yearSections =
      for
        eachYear <- layout.eachYear.toSeq
        locked = !eachYear.startsAfter.forall(id => built.find(_.id == id).forall(_.isComplete))
        year    <- QuestionEngine.selectedTaxYears(answers)
        section = yearSection(year, eachYear, locked)
        if section.items.nonEmpty
      yield section

    val canFinalise = (sections ++ yearSections).forall(_.isComplete)
    val finalSection = TaskListSection(
      id = "final",
      titleKey = "calculations.taskList.section.final",
      items = Seq(
        TaskListItem(
          id = "final-calculations",
          titleKey = "calculations.taskList.item.final",
          status = if canFinalise then TaskStatus.NotStarted else TaskStatus.CannotStartYet,
          questionIds = Nil
        )
      )
    )

    TaskListModel(sections ++ yearSections :+ finalSection)

  def startQuestionId(item: TaskListItem, answers: Map[String, String]): Option[String] =
    item.questionIds.find(id => !answers.contains(id)).orElse(item.questionIds.headOption)

  def nextQuestionId(item: TaskListItem, afterId: String): Option[String] =
    val idx = item.questionIds.indexOf(afterId)
    if idx < 0 then item.questionIds.headOption
    else item.questionIds.lift(idx + 1)

  def previousQuestionId(item: TaskListItem, currentId: String): Option[String] =
    val idx = item.questionIds.indexOf(currentId)
    if idx > 0 then Some(item.questionIds(idx - 1)) else None

  private def inYear(q: ResolvedQuestion, year: Option[String]): Boolean =
    year.forall(y => q.taxYear.contains(y))

  private def pending(id: String, titleKey: String, hintKey: Option[String], questionIds: Seq[String]): TaskListItem =
    TaskListItem(id = id, titleKey = titleKey, hintKey = hintKey, status = TaskStatus.NotStarted, questionIds = questionIds)

  /** Drops tasks with nothing to ask, then sets each status. */
  private def withStatus(
    items  : Seq[TaskListItem],
    answers: Map[String, String],
    locked : Boolean,
    inOrder: Boolean
  ): Seq[TaskListItem] =
    items.filter(_.questionIds.nonEmpty).foldLeft(Seq.empty[TaskListItem]): (done, item) =>
      val waiting = locked || (inOrder && done.lastOption.exists(_.status != TaskStatus.Completed))
      val status =
        if waiting then TaskStatus.CannotStartYet
        else if item.questionIds.forall(answers.contains) then TaskStatus.Completed
        else TaskStatus.NotStarted
      done :+ item.copy(status = status)

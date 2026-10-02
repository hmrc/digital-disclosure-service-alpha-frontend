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

import play.api.libs.json.{Format, Json}

/**
  * A task that always asks the same questions, e.g. "About you".
  *
  * @param questions question ids in the order they are asked. A per-year question is asked once per selected year.
  */
final case class TaskConfig(
  id       : String,
  title    : String,
  hint     : Option[String] = None,
  questions: Seq[String]
)

object TaskConfig:
  given Format[TaskConfig] = Json.format[TaskConfig]

/** A tax year's catch-all task: it asks that year's per-year questions that no other task asks. */
final case class LeftoverTask(
  id   : String,
  title: String,
  hint : Option[String] = None
)

object LeftoverTask:
  given Format[LeftoverTask] = Json.format[LeftoverTask]

/**
  * One task per ticked option of a checkbox question, e.g. a task for each income type.
  *
  * @param yearTitle when set, the option also gets a task in every tax-year section holding that year's questions
  */
final case class CategoryTask(
  value    : String,
  title    : String,
  yearTitle: Option[String] = None
)

object CategoryTask:
  given Format[CategoryTask] = Json.using[Json.WithDefaultValues].format[CategoryTask]

/**
  * A checkbox question whose ticked options each get a task. A question belongs to an option's task when its
  * `showIf` is `{"field": question, "contains": value}`, or when it follows up a question that does.
  *
  * @param tasks the options that get a task, in the order they are listed
  */
final case class ForEachSelected(
  question: String,
  tasks   : Seq[CategoryTask]
)

object ForEachSelected:
  given Format[ForEachSelected] = Json.format[ForEachSelected]

/**
  * A section of the task list.
  *
  * @param intro       message key shown above the section on the journey map
  * @param startsAfter sections that must be complete before this section's tasks can start
  * @param inOrder     when true, each task can only start once the one before it is complete
  */
final case class TaskSectionConfig(
  id             : String,
  title          : String,
  intro          : Option[String] = None,
  startsAfter    : Seq[String] = Nil,
  inOrder        : Boolean = false,
  tasks          : Seq[TaskConfig] = Nil,
  forEachSelected: Option[ForEachSelected] = None
)

object TaskSectionConfig:
  given Format[TaskSectionConfig] = Json.using[Json.WithDefaultValues].format[TaskSectionConfig]

/**
  * The section repeated for each selected tax year. Tasks are listed as: `before`, then each category's year
  * task, then `leftover`, then `after`. Task ids are prefixed with `year-<taxYear>-`.
  *
  * @param title    message key with `{0}` for the tax year, e.g. "2017 to 2018"
  * @param leftover collects that year's per-year questions no other task asks
  */
final case class EachYearConfig(
  title      : String,
  intro      : Option[String] = None,
  startsAfter: Seq[String] = Nil,
  before     : Seq[TaskConfig] = Nil,
  leftover   : Option[LeftoverTask] = None,
  after      : Seq[TaskConfig] = Nil
):
  def fixedTasks: Seq[TaskConfig] = before ++ after

object EachYearConfig:
  given Format[EachYearConfig] = Json.using[Json.WithDefaultValues].format[EachYearConfig]

/** Which questions are asked in which task. The final "Calculate" section is always added after these. */
final case class TaskListConfig(
  sections: Seq[TaskSectionConfig],
  eachYear: Option[EachYearConfig] = None
):
  def categories: Seq[ForEachSelected] = sections.flatMap(_.forEachSelected)

  def categoryFields: Set[String] = categories.map(_.question).toSet

  def fixedTasks: Seq[TaskConfig] = sections.flatMap(_.tasks) ++ eachYear.toSeq.flatMap(_.fixedTasks)

object TaskListConfig:
  given Format[TaskListConfig] = Json.using[Json.WithDefaultValues].format[TaskListConfig]

/** A ticked option of a category question, e.g. `incomeTypes` includes `dividends`. */
final case class Lane(field: String, value: String):
  def key: String = s"$field:$value"

object Lane:
  /**
    * The category option a question belongs to. A question whose `showIf` depends on another question inherits
    * that question's lane, so follow-up questions stay in the same task.
    */
  def of(q: ConfigQuestion, pack: QuestionPack): Option[Lane] =
    of(q, pack.questions.map(q => q.id -> q).toMap, pack.taskList.categoryFields, Set.empty)

  private def of(
    q             : ConfigQuestion,
    byId          : Map[String, ConfigQuestion],
    categoryFields: Set[String],
    seen          : Set[String]
  ): Option[Lane] =
    if seen.contains(q.id) then None
    else
      q.showIf.flatMap: rule =>
        rule.contains.filter(_ => categoryFields.contains(rule.field)) match
          case Some(value) => Some(Lane(rule.field, value))
          case None        => byId.get(rule.field).flatMap(parent => of(parent, byId, categoryFields, seen + q.id))

  /** Whether the rule is what puts a question in a lane rather than a follow-up condition. */
  def isLaneRule(rule: ShowIf, pack: QuestionPack): Boolean =
    rule.contains.isDefined && pack.taskList.categoryFields.contains(rule.field)

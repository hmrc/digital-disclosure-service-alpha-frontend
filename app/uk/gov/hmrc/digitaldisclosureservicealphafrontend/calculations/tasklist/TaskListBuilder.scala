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
  ConfigQuestion,
  ResolvedQuestion,
  SessionState,
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
)

final case class TaskListModel(
  sections: Seq[TaskListSection]
):
  def allItems: Seq[TaskListItem] = sections.flatMap(_.items)

  def itemForQuestion(questionId: String): Option[TaskListItem] =
    val matches = allItems.filter(_.questionIds.contains(questionId))
    YearAnswers.parse(questionId)._2
      .flatMap(year => matches.find(_.id.startsWith(s"year-$year")))
      .orElse(matches.headOption)

object TaskListBuilder:

  private val AboutDisclosureIds = Seq("taxYears", "incomeTypes")
  private val AboutYouIds = Seq("ageBand", "marriedOrCivilPartnership")
  private val AllowanceIds = Seq(
    "blindPersonEligible",
    "blindAllowanceAlreadyClaimed",
    "marriageAllowanceClaim",
    "marriageAllowanceAlreadyInTaxCode"
  )
  private val AlreadyDeclaredIds = Seq("alreadyDeclaredIncome", "taxAlreadyPaid")
  private val ReliefIds = Seq("claimAnyReliefs", "otherReliefs")

  private val IncomeTypeOrder = Seq(
    "employment",
    "selfEmployment",
    "ukProperty",
    "pension",
    "employmentBenefits",
    "trustsEstates",
    "chargeableEventGains",
    "partnership",
    "remittanceBasisCharge",
    "pensionCharges",
    "bankInterest",
    "dividends",
    "foreignIncome",
    "otherUkIncome"
  )

  def build(state: SessionState, translate: String => String = identity): TaskListModel =
    val visible = QuestionEngine.visibleQuestions(state.questionPack, state.rateCatalog, state.answers, translate)
    val byBase = visible.groupBy(q => YearAnswers.parse(q.id)._1)
    val years = QuestionEngine.selectedTaxYears(state.answers)
    val incomeTypes = QuestionEngine.splitMulti(state.answers.getOrElse("incomeTypes", ""))
    val gainTypes = selectedGainTypes(state.answers, visible)
    val byId = state.questionPack.questions.map(q => q.id -> q).toMap

    def idsOf(baseIds: Seq[String]): Seq[String] =
      baseIds.flatMap(base => byBase.getOrElse(base, Nil).map(_.id))

    def idsForIncome(incomeType: String, year: Option[String]): Seq[String] =
      visible
        .filter: q =>
          year.forall(y => q.taxYear.contains(y)) &&
            belongsToLane(q.template, byId, s"incomeTypes:$incomeType")
        .map(_.id)

    def idsForGain(gainType: String, year: Option[String]): Seq[String] =
      visible
        .filter: q =>
          year.forall(y => q.taxYear.contains(y)) &&
            (
              (gainType == "capitalGains" && q.template.id == "capitalGainTypes") ||
                belongsToLane(q.template, byId, s"capitalGainTypes:$gainType")
            )
        .map(_.id)

    val aboutDisclosureQs = idsOf(AboutDisclosureIds)
    val aboutYouQs = idsOf(AboutYouIds)
    val allowanceQs = idsOf(AllowanceIds)

    val prepareComplete =
      isComplete(aboutDisclosureQs, state.answers) &&
        isComplete(aboutYouQs, state.answers) &&
        isComplete(allowanceQs, state.answers)

    val prepareItems = Seq(
      item(
        id = "about-disclosure",
        titleKey = "calculations.taskList.item.aboutDisclosure",
        questionIds = aboutDisclosureQs,
        answers = state.answers,
        locked = false
      ),
      item(
        id = "about-you",
        titleKey = "calculations.taskList.item.aboutYou",
        questionIds = aboutYouQs,
        answers = state.answers,
        locked = !isComplete(aboutDisclosureQs, state.answers)
      ),
      item(
        id = "allowances",
        titleKey = "calculations.taskList.item.allowances",
        questionIds = allowanceQs,
        answers = state.answers,
        locked = !isComplete(aboutYouQs, state.answers)
      )
    ).filter(_.questionIds.nonEmpty)

    val prepareSection = TaskListSection(
      id = "prepare",
      titleKey = "calculations.taskList.section.prepare",
      items = prepareItems
    )

    val incomeSection = TaskListSection(
      id = "income-types",
      titleKey = "calculations.taskList.section.incomeTypes",
      items = IncomeTypeOrder
        .filter(incomeTypes.contains)
        .map: incomeType =>
          item(
            id = s"income-$incomeType",
            titleKey = s"calculations.taskList.item.income.$incomeType",
            questionIds = idsForIncome(incomeType, year = None),
            answers = state.answers,
            locked = !prepareComplete
          )
        .filter(_.questionIds.nonEmpty)
    )

    val gainSection = TaskListSection(
      id = "gain-types",
      titleKey = "calculations.taskList.section.gainTypes",
      items = gainTypes
        .map: gainType =>
          item(
            id = s"gain-$gainType",
            titleKey = s"calculations.taskList.item.gain.$gainType",
            questionIds = idsForGain(gainType, year = None),
            answers = state.answers,
            locked = !prepareComplete
          )
        .filter(_.questionIds.nonEmpty)
    )

    val categoriesComplete =
      incomeSection.items.forall(_.status == TaskStatus.Completed) &&
        gainSection.items.forall(_.status == TaskStatus.Completed)

    val yearSections = years
      .map: year =>
        val declared = idsOf(AlreadyDeclaredIds).filter(_.endsWith(s"__$year"))
        val reliefs = idsOf(ReliefIds).filter(_.endsWith(s"__$year"))
        val yearLocked = !prepareComplete || !categoriesComplete
        val incomeItems = IncomeTypeOrder
          .filter(incomeTypes.contains)
          .map: incomeType =>
            item(
              id = s"year-$year-income-$incomeType",
              titleKey = s"calculations.taskList.item.yearIncome.$incomeType",
              questionIds = idsForIncome(incomeType, Some(year)),
              answers = state.answers,
              locked = yearLocked
            )
          .filter(_.questionIds.nonEmpty)
        val gainItems = gainTypes
          .filterNot(_ == "capitalGains")
          .map: gainType =>
            item(
              id = s"year-$year-gain-$gainType",
              titleKey = s"calculations.taskList.item.yearGain.$gainType",
              questionIds = idsForGain(gainType, Some(year)),
              answers = state.answers,
              locked = yearLocked
            )
          .filter(_.questionIds.nonEmpty)

        TaskListSection(
          id = s"year-$year",
          titleKey = "calculations.taskList.section.year",
          titleArgs = Seq(displayTaxYear(year)),
          items = Seq(
            item(
              id = s"year-$year-declared",
              titleKey = "calculations.taskList.item.alreadyDeclared",
              hintKey = Some("calculations.taskList.item.alreadyDeclared.hint"),
              questionIds = declared,
              answers = state.answers,
              locked = yearLocked
            )
          ).filter(_.questionIds.nonEmpty) ++ incomeItems ++ gainItems ++ Seq(
            item(
              id = s"year-$year-reliefs",
              titleKey = "calculations.taskList.item.reliefs",
              questionIds = reliefs,
              answers = state.answers,
              locked = yearLocked
            )
          ).filter(_.questionIds.nonEmpty)
        )
      .filter(_.items.nonEmpty)

    val yearComplete = yearSections.flatMap(_.items).forall(_.status == TaskStatus.Completed)
    val canFinalise = prepareComplete && (years.isEmpty || yearComplete)

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

    // translate unused but kept for future title resolution in builder
    val _ = translate

    TaskListModel(
      Seq(prepareSection, incomeSection, gainSection).filter(_.items.nonEmpty) ++
        yearSections ++
        Seq(finalSection)
    )

  def startQuestionId(item: TaskListItem, answers: Map[String, String]): Option[String] =
    item.questionIds.find(id => !answers.contains(id)).orElse(item.questionIds.headOption)

  def nextQuestionId(item: TaskListItem, afterId: String): Option[String] =
    val idx = item.questionIds.indexOf(afterId)
    if idx < 0 then item.questionIds.headOption
    else item.questionIds.lift(idx + 1)

  def previousQuestionId(item: TaskListItem, currentId: String): Option[String] =
    val idx = item.questionIds.indexOf(currentId)
    if idx > 0 then Some(item.questionIds(idx - 1)) else None

  private def item(
    id         : String,
    titleKey   : String,
    questionIds: Seq[String],
    answers    : Map[String, String],
    locked     : Boolean,
    hintKey    : Option[String] = None
  ): TaskListItem =
    val status =
      if locked then TaskStatus.CannotStartYet
      else if questionIds.nonEmpty && isComplete(questionIds, answers) then TaskStatus.Completed
      else TaskStatus.NotStarted
    TaskListItem(
      id = id,
      titleKey = titleKey,
      hintKey = hintKey,
      status = status,
      questionIds = questionIds
    )

  private def isComplete(questionIds: Seq[String], answers: Map[String, String]): Boolean =
    questionIds.nonEmpty && questionIds.forall(answers.contains)

  private def selectedGainTypes(answers: Map[String, String], visible: Seq[ResolvedQuestion]): Seq[String] =
    val fromAnswers = QuestionEngine.splitMulti(answers.getOrElse("capitalGainTypes", ""))
    if fromAnswers.nonEmpty then fromAnswers
    else if QuestionEngine.splitMulti(answers.getOrElse("incomeTypes", "")).contains("capitalGains") then
      if visible.exists(_.template.id == "capitalGainTypes") then Seq("capitalGains")
      else Nil
    else Nil

  private def belongsToLane(
    q   : ConfigQuestion,
    byId: Map[String, ConfigQuestion],
    lane: String
  ): Boolean =
    rootLaneKey(q, byId).contains(lane)

  private def rootLaneKey(
    q   : ConfigQuestion,
    byId: Map[String, ConfigQuestion],
    seen: Set[String] = Set.empty
  ): Option[String] =
    if seen.contains(q.id) then None
    else
      q.showIf match
        case None => None
        case Some(rule) if rule.contains.isDefined && Set("incomeTypes", "capitalGainTypes").contains(rule.field) =>
          Some(s"${rule.field}:${rule.contains.get}")
        case Some(rule) =>
          byId.get(rule.field).flatMap(parent => rootLaneKey(parent, byId, seen + q.id))

  private def displayTaxYear(taxYear: String): String =
    taxYear.split('-').toList match
      case start :: end :: Nil if start.length == 4 && end.length == 2 =>
        s"$start to 20$end"
      case _ => taxYear

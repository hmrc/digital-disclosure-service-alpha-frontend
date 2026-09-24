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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.{LiabilityCalculator, QuestionEngine}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  ConfigQuestion,
  QuestionType,
  RateCatalog,
  ShowIf
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.tasklist.TaskListBuilder

/**
  * Groups question templates into the task list's sections and tasks without needing answers,
  * so reviewers see every branch at once. Placement mirrors TaskListBuilder.
  */
object JourneyMapBuilder:

  private val IncomeField = "incomeTypes"
  private val GainField = "capitalGainTypes"

  def build(
    questions: Seq[ConfigQuestion],
    catalog  : RateCatalog,
    spec     : CalculationSpec,
    translate: String => String
  ): JourneyMap =
    val byId = questions.map(q => q.id -> q).toMap
    val usage = calculationUsage(spec, translate)

    def task(id: String, titleKey: String, trigger: Option[String], qs: Seq[ConfigQuestion]): Option[JourneyTask] =
      Option.when(qs.nonEmpty):
        val ids = qs.map(_.id).toSet
        JourneyTask(
          id = id,
          title = translateOr(titleKey, id, translate),
          trigger = trigger,
          questions = qs.map(q => question(q, byId, ids, catalog, usage, translate))
        )

    def fixed(ids: Seq[String]): Seq[ConfigQuestion] =
      questions.filter(q => ids.contains(q.id))

    def lane(key: String): Seq[ConfigQuestion] =
      questions.filter(q => TaskListBuilder.rootLaneKey(q, byId).contains(key))

    val incomeValues = optionValues(byId.get(IncomeField))
    val incomeOrder =
      TaskListBuilder.IncomeTypeOrder.filter(incomeValues.contains) ++
        incomeValues.filterNot(v => TaskListBuilder.IncomeTypeOrder.contains(v) || v == "capitalGains")
    val incomeTasks = incomeOrder.flatMap: value =>
      task(
        id = s"income-$value",
        titleKey = s"calculations.taskList.item.income.$value",
        trigger = Some(tickTrigger(byId, IncomeField, value, translate)),
        qs = lane(s"$IncomeField:$value")
      )

    val gainTasks =
      task(
        id = "gain-capitalGains",
        titleKey = "calculations.taskList.item.gain.capitalGains",
        trigger = Some(tickTrigger(byId, IncomeField, "capitalGains", translate)),
        qs = fixed(Seq(GainField))
      ).toSeq ++ optionValues(byId.get(GainField)).flatMap: value =>
        task(
          id = s"gain-$value",
          titleKey = s"calculations.taskList.item.gain.$value",
          trigger = Some(tickTrigger(byId, GainField, value, translate)),
          qs = lane(s"$GainField:$value")
        )

    val prepareTasks = Seq(
      task("about-disclosure", "calculations.taskList.item.aboutDisclosure", None, fixed(TaskListBuilder.AboutDisclosureIds)),
      task("about-you", "calculations.taskList.item.aboutYou", None, fixed(TaskListBuilder.AboutYouIds)),
      task("allowances", "calculations.taskList.item.allowances", None, fixed(TaskListBuilder.AllowanceIds))
    ).flatten
    val declaredTask = task("year-declared", "calculations.taskList.item.alreadyDeclared", None, fixed(TaskListBuilder.AlreadyDeclaredIds))
    val reliefsTask = task("year-reliefs", "calculations.taskList.item.reliefs", None, fixed(TaskListBuilder.ReliefIds))

    val claimed =
      (prepareTasks ++ incomeTasks ++ gainTasks ++ declaredTask ++ reliefsTask).flatMap(_.questions).map(_.id).toSet
    val (leftoverPerYear, unplacedQs) = questions.filterNot(q => claimed.contains(q.id)).partition(_.perTaxYear)
    val otherTask = task(
      "year-other",
      "calculations.taskList.item.yearOther",
      Some(translate("calculations.graph.journey.yearOther.trigger")),
      leftoverPerYear
    )

    val sections = Seq(
      JourneySection(
        id = "prepare",
        title = translate("calculations.taskList.section.prepare"),
        intro = Some(translate("calculations.graph.journey.prepare.intro")),
        tasks = prepareTasks
      ),
      JourneySection(
        id = "income-types",
        title = translate("calculations.taskList.section.incomeTypes"),
        intro = Some(translate("calculations.graph.journey.income.intro")),
        tasks = incomeTasks
      ),
      JourneySection(
        id = "gain-types",
        title = translate("calculations.taskList.section.gainTypes"),
        intro = Some(translate("calculations.graph.journey.gains.intro")),
        tasks = gainTasks
      ),
      JourneySection(
        id = "year",
        title = translate("calculations.graph.journey.year.title"),
        intro = Some(translate("calculations.graph.journey.year.intro")),
        tasks = (declaredTask ++ otherTask ++ reliefsTask).toSeq
      )
    ).filter(_.tasks.nonEmpty)

    val unplacedIds = unplacedQs.map(_.id).toSet

    JourneyMap(
      sections = sections,
      unplaced = unplacedQs.map(q => question(q, byId, unplacedIds, catalog, usage, translate))
    )

  private def question(
    q        : ConfigQuestion,
    byId     : Map[String, ConfigQuestion],
    taskIds  : Set[String],
    catalog  : RateCatalog,
    usage    : Map[String, Seq[String]],
    translate: String => String
  ): JourneyQuestion =
    val options =
      if q.optionsFromRates then catalog.taxYears
      else q.options.getOrElse(Nil).map(o => CalculationsI18n.text(o.label, translate))
    JourneyQuestion(
      id = q.id,
      title = CalculationsI18n.text(q.title, translate),
      answerType = translate(s"calculations.graph.journey.type.${q.questionType}"),
      options = options,
      perTaxYear = q.perTaxYear,
      condition = q.showIf.filterNot(isLaneRule).map(rule => describeCondition(rule, byId, translate)),
      depth = depth(q, byId, taskIds),
      usedIn = usage.getOrElse(q.id, Nil),
      isAmount = q.questionType == QuestionType.currency
    )

  private def isLaneRule(rule: ShowIf): Boolean =
    rule.contains.isDefined && Set(IncomeField, GainField).contains(rule.field)

  private def depth(q: ConfigQuestion, byId: Map[String, ConfigQuestion], taskIds: Set[String], seen: Set[String] = Set.empty): Int =
    q.showIf.filterNot(isLaneRule).flatMap(r => byId.get(r.field)) match
      case Some(parent) if taskIds.contains(parent.id) && !seen.contains(parent.id) =>
        1 + depth(parent, byId, taskIds, seen + q.id)
      case _ => 0

  private def describeCondition(rule: ShowIf, byId: Map[String, ConfigQuestion], translate: String => String): String =
    val parent = byId.get(rule.field)
    val parentTitle = parent.map(p => CalculationsI18n.text(p.title, translate)).getOrElse(rule.field)
    def valueLabel(value: String): String =
      parent
        .flatMap(_.options)
        .flatMap(_.find(_.value == value))
        .map(o => CalculationsI18n.text(o.label, translate))
        .getOrElse(if parent.exists(_.questionType == QuestionType.yesNo) then value.capitalize else value)
    val (key, value) =
      rule.equals.map("is" -> _)
        .orElse(rule.contains.map("includes" -> _))
        .orElse(rule.notEquals.map("isNot" -> _))
        .getOrElse("is" -> "")
    translate(s"calculations.graph.journey.condition.$key")
      .replace("{0}", parentTitle)
      .replace("{1}", valueLabel(value))

  private def tickTrigger(byId: Map[String, ConfigQuestion], field: String, value: String, translate: String => String): String =
    val label = byId
      .get(field)
      .flatMap(_.options)
      .flatMap(_.find(_.value == value))
      .map(o => CalculationsI18n.text(o.label, translate))
      .getOrElse(value)
    translate("calculations.graph.journey.trigger").replace("{0}", label)

  private def optionValues(q: Option[ConfigQuestion]): Seq[String] =
    q.flatMap(_.options).getOrElse(Nil).map(_.value)

  /** Question id → the calculation lines that read its answer. */
  private def calculationUsage(spec: CalculationSpec, translate: String => String): Map[String, Seq[String]] =
    val fromIncome = spec.incomeComponents.flatMap: c =>
      val label = CalculationsI18n.text(c.label, translate)
      c.field.map(_ -> label).toSeq ++
        c.grossField.map(_ -> s"$label (${translate("calculations.graph.journey.usage.gross")})") ++
        Seq(c.deductField, c.altDeductField).flatten.map(_ -> s"$label (${translate("calculations.graph.journey.usage.deduction")})")
    val fromAllowances = spec.allowances.flatMap: a =>
      a.when.map(_.field -> s"${CalculationsI18n.text(a.label, translate)} (${translate("calculations.graph.journey.usage.condition")})")
    val fromTaxPaid = LiabilityCalculator.TaxPaidFields.map(_ -> translate("Tax_already_paid"))
    val fromYears = Seq(QuestionEngine.TaxYearsQuestionId -> translate("calculations.graph.journey.usage.years"))
    (fromIncome ++ fromAllowances ++ fromTaxPaid ++ fromYears)
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).distinct)
      .toMap

  private def translateOr(key: String, fallback: String, translate: String => String): String =
    val text = translate(key)
    if text == key then fallback else text

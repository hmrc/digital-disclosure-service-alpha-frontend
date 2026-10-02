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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  ConfigQuestion,
  Lane,
  QuestionPack,
  QuestionType,
  RateCatalog,
  ShowIf,
  TaskConfig
}

/**
  * Groups question templates into the task list's sections and tasks without needing answers, so reviewers see
  * every branch at once. Uses the same `taskList` layout as TaskListBuilder; tax-year sections are shown once.
  */
object JourneyMapBuilder:

  def build(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    spec     : CalculationSpec,
    translate: String => String
  ): JourneyMap =
    val layout = pack.taskList
    val byId = pack.questions.map(q => q.id -> q).toMap
    val usage = calculationUsage(spec, translate)
    val isLane = Lane.isLaneRule(_, pack)

    def task(id: String, titleKey: String, trigger: Option[String], qs: Seq[ConfigQuestion]): Option[JourneyTask] =
      Option.when(qs.nonEmpty):
        val ids = qs.map(_.id).toSet
        JourneyTask(
          id = id,
          title = translateOr(titleKey, id, translate),
          trigger = trigger,
          questions = qs.map(q => question(q, byId, ids, isLane, catalog, usage, translate))
        )

    def fixed(t: TaskConfig, idPrefix: String = ""): Option[JourneyTask] =
      val qs = t.questions.flatMap(byId.get)
      val trigger = qs.headOption.flatMap(_.showIf).filter(isLane).map(r => tickTrigger(byId, r.field, r.contains.getOrElse(""), translate))
      task(idPrefix + t.id, t.title, trigger, qs)

    val sections = layout.sections.map: section =>
      val categoryTasks = section.forEachSelected.toSeq.flatMap: category =>
        category.tasks.flatMap: t =>
          task(
            id = s"${section.id}-${t.value}",
            titleKey = t.title,
            trigger = Some(tickTrigger(byId, category.question, t.value, translate)),
            qs = pack.questions.filter(q => Lane.of(q, pack).contains(Lane(category.question, t.value)))
          )
      JourneySection(
        id = section.id,
        title = translate(section.title),
        intro = section.intro.map(translate),
        tasks = section.tasks.flatMap(fixed(_)) ++ categoryTasks
      )

    val claimed = (sections.flatMap(_.tasks) ++ layout.eachYear.toSeq.flatMap(_.fixedTasks).flatMap(fixed(_)))
      .flatMap(_.questions)
      .map(_.id)
      .toSet
    val (leftoverPerYear, unplacedQs) = pack.questions.filterNot(q => claimed.contains(q.id)).partition(_.perTaxYear)

    val yearSection = layout.eachYear.map: eachYear =>
      val leftover = eachYear.leftover.flatMap: t =>
        task(s"year-${t.id}", t.title, Some(translate("calculations.graph.journey.yearOther.trigger")), leftoverPerYear)
      JourneySection(
        id = "year",
        title = translate("calculations.graph.journey.year.title"),
        intro = eachYear.intro.map(translate),
        tasks = eachYear.before.flatMap(fixed(_, "year-")) ++ leftover ++ eachYear.after.flatMap(fixed(_, "year-"))
      )

    val unplacedIds = unplacedQs.map(_.id).toSet

    JourneyMap(
      sections = (sections ++ yearSection).filter(_.tasks.nonEmpty),
      unplaced = unplacedQs.map(q => question(q, byId, unplacedIds, isLane, catalog, usage, translate))
    )

  private def question(
    q        : ConfigQuestion,
    byId     : Map[String, ConfigQuestion],
    taskIds  : Set[String],
    isLane   : ShowIf => Boolean,
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
      condition = q.showIf.filterNot(isLane).map(rule => describeCondition(rule, byId, translate)),
      depth = depth(q, byId, taskIds, isLane),
      usedIn = usage.getOrElse(q.id, Nil),
      isAmount = q.questionType == QuestionType.currency
    )

  private def depth(
    q      : ConfigQuestion,
    byId   : Map[String, ConfigQuestion],
    taskIds: Set[String],
    isLane : ShowIf => Boolean,
    seen   : Set[String] = Set.empty
  ): Int =
    q.showIf.filterNot(isLane).flatMap(r => byId.get(r.field)) match
      case Some(parent) if taskIds.contains(parent.id) && !seen.contains(parent.id) =>
        1 + depth(parent, byId, taskIds, isLane, seen + q.id)
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

  /** Question id → the calculation lines that read its answer. */
  private def calculationUsage(spec: CalculationSpec, translate: String => String): Map[String, Seq[String]] =
    val fromIncome = spec.incomeComponents.flatMap: c =>
      val label = CalculationsI18n.text(c.label, translate)
      c.field.map(_ -> label).toSeq ++
        c.grossField.map(_ -> s"$label (${translate("calculations.graph.journey.usage.gross")})") ++
        Seq(c.deductField, c.altDeductField).flatten.map(_ -> s"$label (${translate("calculations.graph.journey.usage.deduction")})")
    val fromAllowances = spec.allowances.flatMap: a =>
      a.when.map(_.field -> s"${CalculationsI18n.text(a.label, translate)} (${translate("calculations.graph.journey.usage.condition")})")
    val fromTaxPaid = spec.taxPaidFields.map(_ -> translate("Tax_already_paid"))
    val fromYears = Seq(QuestionPack.TaxYearsQuestionId -> translate("calculations.graph.journey.usage.years"))
    (fromIncome ++ fromAllowances ++ fromTaxPaid ++ fromYears)
      .groupBy(_._1)
      .view
      .mapValues(_.map(_._2).distinct)
      .toMap

  private def translateOr(key: String, fallback: String, translate: String => String): String =
    val text = translate(key)
    if text == key then fallback else text

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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  CalculationSpec,
  ConfigQuestion,
  IncomeComponentKind,
  QuestionPack,
  SessionState,
  ShowIf
}

object GraphBuilder:

  def build(state: SessionState, translate: String => String = identity): GraphModel =
    val selected = QuestionEngine.selectedTaxYears(state.answers)
    val screenList = screens(state.questionPack, translate)
    GraphModel(
      architectureMermaid = architectureMermaid(state),
      journeyMermaid = journeyMermaid(state.questionPack, translate),
      calculationMermaid = calculationMermaid(state.calculationSpec, translate),
      screens = screenList,
      journeyBranches = branchMap(state.questionPack, screenList, translate),
      calcSteps = calcSteps(state.calculationSpec, translate),
      examples = ExampleJourneys.build(state, translate),
      rateYears = state.rateCatalog.taxYears,
      catalogVersion = state.rateCatalog.version,
      calculationVersion = state.calculationSpec.version,
      selectedYears = selected
    )

  private def screens(pack: QuestionPack, translate: String => String): Seq[GraphScreen] =
    pack.questions.map: q =>
      GraphScreen(
        id = q.id,
        title = CalculationsI18n.text(q.title, translate),
        screenType = q.questionType.toString,
        perTaxYear = q.perTaxYear,
        condition = q.showIf.map(describeShowIf),
        feeds = q.feeds
      )

  /**
    * Group conditional screens into parallel lanes. A screen belongs to the
    * incomeTypes / capitalGainTypes lane when its showIf (or an ancestor’s)
    * selects that category; other showIfs become their own lane.
    */
  private def branchMap(
    pack     : QuestionPack,
    screenList: Seq[GraphScreen],
    translate: String => String
  ): JourneyBranchMap =
    val byId = pack.questions.map(q => q.id -> q).toMap
    val paired = pack.questions.zip(screenList)

    val trunk = paired.collect { case (q, s) if rootLaneKey(q, byId).isEmpty => s }

    val ordered = scala.collection.mutable.LinkedHashMap.empty[String, scala.collection.mutable.ListBuffer[GraphScreen]]
    paired.foreach:
      case (q, s) =>
        rootLaneKey(q, byId).foreach: key =>
          ordered.getOrElseUpdate(key, scala.collection.mutable.ListBuffer.empty) += s

    val branches = ordered.map: (key, buf) =>
      JourneyBranch(id = key, label = branchLabel(key, pack, translate), screens = buf.toSeq)
    .toSeq

    JourneyBranchMap(trunk = trunk, branches = branches)

  /** Lane key for a question: income/CGT category, nested ancestor, or standalone condition. */
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
          byId.get(rule.field).flatMap(parent => rootLaneKey(parent, byId, seen + q.id)) match
            case some @ Some(_) => some
            case None           => Some(s"cond:${describeShowIf(rule)}")

  private def branchLabel(key: String, pack: QuestionPack, translate: String => String): String =
    key.split(":", 2) match
      case Array("incomeTypes", value) =>
        optionLabel(pack, "incomeTypes", value, translate)
          .map(l => s"When incomeTypes includes $value — $l")
          .getOrElse(s"When incomeTypes includes $value")
      case Array("capitalGainTypes", value) =>
        optionLabel(pack, "capitalGainTypes", value, translate)
          .map(l => s"When capitalGainTypes includes $value — $l")
          .getOrElse(s"When capitalGainTypes includes $value")
      case Array("cond", rest) => s"When $rest"
      case _                   => key

  private def optionLabel(
    pack     : QuestionPack,
    questionId: String,
    value    : String,
    translate: String => String
  ): Option[String] =
    pack.questions
      .find(_.id == questionId)
      .flatMap(_.options)
      .flatMap(_.find(_.value == value))
      .map(opt => CalculationsI18n.text(opt.label, translate))

  private def calcSteps(spec: CalculationSpec, translate: String => String): Seq[GraphCalcStep] =
    val income = spec.incomeComponents.map: c =>
      val (operation, detail) = c.kind match
        case IncomeComponentKind.net =>
          val gross = c.grossField.getOrElse("?")
          val deductions = Seq(c.deductField, c.altDeductField).flatten
          val deductionFields =
            if deductions.isEmpty then "no deduction fields"
            else deductions.mkString(" and ")
          (
            "Subtract deductions from gross income. If the result is less than £0, use £0 instead.",
            s"Use the gross amount from $gross and deductions from $deductionFields."
          )
        case IncomeComponentKind.amount =>
          val field = c.field.getOrElse(c.id)
          ("Use the amount entered for this question.", s"Take the answered amount from $field.")
      GraphCalcStep(c.id, CalculationsI18n.text(c.label, translate), operation, detail)

    val sumIncome = GraphCalcStep(
      "totalIncome",
      translate("Total_income"),
      "Add all income amounts together.",
      "Add every income component for the tax year"
    )

    val allowances = spec.allowances.map: a =>
      val label = CalculationsI18n.text(a.label, translate)
      val operation = a.kind match
        case AllowanceKind.personalAllowance =>
          if a.taper.isDefined then
            s"Use the $label for the tax year, reducing it when income is above the taper threshold."
          else s"Use the $label for the tax year."
        case AllowanceKind.conditionalAmount =>
          if a.when.isDefined then
            s"Use the $label when the answer meets the condition. Otherwise, use £0."
          else s"Use the $label for the tax year."
      val detail =
        s"The amount comes from ${a.rateKey} in the rate catalogue" +
          a.taper.map(t => s"; reduce by ${t.reduceBy} for every ${t.forEvery} over ${t.thresholdRateKey}").getOrElse("") +
          a.when.map(w => s"; only when ${describeShowIf(w)}").getOrElse("")
      GraphCalcStep(a.id, label, operation, detail)

    val taxable = GraphCalcStep(
      "taxableIncome",
      translate("Taxable_income"),
      "Subtract all allowances from total income. If the result is less than £0, use £0 instead.",
      "Taxable income cannot be a negative amount."
    )

    val bands = spec.tax.bands.map: b =>
      val label = CalculationsI18n.text(b.label, translate)
      val operation = s"Apply the $label to the taxable income in this band."
      val detail =
        s"Multiply the income in this band by ${b.rateKey} from the rate catalogue" +
          b.upToRateKey.map(k => s" (cap from $k)").getOrElse(" (no upper cap)")
      GraphCalcStep(b.rateKey, label, operation, detail)

    val taxDue = GraphCalcStep(
      "taxDue",
      translate("Estimated_income_tax"),
      s"Add the tax from each band and round the total to ${spec.tax.scale} decimal places.",
      s"The configured rounding method is ${spec.tax.rounding}."
    )

    income ++ Seq(sumIncome) ++ allowances ++ Seq(taxable) ++ bands ++ Seq(taxDue)

  private def architectureMermaid(state: SessionState): String =
    s"""flowchart TD
  |  subgraph stage1 [Stage 1 - Build the journey]
  |    questions["Question pack\\n${escape(state.questionPack.id)}"]
  |    ratesJourney["Rate catalogue\\n${escape(state.rateCatalog.version)}"]
  |    journey["Generated GDS screens"]
  |    answers["User answers"]
  |    questions --> journey
  |    ratesJourney --> journey
  |    journey --> answers
  |  end
  |
  |  subgraph stage2 [Stage 2 - Calculate]
  |    answers2["User answers"]
  |    ratesCalc["Rate catalogue\\namounts by year"]
  |    calc["Calculation spec\\n${escape(state.calculationSpec.id)} v${escape(state.calculationSpec.version)}"]
  |    engine["Calculations"]
  |    result["Liability estimate"]
  |    answers2 --> engine
  |    ratesCalc --> engine
  |    calc --> engine
  |    engine --> result
  |  end
  |
  |  answers --> answers2""".stripMargin

  private def journeyMermaid(pack: QuestionPack, translate: String => String): String =
    val screenList = screens(pack, translate)
    val map = branchMap(pack, screenList, translate)
    val byId = pack.questions.map(q => q.id -> q).toMap

    val trunkQs = pack.questions.filter(q => rootLaneKey(q, byId).isEmpty)
    val trunkNodes = trunkQs.map: q =>
      s"""  ${nodeId(q.id)}["${escape(shortTitle(q, translate))}"]"""
    val trunkEdges =
      if trunkQs.isEmpty then Seq.empty
      else
        Seq(s"  start([Start]) --> ${nodeId(trunkQs.head.id)}") ++
          trunkQs.sliding(2).toSeq.collect { case Seq(a, b) => s"  ${nodeId(a.id)} --> ${nodeId(b.id)}" }

    val hubId =
      trunkQs.find(_.id == "incomeTypes").orElse(trunkQs.headOption).map(q => nodeId(q.id))

    val branchBlocks = map.branches.flatMap: branch =>
      val qs = pack.questions.filter(q => rootLaneKey(q, byId).contains(branch.id))
      if qs.isEmpty then Seq.empty
      else
        val laneId = nodeId(s"lane_${branch.id}")
        val shortLabel =
          branch.label.split(" — ").lastOption.getOrElse(branch.label)
        val header = s"""  $laneId(["${escape(shortLabel.take(48))}"])"""
        val nodes = qs.map: q =>
          val title =
            if q.showIf.exists(r => !Set("incomeTypes", "capitalGainTypes").contains(r.field)) then
              s"${shortTitle(q, translate)}\\n(${escape(describeShowIf(q.showIf.get))})"
            else shortTitle(q, translate)
          s"""  ${nodeId(q.id)}["${escape(title)}"]"""
        val into = hubId.toSeq.map(h => s"  $h --> $laneId")
        val chain =
          Seq(s"  $laneId --> ${nodeId(qs.head.id)}") ++
            qs.sliding(2).toSeq.collect { case Seq(a, b) =>
              val label = b.showIf
                .filter(r => !Set("incomeTypes", "capitalGainTypes").contains(r.field))
                .map(r => s"|${escape(describeShowIf(r))}|")
                .getOrElse("")
              s"  ${nodeId(a.id)} -->$label ${nodeId(b.id)}"
            }
        val out = Seq(s"  ${nodeId(qs.last.id)} --> cya")
        Seq(header) ++ nodes ++ into ++ chain ++ out

    val end =
      if trunkQs.nonEmpty then
        Seq(s"  ${nodeId(trunkQs.last.id)} --> cya([Check your answers])", "  cya --> result([Calculate])")
      else
        Seq("  start([Start]) --> cya([Check your answers])", "  cya --> result([Calculate])")

    (Seq("flowchart TD") ++ trunkNodes ++ trunkEdges ++ branchBlocks ++ end).mkString("\n")

  private def calculationMermaid(spec: CalculationSpec, translate: String => String): String =
    val incomeNodes = spec.incomeComponents.map: c =>
      val op = c.kind match
        case IncomeComponentKind.net    => "gross income minus deductions; use zero if negative"
        case IncomeComponentKind.amount => "use the entered amount"
      s"""  ${nodeId(c.id)}["${escape(CalculationsI18n.text(c.label, translate))}\\n$op"]"""
    val incomeEdges = spec.incomeComponents.map: c =>
      s"  ${nodeId(c.id)} --> totalIncome"

    val allowanceNodes = spec.allowances.map: a =>
      val op = a.kind match
        case AllowanceKind.personalAllowance => "use the tax-year allowance, with any reduction"
        case AllowanceKind.conditionalAmount => "use the allowance when the condition is met"
      s"""  ${nodeId(a.id)}["${escape(CalculationsI18n.text(a.label, translate))}\\n$op"]"""
    val allowanceEdges = spec.allowances.map: a =>
      s"  ${nodeId(a.id)} --> taxable"

    val bandNodes = spec.tax.bands.zipWithIndex.map: (b, i) =>
      val nid = s"band$i"
      s"""  $nid["${escape(CalculationsI18n.text(b.label, translate))}\\napply rate to income in this band"]"""
    val bandChain =
      if spec.tax.bands.isEmpty then Seq.empty
      else
        val ids = spec.tax.bands.indices.map(i => s"band$i")
        Seq(s"  taxable --> ${ids.head}") ++
          ids.sliding(2).toSeq.collect { case Seq(a, b) => s"  $a --> $b" } ++
          Seq(s"  ${ids.last} --> taxDue")

    (
      Seq(
        "flowchart TD",
        s"""  totalIncome["${escape(translate("Total_income"))}\\nadd all income amounts"]""",
        s"""  taxable["${escape(translate("Taxable_income"))}\\nsubtract allowances; use zero if negative"]""",
        s"""  taxDue(["${escape(translate("Estimated_income_tax"))}\\nadd tax from each band"])"""
      ) ++ incomeNodes ++ incomeEdges ++
        Seq("  totalIncome --> taxable") ++
        allowanceNodes ++ allowanceEdges ++
        bandNodes ++ bandChain
    ).mkString("\n")

  private def describeShowIf(rule: ShowIf): String =
    rule.equals.map(v => s"${rule.field}=$v")
      .orElse(rule.contains.map(v => s"${rule.field} contains $v"))
      .orElse(rule.notEquals.map(v => s"${rule.field}≠$v"))
      .getOrElse(rule.field)

  private def shortTitle(q: ConfigQuestion, translate: String => String): String =
    val t = CalculationsI18n.text(q.title, translate)
    if t.length <= 42 then t else t.take(39) + "..."

  private def nodeId(raw: String): String =
    "n_" + raw.replaceAll("[^A-Za-z0-9]", "_")

  private def escape(raw: String): String =
    raw
      .replace("\\", "\\\\")
      .replace("\"", "'")
      .replace("\n", " ")
      .replace("<", "")
      .replace(">", "")

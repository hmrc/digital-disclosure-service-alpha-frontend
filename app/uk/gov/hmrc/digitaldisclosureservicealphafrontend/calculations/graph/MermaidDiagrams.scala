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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.graph.CalculationStagesBuilder.{describeCondition, rateLabel}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  CalculationSpec,
  ConfigQuestion,
  IncomeComponentKind,
  QuestionPack,
  RateCatalog,
  SessionState,
  ShowIf
}

/** Mermaid flowchart source for the architecture, the question journey and the calculation. */
object MermaidDiagrams:

  def architecture(state: SessionState): String =
    val hipLines =
      if state.option.fetchesDownstreamRates then
        """    hipGet["GET existing MTD calc\nHIP 5294"]
    |    hipGet --> ratesJourney
    |""".stripMargin
      else ""
    s"""flowchart TD
  |  subgraph stage1 [Stage 1 - Build the journey]
  |    questions["Question pack\\n${escape(state.questionPack.id)}"]
  |    ratesJourney["Rate catalogue\\n${escape(state.rateCatalog.version)}"]
  |$hipLines    journey["Generated GDS screens"]
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

  /** Always-asked questions form a trunk; conditional questions hang off it in one lane per condition. */
  def journey(pack: QuestionPack, translate: String => String): String =
    val byId = pack.questions.map(q => q.id -> q).toMap
    val categoryFields = pack.taskList.categoryFields
    val laneOf = pack.questions.map(q => q.id -> laneKey(q, byId, categoryFields)).toMap

    val trunk = pack.questions.filter(q => laneOf(q.id).isEmpty)
    val trunkNodes = trunk.map(q => s"""  ${nodeId(q.id)}["${escape(shortTitle(q, translate))}"]""")
    val trunkEdges =
      if trunk.isEmpty then Seq.empty
      else
        Seq(s"  start([Start]) --> ${nodeId(trunk.head.id)}") ++
          trunk.sliding(2).toSeq.collect { case Seq(a, b) => s"  ${nodeId(a.id)} --> ${nodeId(b.id)}" }

    val hub = trunk.find(q => categoryFields.contains(q.id)).orElse(trunk.headOption).map(q => nodeId(q.id))

    val lanes = pack.questions.flatMap(q => laneOf(q.id)).distinct.flatMap: lane =>
      val qs = pack.questions.filter(q => laneOf(q.id).contains(lane))
      val laneId = nodeId(s"lane_$lane")
      val header = s"""  $laneId(["${escape(laneLabel(lane, pack, categoryFields, translate).take(48))}"])"""
      val nodes = qs.map: q =>
        val title = followUpRule(q, categoryFields).fold(shortTitle(q, translate))(r => s"${shortTitle(q, translate)}\\n(${escape(describeShowIf(r))})")
        s"""  ${nodeId(q.id)}["${escape(title)}"]"""
      val chain =
        Seq(s"  $laneId --> ${nodeId(qs.head.id)}") ++
          qs.sliding(2).toSeq.collect { case Seq(a, b) =>
            val label = followUpRule(b, categoryFields).map(r => s"|${escape(describeShowIf(r))}|").getOrElse("")
            s"  ${nodeId(a.id)} -->$label ${nodeId(b.id)}"
          }
      Seq(header) ++ nodes ++ hub.toSeq.map(h => s"  $h --> $laneId") ++ chain ++ Seq(s"  ${nodeId(qs.last.id)} --> cya")

    val end =
      Seq(s"  ${trunk.lastOption.fold("start([Start])")(q => nodeId(q.id))} --> cya([Check your answers])", "  cya --> result([Calculate])")

    (Seq("flowchart TD") ++ trunkNodes ++ trunkEdges ++ lanes ++ end).mkString("\n")

  def calculation(spec: CalculationSpec, catalog: RateCatalog, translate: String => String): String =
    val incomeNodes = spec.incomeComponents.map: c =>
      val op = c.kind match
        case IncomeComponentKind.net    => "gross minus deductions"
        case IncomeComponentKind.amount => "amount entered"
      s"""    ${nodeId(c.id)}["${escape(CalculationsI18n.text(c.label, translate))}\\n$op"]"""

    val allowanceNodes = spec.allowances.map: a =>
      val op = a.kind match
        case AllowanceKind.personalAllowance => if a.taper.isDefined then "tapered above threshold" else "full amount"
        case AllowanceKind.conditionalAmount => a.when.map(w => s"only when ${describeCondition(w)}").getOrElse("full amount")
      s"""    ${nodeId(a.id)}["${escape(CalculationsI18n.text(a.label, translate))}\\n${escape(op)}"]"""

    val bandNodes = spec.tax.bands.zipWithIndex.map: (b, i) =>
      s"""    band$i["${escape(CalculationsI18n.text(b.label, translate))}\\n${escape(rateLabel(catalog, translate)(b.rateKey))}"]"""

    def group(id: String, title: String, nodes: Seq[String]): Seq[String] =
      if nodes.isEmpty then Seq.empty
      else Seq(s"""  subgraph $id ["${escape(title)}"]""") ++ nodes ++ Seq("  end")

    (
      Seq("flowchart TD") ++
        group("income", translate("calculations.graph.stage.income"), incomeNodes) ++
        Seq(s"""  totalIncome["${escape(translate("Total_income"))}"]""", "  income --> totalIncome") ++
        group("allowances", translate("calculations.graph.stage.allowances"), allowanceNodes) ++
        Seq(s"""  taxable["${escape(translate("Taxable_income"))}\\nnot below zero"]""", "  totalIncome --> taxable") ++
        Option.when(allowanceNodes.nonEmpty)("  allowances -->|minus| taxable").toSeq ++
        group("bands", translate("calculations.graph.stage.bands"), bandNodes) ++
        Seq(s"""  grossTax["${escape(translate("calculations.graph.term.taxBeforePayments"))}"]""") ++
        (if bandNodes.isEmpty then Seq("  taxable --> grossTax") else Seq("  taxable --> bands", "  bands -->|added| grossTax")) ++
        Seq(
          s"""  taxPaid["${escape(translate("Tax_already_paid"))}"]""",
          s"""  taxDue(["${escape(translate("Estimated_income_tax"))}\\nnot below zero"])""",
          "  grossTax --> taxDue",
          "  taxPaid -->|minus| taxDue"
        )
    ).mkString("\n")

  /** An income or gain category (`incomeTypes:dividends`), inherited from an ancestor, or the question's own condition. */
  private def laneKey(
    q             : ConfigQuestion,
    byId          : Map[String, ConfigQuestion],
    categoryFields: Set[String],
    seen          : Set[String] = Set.empty
  ): Option[String] =
    if seen.contains(q.id) then None
    else
      q.showIf.flatMap: rule =>
        rule.contains.filter(_ => categoryFields.contains(rule.field)).map(value => s"${rule.field}:$value").orElse(
          byId.get(rule.field).flatMap(parent => laneKey(parent, byId, categoryFields, seen + q.id)).orElse(Some(s"cond:${describeShowIf(rule)}"))
        )

  private def laneLabel(lane: String, pack: QuestionPack, categoryFields: Set[String], translate: String => String): String =
    lane.split(":", 2) match
      case Array(field, value) if categoryFields.contains(field) =>
        pack.questions
          .find(_.id == field)
          .flatMap(_.options)
          .flatMap(_.find(_.value == value))
          .map(option => CalculationsI18n.text(option.label, translate))
          .getOrElse(s"When $field includes $value")
      case Array("cond", condition) => s"When $condition"
      case _                        => lane

  private def followUpRule(q: ConfigQuestion, categoryFields: Set[String]): Option[ShowIf] =
    q.showIf.filterNot(r => categoryFields.contains(r.field))

  private def describeShowIf(rule: ShowIf): String =
    rule.equals.map(v => s"${rule.field}=$v")
      .orElse(rule.contains.map(v => s"${rule.field} contains $v"))
      .orElse(rule.notEquals.map(v => s"${rule.field}≠$v"))
      .getOrElse(rule.field)

  private def shortTitle(q: ConfigQuestion, translate: String => String): String =
    val title = CalculationsI18n.text(q.title, translate)
    if title.length <= 42 then title else title.take(39) + "..."

  private def nodeId(raw: String): String =
    "n_" + raw.replaceAll("[^A-Za-z0-9]", "_")

  private def escape(raw: String): String =
    raw.replace("\\", "\\\\").replace("\"", "'").replace("\n", " ").replace("<", "").replace(">", "")

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
  AllowanceKind,
  AllowanceRule,
  ArchitectureOption,
  CalculationSpec,
  ConfigQuestion,
  IncomeComponent,
  IncomeComponentKind,
  QuestionPack,
  RateCatalog,
  SessionState,
  ShowIf
}

import scala.math.BigDecimal.RoundingMode

object GraphBuilder:

  def build(state: SessionState, translate: String => String = identity): GraphModel =
    val selected = QuestionEngine.selectedTaxYears(state.answers)
    GraphModel(
      architectureMermaid = architectureMermaid(state),
      journeyMermaid = journeyMermaid(state.questionPack, translate),
      calculationMermaid = calculationMermaid(state.calculationSpec, translate),
      journeyMap = JourneyMapBuilder.build(state.questionPack.questions, state.rateCatalog, state.calculationSpec, translate),
      calcScope = state.calculationSpec.description,
      calcStages = calcStages(state.calculationSpec, translate),
      rateTable = rateTable(state.rateCatalog, translate),
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

  /** The calculation read as five stages, each with a one-line formula and the rules behind it. */
  private def calcStages(spec: CalculationSpec, translate: String => String): Seq[CalcStage] =
    val totalIncome = translate("Total_income")
    val taxableIncome = translate("Taxable_income")
    val allowancesTerm = translate("calculations.graph.term.allowances")
    val taxBeforePayments = translate("calculations.graph.term.taxBeforePayments")
    val taxPaid = translate("Tax_already_paid")
    val taxDue = translate("Estimated_income_tax")
    val allowanceLabels = spec.allowances.map(a => CalculationsI18n.text(a.label, translate))
    val bandLabels = spec.tax.bands.map(b => CalculationsI18n.text(b.label, translate))

    Seq(
      CalcStage(
        id = "income",
        title = translate("calculations.graph.stage.income"),
        formula = s"$totalIncome = the ${spec.incomeComponents.size} income sources below, added together",
        rules = incomeRules(spec, translate)
      ),
      CalcStage(
        id = "allowances",
        title = translate("calculations.graph.stage.allowances"),
        formula =
          if allowanceLabels.isEmpty then s"$allowancesTerm = £0"
          else s"$allowancesTerm = ${allowanceLabels.mkString(" + ")}",
        rules = spec.allowances.map(allowanceRule(_, translate))
      ),
      CalcStage(
        id = "taxable",
        title = translate("calculations.graph.stage.taxable"),
        formula = s"$taxableIncome = $totalIncome − $allowancesTerm, not below £0",
        rules = Seq.empty
      ),
      CalcStage(
        id = "bands",
        title = translate("calculations.graph.stage.bands"),
        formula = s"$taxBeforePayments = ${bandLabels.map(l => s"$l tax").mkString(" + ")}",
        rules = spec.tax.bands.zipWithIndex.map: (b, i) =>
          val slice = (i, b.upToRateKey) match
            case (0, Some(cap)) => s"Taxable income up to the ${rateLabel(cap, translate)}"
            case (_, Some(cap)) => s"Taxable income above the previous band, up to the ${rateLabel(cap, translate)}"
            case (0, None)      => "All taxable income"
            case (_, None)      => "All taxable income above the previous band"
          CalcRule(
            label = CalculationsI18n.text(b.label, translate),
            rule = s"$slice, charged at the ${rateLabel(b.rateKey, translate)}.",
            source = Some((Seq(b.rateKey) ++ b.upToRateKey).mkString(", "))
          )
      ),
      CalcStage(
        id = "taxDue",
        title = translate("calculations.graph.stage.taxDue"),
        formula = s"$taxDue = $taxBeforePayments − $taxPaid, not below £0",
        rules = Seq(
          CalcRule(
            label = taxPaid,
            rule = "Tax already paid and tax taken off at source, added together.",
            source = Some(LiabilityCalculator.TaxPaidFields.mkString(" + "))
          ),
          CalcRule(
            label = translate("calculations.graph.term.rounding"),
            rule = s"Tax is rounded to ${spec.tax.scale} decimal places, ${roundingDescription(spec.tax.rounding)}.",
            source = Some(s"tax.scale = ${spec.tax.scale}, tax.rounding = ${spec.tax.rounding}")
          )
        )
      )
    )

  /** Calculated components get a row each; components taken as entered share one row. */
  private def incomeRules(spec: CalculationSpec, translate: String => String): Seq[CalcRule] =
    val (entered, calculated) =
      spec.incomeComponents.partition(c => c.kind == IncomeComponentKind.amount && c.floorAtZero)
    val enteredRule = Option.when(entered.nonEmpty):
      CalcRule(
        label = s"${entered.size} ${translate("calculations.graph.calc.enteredSources")}",
        rule = "Amount entered, not below £0",
        items = entered.map(c => CalculationsI18n.text(c.label, translate) -> c.field.getOrElse(c.id))
      )
    calculated.map(incomeRule(_, translate)) ++ enteredRule

  private def incomeRule(c: IncomeComponent, translate: String => String): CalcRule =
    val floor = if c.floorAtZero then ", not below £0" else ""
    val label = CalculationsI18n.text(c.label, translate)
    c.kind match
      case IncomeComponentKind.amount =>
        CalcRule(label, s"Amount entered$floor", Some(c.field.getOrElse(c.id)))
      case IncomeComponentKind.net =>
        val gross = c.grossField.getOrElse(c.id)
        Seq(c.deductField, c.altDeductField).flatten match
          case Nil      => CalcRule(label, s"Gross amount entered$floor", Some(gross))
          case Seq(one) => CalcRule(label, s"Gross amount minus deductions$floor", Some(s"$gross − $one"))
          case many     => CalcRule(label, s"Gross amount minus deductions$floor", Some(s"$gross − (${many.mkString(" + ")})"))

  private def allowanceRule(a: AllowanceRule, translate: String => String): CalcRule =
    val amount = rateLabel(a.rateKey, translate).capitalize + " for the tax year"
    val taper = a.taper.map: t =>
      s" Reduced by ${wholePounds(t.reduceBy)} for every ${wholePounds(t.forEvery)} of total income " +
        s"above the ${rateLabel(t.thresholdRateKey, translate)}, down to £0."
    val condition = a.when.map(w => s" Only given when ${describeCondition(w)}; otherwise £0.")
    CalcRule(
      label = CalculationsI18n.text(a.label, translate),
      rule = amount + "." + taper.getOrElse("") + condition.getOrElse(""),
      source = Some((Seq(a.rateKey) ++ a.taper.map(_.thresholdRateKey) ++ a.when.map(_.field)).mkString(", "))
    )

  private val RateKeys: Seq[String] =
    Seq("personalAllowance", "taperThreshold", "blindPersonsAllowance", "basicRateBand", "basicRate", "higherRate")

  private def rateTable(catalog: RateCatalog, translate: String => String): RateTable =
    val values = catalog.years.map(LiabilityCalculator.rateValues)
    RateTable(
      years = catalog.taxYears,
      rows =
        RateTableRow(translate("calculations.graph.rates.version"), catalog.years.map(_.version)) +:
          RateKeys.map: key =>
            RateTableRow(
              label = rateLabel(key, translate).capitalize,
              values = values.map(v => formatRate(key, v.getOrElse(key, BigDecimal(0))))
            )
    )

  private def rateLabel(key: String, translate: String => String): String =
    val messageKey = s"calculations.graph.rateKey.$key"
    val label = translate(messageKey)
    if label == messageKey then key else label

  private def formatRate(key: String, value: BigDecimal): String =
    if key.endsWith("Rate") then
      val pct = (value * 100).bigDecimal.stripTrailingZeros.toPlainString
      s"$pct%"
    else wholePounds(value)

  private def wholePounds(amount: BigDecimal): String =
    if amount == amount.setScale(0, RoundingMode.DOWN) then f"£${amount.setScale(0, RoundingMode.DOWN)}%,.0f"
    else f"£${amount.setScale(2, RoundingMode.HALF_UP)}%,.2f"

  private def roundingDescription(rounding: String): String =
    rounding.toLowerCase match
      case "down" | "floor" => "rounding down"
      case "up" | "ceiling" => "rounding up"
      case _                => "rounding half up"

  private def describeCondition(rule: ShowIf): String =
    rule.equals.map(v => s"${rule.field} is ‘$v’")
      .orElse(rule.contains.map(v => s"${rule.field} includes ‘$v’"))
      .orElse(rule.notEquals.map(v => s"${rule.field} is not ‘$v’"))
      .getOrElse(s"${rule.field} is answered")

  private def architectureMermaid(state: SessionState): String =
    val hipLines =
      if state.option == ArchitectureOption.DownstreamRates then
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
        case IncomeComponentKind.net    => "gross minus deductions"
        case IncomeComponentKind.amount => "amount entered"
      s"""    ${nodeId(c.id)}["${escape(CalculationsI18n.text(c.label, translate))}\\n$op"]"""

    val allowanceNodes = spec.allowances.map: a =>
      val op = a.kind match
        case AllowanceKind.personalAllowance => if a.taper.isDefined then "tapered above threshold" else "full amount"
        case AllowanceKind.conditionalAmount => a.when.map(w => s"only when ${describeCondition(w)}").getOrElse("full amount")
      s"""    ${nodeId(a.id)}["${escape(CalculationsI18n.text(a.label, translate))}\\n${escape(op)}"]"""

    val bandNodes = spec.tax.bands.zipWithIndex.map: (b, i) =>
      s"""    band$i["${escape(CalculationsI18n.text(b.label, translate))}\\n${escape(rateLabel(b.rateKey, translate))}"]"""

    def group(id: String, title: String, nodes: Seq[String]): Seq[String] =
      if nodes.isEmpty then Seq.empty
      else Seq(s"""  subgraph $id ["${escape(title)}"]""") ++ nodes ++ Seq("  end")

    (
      Seq("flowchart TD") ++
        group("income", translate("calculations.graph.stage.income"), incomeNodes) ++
        Seq(s"""  totalIncome["${escape(translate("Total_income"))}"]""", "  income --> totalIncome") ++
        group("allowances", translate("calculations.graph.stage.allowances"), allowanceNodes) ++
        Seq(
          s"""  taxable["${escape(translate("Taxable_income"))}\\nnot below zero"]""",
          "  totalIncome --> taxable"
        ) ++
        Option.when(allowanceNodes.nonEmpty)("  allowances -->|minus| taxable").toSeq ++
        group("bands", translate("calculations.graph.stage.bands"), bandNodes) ++
        Seq(s"""  grossTax["${escape(translate("calculations.graph.term.taxBeforePayments"))}"]""") ++
        (if bandNodes.isEmpty then Seq("  taxable --> grossTax")
         else Seq("  taxable --> bands", "  bands -->|added| grossTax")) ++
        Seq(
          s"""  taxPaid["${escape(translate("Tax_already_paid"))}"]""",
          s"""  taxDue(["${escape(translate("Estimated_income_tax"))}\\nnot below zero"])""",
          "  grossTax --> taxDue",
          "  taxPaid -->|minus| taxDue"
        )
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

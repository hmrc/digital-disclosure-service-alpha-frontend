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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceRule,
  CalculationSpec,
  DisclosureSlice,
  IncomeComponent,
  IncomeComponentKind,
  PenaltyRules,
  PenaltyScore,
  QuestionPack,
  RateCatalog,
  ShowIf
}

/** Describes a calculation spec for reviewers: one stage per step, and the rates used per year. */
object CalculationStagesBuilder:

  def stages(
    spec     : CalculationSpec,
    catalog  : RateCatalog,
    translate: String => String,
    pack     : Option[QuestionPack] = None
  ): Seq[CalcStage] =
    val rate = rateLabel(catalog, translate)
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
        rules = spec.allowances.map(allowanceRule(_, rate, translate))
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
        rules = bandRules(spec, rate, translate)
      ),
      CalcStage(
        id = "taxDue",
        title = translate("calculations.graph.stage.taxDue"),
        formula = s"$taxDue = $taxBeforePayments − $taxPaid, not below £0",
        rules = Seq(
          CalcRule(
            label = taxPaid,
            rule = "Tax already paid and tax taken off at source, added together.",
            source = Some(spec.taxPaidFields.mkString(" + "))
          ),
          CalcRule(
            label = translate("calculations.graph.term.rounding"),
            rule = s"Tax is rounded to ${spec.tax.scale} decimal places, ${spec.tax.rounding.description}.",
            source = Some(s"tax.scale = ${spec.tax.scale}, tax.rounding = ${spec.tax.rounding}")
          )
        )
      )
    ) ++ spec.penalty.toSeq.map(penaltyStage(_, taxDue, pack, translate))

  def rateTable(catalog: RateCatalog, translate: String => String): RateTable =
    RateTable(
      years = catalog.taxYears,
      rows =
        RateTableRow(translate("calculations.graph.rates.version"), catalog.years.map(_.version)) +:
          catalog.rates.map: rate =>
            RateTableRow(
              label = rateLabel(catalog, translate)(rate.key).capitalize,
              values = catalog.years.map(year => Formats.rateValue(rate.kind, year.value(rate.key)))
            )
    )

  /** A rate's name as used in a sentence, e.g. "higher rate". Shows the key when the label has no message. */
  def rateLabel(catalog: RateCatalog, translate: String => String)(key: String): String =
    catalog.definition(key) match
      case Some(rate) if translate(rate.label) != rate.label => translate(rate.label)
      case _                                                 => key

  def describeCondition(rule: ShowIf): String =
    rule.equals.map(v => s"${rule.field} is ‘$v’")
      .orElse(rule.contains.map(v => s"${rule.field} includes ‘$v’"))
      .orElse(rule.notEquals.map(v => s"${rule.field} is not ‘$v’"))
      .getOrElse(s"${rule.field} is answered")

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

  private def allowanceRule(a: AllowanceRule, rate: String => String, translate: String => String): CalcRule =
    val amount = rate(a.rateKey).capitalize + " for the tax year"
    val taper = a.taper.map: t =>
      s" Reduced by ${Formats.wholePounds(t.reduceBy)} for every ${Formats.wholePounds(t.forEvery)} of total income " +
        s"above the ${rate(t.thresholdRateKey)}, down to £0."
    val condition = a.when.map(w => s" Only given when ${describeCondition(w)}; otherwise £0.")
    CalcRule(
      label = CalculationsI18n.text(a.label, translate),
      rule = amount + "." + taper.getOrElse("") + condition.getOrElse(""),
      source = Some((Seq(a.rateKey) ++ a.taper.map(_.thresholdRateKey) ++ a.when.map(_.field)).mkString(", "))
    )

  private def penaltyStage(
    rules    : PenaltyRules,
    taxDue   : String,
    pack     : Option[QuestionPack],
    translate: String => String
  ): CalcStage =
    val penalty = translate("Penalty")
    val quality = translate("Quality_of_disclosure")
    val upper = Formats.percentPoints(rules.upperRange)
    val lower = Formats.percentPoints(rules.lowerRange)
    val range = plain((rules.upperRange - rules.lowerRange).max(0))
    val tellingParts = rules.telling.parts.map: part =>
      CalcRule(
        label = CalculationsI18n.text(part.label, translate),
        rule = scoreSentence(part.scores, part.question, pack, translate),
        source = Some(part.question)
      )
    CalcStage(
      id = "penalty",
      title = translate("calculations.graph.stage.penalty"),
      formula =
        s"Penalty rate = $upper − ($range × $quality). $penalty = $taxDue × penalty rate.",
      rules = Seq(
        CalcRule(
          label = translate("I_have_a_reasonable_excuse"),
          rule =
            s"When the answer is ‘${answerName(rules.excuseQuestion, rules.excuseValue, pack, translate)}’, the penalty is £0. " +
              "The 3 year rule is not applied.",
          source = Some(rules.excuseQuestion)
        )
      ) ++ tellingParts ++ Seq(
        CalcRule(
          label = translate("Telling"),
          rule =
            s"Average the ${rules.telling.parts.size} parts above, then multiply by ${Formats.percentPoints(rules.telling.maximum)}.",
          source = Some(rules.telling.parts.map(_.question).mkString(" + "))
        ),
        sliceRule("helping", rules.helping, pack, translate),
        sliceRule("giving", rules.giving, pack, translate),
        CalcRule(
          label = quality,
          rule = "Add telling, helping and giving. The total cannot go above 100% or below 0%.",
          source = Some("penalty.telling + penalty.helping + penalty.giving")
        ),
        CalcRule(
          label = translate("Penalty_range"),
          rule = s"Step 1. Upper range $upper minus lower range $lower. The next step uses $range, the gap as a whole number.",
          source = Some("penalty.upperRange, penalty.lowerRange")
        ),
        CalcRule(
          label = translate("Percentage_reduction"),
          rule = s"Step 2. Multiply the range, as a whole number, by the quality of disclosure: $range × $quality.",
          source = Some("penalty.upperRange, penalty.lowerRange")
        ),
        CalcRule(
          label = translate("Penalty_percentage_rate"),
          rule = s"Step 3. Upper range $upper minus the percentage reduction.",
          source = Some("penalty.upperRange")
        ),
        CalcRule(
          label = penalty,
          rule = s"Multiply the $taxDue by the penalty percentage rate.",
          source = Some("tax due, penalty rate")
        )
      )
    )

  private def sliceRule(
    configKey: String,
    slice    : DisclosureSlice,
    pack     : Option[QuestionPack],
    translate: String => String
  ): CalcRule =
    val maximum = Formats.percentPoints(slice.maximum)
    slice.question match
      case Some(question) =>
        CalcRule(
          label = CalculationsI18n.text(slice.label, translate),
          rule = s"${scoreSentence(slice.scores, question, pack, translate)} Multiply that score by $maximum.",
          source = Some(question)
        )
      case None =>
        val awarded = Formats.percentPoints(slice.score * slice.maximum)
        CalcRule(
          label = CalculationsI18n.text(slice.label, translate),
          rule = s"No question. Configured score ${plain(slice.score)} multiplied by $maximum is $awarded.",
          source = Some(s"penalty.$configKey.score")
        )

  private def scoreSentence(
    scores   : Seq[PenaltyScore],
    question : String,
    pack     : Option[QuestionPack],
    translate: String => String
  ): String =
    scores.map(s => s"${answerName(question, s.value, pack, translate)} scores ${plain(s.score)}").mkString(". ") + "."

  /** The option's wording, without the examples in brackets. Falls back to the config value. */
  private def answerName(questionId: String, value: String, pack: Option[QuestionPack], translate: String => String): String =
    pack
      .flatMap(_.questions.find(_.id == questionId))
      .flatMap(_.options)
      .flatMap(_.find(_.value == value))
      .map(o => CalculationsI18n.text(o.label, translate).split(" \\(", 2).head)
      .getOrElse(value)

  private def plain(amount: BigDecimal): String =
    amount.bigDecimal.stripTrailingZeros.toPlainString

  private def bandRules(spec: CalculationSpec, rate: String => String, translate: String => String): Seq[CalcRule] =
    spec.tax.bands.zipWithIndex.map: (band, index) =>
      val slice = (index, band.upToRateKey) match
        case (0, Some(cap)) => s"Taxable income up to the ${rate(cap)}"
        case (_, Some(cap)) => s"Taxable income above the previous band, up to the ${rate(cap)}"
        case (0, None)      => "All taxable income"
        case (_, None)      => "All taxable income above the previous band"
      CalcRule(
        label = CalculationsI18n.text(band.label, translate),
        rule = s"$slice, charged at the ${rate(band.rateKey)}.",
        source = Some((Seq(band.rateKey) ++ band.upToRateKey).mkString(", "))
      )

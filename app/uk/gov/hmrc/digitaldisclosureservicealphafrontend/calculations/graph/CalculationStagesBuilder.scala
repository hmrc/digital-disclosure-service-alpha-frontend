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
  IncomeComponent,
  IncomeComponentKind,
  RateCatalog,
  RatePack,
  ShowIf
}

/** Describes a calculation spec for reviewers: five stages with a formula each, and the rates used per year. */
object CalculationStagesBuilder:

  def stages(spec: CalculationSpec, translate: String => String): Seq[CalcStage] =
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
        rules = bandRules(spec, translate)
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
            rule = s"Tax is rounded to ${spec.tax.scale} decimal places, ${roundingDescription(spec.tax.rounding)}.",
            source = Some(s"tax.scale = ${spec.tax.scale}, tax.rounding = ${spec.tax.rounding}")
          )
        )
      )
    )

  def rateTable(catalog: RateCatalog, translate: String => String): RateTable =
    RateTable(
      years = catalog.taxYears,
      rows =
        RateTableRow(translate("calculations.graph.rates.version"), catalog.years.map(_.version)) +:
          RatePack.Keys.map: key =>
            RateTableRow(
              label = rateLabel(key, translate).capitalize,
              values = catalog.years.map(year => Formats.rateValue(key, year.value(key)))
            )
    )

  /** A readable name for a rate key, e.g. "higher rate"; falls back to the key itself. */
  def rateLabel(key: String, translate: String => String): String =
    val messageKey = s"calculations.graph.rateKey.$key"
    val label = translate(messageKey)
    if label == messageKey then key else label

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

  private def allowanceRule(a: AllowanceRule, translate: String => String): CalcRule =
    val amount = rateLabel(a.rateKey, translate).capitalize + " for the tax year"
    val taper = a.taper.map: t =>
      s" Reduced by ${Formats.wholePounds(t.reduceBy)} for every ${Formats.wholePounds(t.forEvery)} of total income " +
        s"above the ${rateLabel(t.thresholdRateKey, translate)}, down to £0."
    val condition = a.when.map(w => s" Only given when ${describeCondition(w)}; otherwise £0.")
    CalcRule(
      label = CalculationsI18n.text(a.label, translate),
      rule = amount + "." + taper.getOrElse("") + condition.getOrElse(""),
      source = Some((Seq(a.rateKey) ++ a.taper.map(_.thresholdRateKey) ++ a.when.map(_.field)).mkString(", "))
    )

  private def bandRules(spec: CalculationSpec, translate: String => String): Seq[CalcRule] =
    spec.tax.bands.zipWithIndex.map: (band, index) =>
      val slice = (index, band.upToRateKey) match
        case (0, Some(cap)) => s"Taxable income up to the ${rateLabel(cap, translate)}"
        case (_, Some(cap)) => s"Taxable income above the previous band, up to the ${rateLabel(cap, translate)}"
        case (0, None)      => "All taxable income"
        case (_, None)      => "All taxable income above the previous band"
      CalcRule(
        label = CalculationsI18n.text(band.label, translate),
        rule = s"$slice, charged at the ${rateLabel(band.rateKey, translate)}.",
        source = Some((Seq(band.rateKey) ++ band.upToRateKey).mkString(", "))
      )

  private def roundingDescription(rounding: String): String =
    rounding.toLowerCase match
      case "down" | "floor" => "rounding down"
      case "up" | "ceiling" => "rounding up"
      case _                => "rounding half up"

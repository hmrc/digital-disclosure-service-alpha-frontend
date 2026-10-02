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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats.{gbp, percent}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  CalcExplanation,
  IncomeComponentKind,
  TaxRules
}

/**
  * Describes each step of a year's calculation in plain English with the actual amounts.
  * Steps come out in a fixed order that the worked-example computation relies on:
  * income components, total income, allowances, taxable income, bands, tax before payments,
  * tax already paid, tax due.
  */
object LiabilityExplanations:

  private val NotBelowZero = "If the result is less than £0, use £0 instead."

  def forYear(figures: YearFigures, tax: TaxRules): Seq[CalcExplanation] =
    income(figures) ++
      Seq(totalIncome(figures)) ++
      allowances(figures) ++
      Seq(taxable(figures)) ++
      bands(figures) ++
      Seq(taxBeforePaid(figures, tax), taxPaid(figures), taxDue(figures))

  private def income(figures: YearFigures): Seq[CalcExplanation] =
    figures.income.map: f =>
      val floor = f.component.floorAtZero
      f.component.kind match
        case IncomeComponentKind.amount =>
          CalcExplanation(
            label = f.component.label,
            operation =
              if floor then s"Use ${gbp(f.gross)}. If this amount is less than £0, use £0 instead."
              else s"Use ${gbp(f.gross)}.",
            result = gbp(f.amount)
          )
        case IncomeComponentKind.net =>
          val subtraction = s"Subtract ${gbp(f.deductions)} of deductions from ${gbp(f.gross)} gross income."
          CalcExplanation(
            label = f.component.label,
            operation = if floor then s"$subtraction $NotBelowZero" else subtraction,
            result = gbp(f.amount),
            working = Option.when(f.gross != 0 || f.deductions != 0)(s"${gbp(f.gross)} − ${gbp(f.deductions)}")
          )

  private def totalIncome(figures: YearFigures): CalcExplanation =
    CalcExplanation(
      label = "Total_income",
      operation =
        if figures.income.isEmpty then "There are no income amounts, so use £0.00."
        else s"Add all income amounts: ${figures.income.map(f => gbp(f.amount)).mkString(" + ")}.",
      result = gbp(figures.totalIncome)
    )

  private def allowances(figures: YearFigures): Seq[CalcExplanation] =
    figures.allowances.map: a =>
      val isPersonal = a.rule.kind == AllowanceKind.personalAllowance
      CalcExplanation(
        label = if isPersonal then s"${a.rule.label}.withYear:${figures.rates.taxYear}" else a.rule.label,
        operation = allowanceOperation(a, figures.totalIncome),
        result = gbp(a.amount),
        working = Option.when(isPersonal && a.amount < a.fullAmount)(
          s"${gbp(a.fullAmount)} − ${gbp(a.fullAmount - a.amount)} taper"
        )
      )

  private def allowanceOperation(a: AllowanceFigure, totalIncome: BigDecimal): String =
    val full = gbp(a.fullAmount)
    (a.rule.kind, a.rule.taper, a.taperThreshold) match
      case (AllowanceKind.conditionalAmount, _, _) =>
        if a.applies then s"Use the $full allowance for this tax year."
        else "This allowance does not apply, so use £0.00."
      case (_, Some(taper), Some(threshold)) if a.tapered =>
        s"Income is ${gbp(totalIncome - threshold)} above the ${gbp(threshold)} taper threshold. " +
          s"Reduce the $full allowance by ${gbp(a.reduction)} " +
          s"(${gbp(taper.reduceBy)} for every ${gbp(taper.forEvery)} above the threshold), " +
          s"leaving ${gbp(a.amount)}."
      case (_, Some(_), Some(threshold)) =>
        s"Income of ${gbp(totalIncome)} is not above the ${gbp(threshold)} taper threshold, " +
          s"so use the full allowance of $full."
      case _ =>
        s"Use the full allowance of $full for this tax year."

  private def taxable(figures: YearFigures): CalcExplanation =
    val total = gbp(figures.totalIncome)
    CalcExplanation(
      label = "Taxable_income",
      operation =
        if figures.allowances.isEmpty then s"Use total income of $total. If it is less than £0, use £0 instead."
        else
          s"Subtract allowances of ${figures.allowances.map(a => gbp(a.amount)).mkString(", ")} " +
            s"from total income of $total. $NotBelowZero",
      result = gbp(figures.taxableIncome)
    )

  private def bands(figures: YearFigures): Seq[CalcExplanation] =
    figures.bands.map: band =>
      CalcExplanation(
        label = band.label,
        operation = s"Apply the ${percent(band.rate)} rate to ${gbp(band.width)} of taxable income.",
        result = gbp(band.tax),
        working = Some(s"${gbp(band.width)} × ${percent(band.rate)}")
      )

  private def taxBeforePaid(figures: YearFigures, tax: TaxRules): CalcExplanation =
    CalcExplanation(
      label = "Estimated_income_tax_before_tax_already_paid",
      operation =
        if figures.bands.isEmpty then "No income falls into a tax band, so use £0.00."
        else
          s"Add the tax from each band: ${figures.bands.map(b => gbp(b.tax)).mkString(" + ")}. " +
            s"Round the total to ${tax.scale} decimal places.",
      result = gbp(figures.taxBeforePaid)
    )

  private def taxPaid(figures: YearFigures): CalcExplanation =
    CalcExplanation(
      label = "Tax_already_paid",
      operation = s"Add all tax already paid or deducted at source. The total is ${gbp(figures.taxPaid)}.",
      result = gbp(figures.taxPaid)
    )

  private def taxDue(figures: YearFigures): CalcExplanation =
    CalcExplanation(
      label = "Estimated_income_tax",
      operation =
        s"Subtract ${gbp(figures.taxPaid)} already paid from ${gbp(figures.taxBeforePaid)} tax before payments. " +
          NotBelowZero,
      result = gbp(figures.taxDue)
    )

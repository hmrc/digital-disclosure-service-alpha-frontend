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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  AllowanceRule,
  Answers,
  CalculationSpec,
  IncomeComponent,
  IncomeComponentKind,
  LiabilityResult,
  MultiYearLiabilityResult,
  RateCatalog,
  RatePack,
  TaxRules
}

import scala.math.BigDecimal.RoundingMode

/** One income component: `gross` is the amount entered (or gross amount for net components). */
final case class IncomeFigure(component: IncomeComponent, gross: BigDecimal, deductions: BigDecimal, amount: BigDecimal)

/** `tapered` is true when income was above the taper threshold and the allowance was reduced. */
final case class AllowanceFigure(
  rule           : AllowanceRule,
  fullAmount     : BigDecimal,
  applies        : Boolean,
  taperThreshold : Option[BigDecimal],
  tapered        : Boolean,
  reduction      : BigDecimal,
  amount         : BigDecimal
)

final case class BandFigure(label: String, width: BigDecimal, rate: BigDecimal, tax: BigDecimal)

/** Every figure behind one tax year's estimate, before any wording is added. */
final case class YearFigures(
  rates          : RatePack,
  income         : Seq[IncomeFigure],
  totalIncome    : BigDecimal,
  allowances     : Seq[AllowanceFigure],
  taxableIncome  : BigDecimal,
  bands          : Seq[BandFigure],
  taxBeforePaid  : BigDecimal,
  taxPaid        : BigDecimal,
  taxDue         : BigDecimal
)

/** Interprets a CalculationSpec against answers and each selected year's rate pack. Demo calculations only. */
object LiabilityCalculator:

  def calculate(catalog: RateCatalog, spec: CalculationSpec, answers: Map[String, String]): MultiYearLiabilityResult =
    val years = QuestionEngine.selectedTaxYears(answers) match
      case Nil      => catalog.taxYears
      case selected => selected.filter(y => catalog.forYear(y).isDefined)

    val yearResults = years.flatMap: taxYear =>
      catalog.forYear(taxYear).map(rates => calculateYear(rates, spec, answers))

    MultiYearLiabilityResult(
      catalogVersion = catalog.version,
      calculationVersion = spec.version,
      years = yearResults,
      totalTaxDue = spec.tax.round(yearResults.map(_.taxDue).sum)
    )

  def calculateYear(rates: RatePack, spec: CalculationSpec, answers: Map[String, String]): LiabilityResult =
    val figures = yearFigures(rates, spec, answers)
    LiabilityResult(
      taxYear = rates.taxYear,
      ratePackVersion = rates.version,
      totalIncome = figures.totalIncome,
      allowancesUsed = figures.allowances.map(a => a.rule.id -> a.amount).toMap,
      taxableIncome = figures.taxableIncome,
      taxDue = figures.taxDue,
      explanations = LiabilityExplanations.forYear(figures, spec.tax)
    )

  def yearFigures(rates: RatePack, spec: CalculationSpec, answers: Map[String, String]): YearFigures =
    def amount(field: String): BigDecimal = Answers.amountForYear(answers, field, rates.taxYear)

    val income = spec.incomeComponents.map(incomeFigure(_, amount))
    val totalIncome = income.map(_.amount).sum
    val allowances = spec.allowances.map(allowanceFigure(_, rates, answers, totalIncome))
    val taxable = (totalIncome - allowances.map(_.amount).sum).max(0)
    val bands = applyBands(taxable, spec.tax, rates)
    val taxBeforePaid = spec.tax.round(bands.map(_.tax).sum)
    val taxPaid = spec.taxPaidFields.map(amount).sum

    YearFigures(
      rates = rates,
      income = income,
      totalIncome = totalIncome,
      allowances = allowances,
      taxableIncome = taxable,
      bands = bands,
      taxBeforePaid = taxBeforePaid,
      taxPaid = taxPaid,
      taxDue = spec.tax.round((taxBeforePaid - taxPaid).max(0))
    )

  private def incomeFigure(component: IncomeComponent, amount: String => BigDecimal): IncomeFigure =
    val (gross, deductions) = component.kind match
      case IncomeComponentKind.amount =>
        amount(component.field.getOrElse(component.id)) -> BigDecimal(0)
      case IncomeComponentKind.net =>
        amount(component.grossField.getOrElse(component.id)) ->
          Seq(component.deductField, component.altDeductField).flatten.map(amount).sum
    val net = gross - deductions
    IncomeFigure(component, gross, deductions, if component.floorAtZero then net.max(0) else net)

  private def allowanceFigure(
    rule       : AllowanceRule,
    rates      : RatePack,
    answers    : Map[String, String],
    totalIncome: BigDecimal
  ): AllowanceFigure =
    val full = rates.value(rule.rateKey)
    rule.kind match
      case AllowanceKind.conditionalAmount =>
        val applies = rule.when.forall(when => when.matches(Answers.values(answers, when.field)))
        AllowanceFigure(rule, full, applies, None, tapered = false, BigDecimal(0), if applies then full else BigDecimal(0))
      case AllowanceKind.personalAllowance =>
        rule.taper match
          case None =>
            AllowanceFigure(rule, full, applies = true, None, tapered = false, BigDecimal(0), full)
          case Some(taper) =>
            val threshold = rates.value(taper.thresholdRateKey)
            val tapered = totalIncome > threshold && taper.forEvery != 0
            val reduction =
              if tapered then ((totalIncome - threshold) * taper.reduceBy / taper.forEvery).setScale(0, RoundingMode.DOWN)
              else BigDecimal(0)
            AllowanceFigure(rule, full, applies = true, Some(threshold), tapered, reduction, (full - reduction).max(0))

  /** Fills each band in order; a band without an upper limit takes whatever taxable income is left. */
  private def applyBands(taxable: BigDecimal, tax: TaxRules, rates: RatePack): Seq[BandFigure] =
    val (_, _, figures) = tax.bands.foldLeft((taxable, BigDecimal(0), Vector.empty[BandFigure])):
      case ((remaining, previousLimit, acc), band) =>
        val rate = rates.value(band.rateKey)
        val limit = band.upToRateKey.map(rates.value)
        val width = limit.fold(remaining)(l => remaining.min((l - previousLimit).max(0)))
        (remaining - width, limit.getOrElse(previousLimit), acc :+ BandFigure(band.label, width, rate, width * rate))
    figures

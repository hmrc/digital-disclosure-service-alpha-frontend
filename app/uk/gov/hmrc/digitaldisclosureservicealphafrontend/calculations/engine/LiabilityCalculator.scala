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
  CalcExplanation,
  CalculationSpec,
  IncomeComponent,
  IncomeComponentKind,
  LiabilityResult,
  MultiYearLiabilityResult,
  RateCatalog,
  RatePack,
  ShowIf,
  TaxRules,
  YearAnswers
}

import scala.math.BigDecimal.RoundingMode

/** Interprets a CalculationSpec against answers + a year rate pack.
  * Demo calculations only
  */
object LiabilityCalculator:

  def calculate(
    catalog: RateCatalog,
    spec   : CalculationSpec,
    answers: Map[String, String]
  ): MultiYearLiabilityResult =
    val years = QuestionEngine.selectedTaxYears(answers) match
      case Nil      => catalog.taxYears
      case selected => selected.filter(y => catalog.forYear(y).isDefined)

    val yearResults = years.flatMap: taxYear =>
      catalog.forYear(taxYear).map(rates => calculateYear(rates, spec, answers, taxYear))

    MultiYearLiabilityResult(
      catalogVersion = catalog.version,
      calculationVersion = spec.version,
      years = yearResults,
      totalTaxDue = round(yearResults.map(_.taxDue).sum, spec.tax)
    )

  def calculateYear(
    rates  : RatePack,
    spec   : CalculationSpec,
    answers: Map[String, String],
    taxYear: String
  ): LiabilityResult =
    val rateMap = rateValues(rates)

    val componentAmounts = spec.incomeComponents.map: component =>
      component -> incomeAmount(component, answers, taxYear)

    val totalIncome = componentAmounts.map(_._2).sum

    val allowanceAmounts = spec.allowances.map: allowance =>
      allowance -> allowanceAmount(allowance, rateMap, answers, totalIncome)

    val totalAllowances = allowanceAmounts.map(_._2).sum
    val taxable = (totalIncome - totalAllowances).max(0)
    val (grossTaxDue, bandSlices) = applyBandsDetailed(taxable, spec.tax, rateMap)
    val taxPaid = taxAlreadyPaid(answers, taxYear)
    val taxDue = round((grossTaxDue - taxPaid).max(0), spec.tax)

    val incomeExplanations = componentAmounts.map: (c, amount) =>
      CalcExplanation(
        label = c.label,
        operation = incomeOperation(c, answers, taxYear),
        result = gbp(amount)
      )

    val totalIncomeExplanation = CalcExplanation(
      label = "Total_income",
      operation =
        if componentAmounts.isEmpty then "There are no income amounts, so use £0.00."
        else s"Add all income amounts: ${componentAmounts.map((_, a) => gbp(a)).mkString(" + ")}.",
      result = gbp(totalIncome)
    )

    val allowanceExplanations = allowanceAmounts.map: (a, amount) =>
      val label =
        if a.kind == AllowanceKind.personalAllowance then s"${a.label}.withYear:${rates.taxYear}"
        else a.label
      CalcExplanation(
        label = label,
        operation = allowanceOperation(a, rateMap, answers, totalIncome, amount),
        result = gbp(amount)
      )

    val taxableExplanation = CalcExplanation(
      label = "Taxable_income",
      operation =
        if allowanceAmounts.isEmpty then
          s"Use total income of ${gbp(totalIncome)}. If it is less than £0, use £0 instead."
        else
          s"Subtract allowances of ${allowanceAmounts.map((_, a) => gbp(a)).mkString(", ")} " +
            s"from total income of ${gbp(totalIncome)}. If the result is less than £0, use £0 instead.",
      result = gbp(taxable)
    )

    val bandExplanations = bandSlices.map: slice =>
      CalcExplanation(
        label = slice.label,
        operation = s"Apply the ${pct(slice.rate)} rate to ${gbp(slice.width)} of taxable income.",
        result = gbp(slice.tax)
      )

    val grossTaxExplanation = CalcExplanation(
      label = "Estimated_income_tax_before_tax_already_paid",
      operation =
        if bandSlices.isEmpty then "No income falls into a tax band, so use £0.00."
        else
          s"Add the tax from each band: ${bandSlices.map(s => gbp(s.tax)).mkString(" + ")}. " +
            s"Round the total to ${spec.tax.scale} decimal places.",
      result = gbp(grossTaxDue)
    )

    val taxPaidExplanation = CalcExplanation(
      label = "Tax_already_paid",
      operation = s"Add all tax already paid or deducted at source. The total is ${gbp(taxPaid)}.",
      result = gbp(taxPaid)
    )

    val taxDueExplanation = CalcExplanation(
      label = "Estimated_income_tax",
      operation =
        s"Subtract ${gbp(taxPaid)} already paid from ${gbp(grossTaxDue)} tax before payments. " +
          "If the result is less than £0, use £0 instead.",
      result = gbp(taxDue)
    )

    val explanations =
      incomeExplanations ++
        Seq(totalIncomeExplanation) ++
        allowanceExplanations ++
        Seq(taxableExplanation) ++
        bandExplanations ++
        Seq(grossTaxExplanation, taxPaidExplanation, taxDueExplanation)

    val breakdown = explanations.map(e => e.label -> e.result)

    LiabilityResult(
      taxYear = rates.taxYear,
      ratePackVersion = rates.version,
      totalIncome = totalIncome,
      personalAllowanceUsed = allowanceAmounts.collectFirst {
        case (a, amount) if a.kind == AllowanceKind.personalAllowance => amount
      }.getOrElse(0),
      blindAllowanceUsed = allowanceAmounts.collectFirst {
        case (a, amount) if a.id == "blindPersonsAllowance" || a.rateKey == "blindPersonsAllowance" => amount
      }.getOrElse(0),
      taxableIncome = taxable,
      taxDue = taxDue,
      breakdown = breakdown,
      explanations = explanations
    )

  private final case class BandSlice(
    label: String,
    width: BigDecimal,
    rate : BigDecimal,
    tax  : BigDecimal
  )

  private def incomeAmount(
    component: IncomeComponent,
    answers  : Map[String, String],
    taxYear  : String
  ): BigDecimal =
    component.kind match
      case IncomeComponentKind.amount =>
        val field = component.field.getOrElse(component.id)
        val amount = yearOrBase(answers, field, taxYear)
        if component.floorAtZero then amount.max(0) else amount
      case IncomeComponentKind.net =>
        val gross = yearOrBase(answers, component.grossField.getOrElse(component.id), taxYear)
        val deduct =
          component.deductField.map(f => yearOrBase(answers, f, taxYear)).getOrElse(BigDecimal(0)) +
            component.altDeductField.map(f => yearOrBase(answers, f, taxYear)).getOrElse(BigDecimal(0))
        val net = gross - deduct
        if component.floorAtZero then net.max(0) else net

  private def incomeOperation(
    component: IncomeComponent,
    answers  : Map[String, String],
    taxYear  : String
  ): String =
    component.kind match
      case IncomeComponentKind.amount =>
        val field = component.field.getOrElse(component.id)
        val amount = yearOrBase(answers, field, taxYear)
        if component.floorAtZero then
          s"Use ${gbp(amount)}. If this amount is less than £0, use £0 instead."
        else s"Use ${gbp(amount)}."
      case IncomeComponentKind.net =>
        val gross = yearOrBase(answers, component.grossField.getOrElse(component.id), taxYear)
        val deduct =
          component.deductField.map(f => yearOrBase(answers, f, taxYear)).getOrElse(BigDecimal(0)) +
            component.altDeductField.map(f => yearOrBase(answers, f, taxYear)).getOrElse(BigDecimal(0))
        val subtraction = s"Subtract ${gbp(deduct)} of deductions from ${gbp(gross)} gross income."
        if component.floorAtZero then
          s"$subtraction If the result is less than £0, use £0 instead."
        else subtraction

  private def allowanceAmount(
    allowance  : AllowanceRule,
    rateMap    : Map[String, BigDecimal],
    answers    : Map[String, String],
    totalIncome: BigDecimal
  ): BigDecimal =
    allowance.kind match
      case AllowanceKind.conditionalAmount =>
        if matches(allowance.when, answers) then rateMap.getOrElse(allowance.rateKey, BigDecimal(0))
        else BigDecimal(0)
      case AllowanceKind.personalAllowance =>
        val base = rateMap.getOrElse(allowance.rateKey, BigDecimal(0))
        allowance.taper match
          case None => base
          case Some(taper) =>
            val threshold = rateMap.getOrElse(taper.thresholdRateKey, BigDecimal(0))
            if totalIncome <= threshold || taper.forEvery == 0 then base
            else
              val reduction =
                ((totalIncome - threshold) * taper.reduceBy / taper.forEvery)
                  .setScale(0, RoundingMode.DOWN)
              (base - reduction).max(0)

  private def allowanceOperation(
    allowance  : AllowanceRule,
    rateMap    : Map[String, BigDecimal],
    answers    : Map[String, String],
    totalIncome: BigDecimal,
    amount     : BigDecimal
  ): String =
    val base = rateMap.getOrElse(allowance.rateKey, BigDecimal(0))
    allowance.kind match
      case AllowanceKind.conditionalAmount =>
        if matches(allowance.when, answers) then
          s"Use the ${gbp(base)} allowance for this tax year."
        else
          "This allowance does not apply, so use £0.00."
      case AllowanceKind.personalAllowance =>
        allowance.taper match
          case None => s"Use the full allowance of ${gbp(base)} for this tax year."
          case Some(taper) =>
            val threshold = rateMap.getOrElse(taper.thresholdRateKey, BigDecimal(0))
            if totalIncome <= threshold || taper.forEvery == 0 then
              s"Income of ${gbp(totalIncome)} is not above the ${gbp(threshold)} taper threshold, " +
                s"so use the full allowance of ${gbp(base)}."
            else
              val reduction =
                ((totalIncome - threshold) * taper.reduceBy / taper.forEvery)
                  .setScale(0, RoundingMode.DOWN)
              val aboveThreshold = totalIncome - threshold
              s"Income is ${gbp(aboveThreshold)} above the ${gbp(threshold)} taper threshold. " +
                s"Reduce the ${gbp(base)} allowance by ${gbp(reduction)} " +
                s"(${gbp(taper.reduceBy)} for every ${gbp(taper.forEvery)} above the threshold), " +
                s"leaving ${gbp(amount)}."

  private def applyBandsDetailed(
    taxable: BigDecimal,
    tax    : TaxRules,
    rateMap: Map[String, BigDecimal]
  ): (BigDecimal, Seq[BandSlice]) =
    var remaining = taxable
    var previousCap = BigDecimal(0)
    val slices = tax.bands.map: band =>
      val rate = rateMap.getOrElse(band.rateKey, BigDecimal(0))
      band.upToRateKey match
        case Some(capKey) =>
          val cap = rateMap.getOrElse(capKey, BigDecimal(0))
          val width = (cap - previousCap).max(0)
          val slice = remaining.min(width)
          remaining = (remaining - slice).max(0)
          previousCap = cap
          BandSlice(band.label, slice, rate, slice * rate)
        case None =>
          val slice = remaining
          remaining = 0
          BandSlice(band.label, slice, rate, slice * rate)
    (round(slices.map(_.tax).sum, tax), slices)

  private def taxAlreadyPaid(answers: Map[String, String], taxYear: String): BigDecimal =
    Seq("taxAlreadyPaid", "employmentTaxDeducted", "bankInterestTaxDeducted", "pensionTaxDeducted")
      .map(field => yearOrBase(answers, field, taxYear))
      .sum

  private def rateValues(rates: RatePack): Map[String, BigDecimal] =
    Map(
      "personalAllowance" -> rates.personalAllowance,
      "taperThreshold" -> rates.taperThreshold,
      "blindPersonsAllowance" -> rates.blindPersonsAllowance,
      "basicRateBand" -> rates.basicRateBand,
      "basicRate" -> rates.basicRate,
      "higherRate" -> rates.higherRate
    )

  private def matches(rule: Option[ShowIf], answers: Map[String, String]): Boolean =
    rule match
      case None => true
      case Some(r) =>
        val values = QuestionEngine.splitMulti(answers.getOrElse(r.field, ""))
        val hasPositive = r.equals.isDefined || r.contains.isDefined
        val positiveOk =
          if !hasPositive then true
          else r.equals.exists(values.contains) || r.contains.exists(values.contains)
        val negativeOk = r.notEquals.forall(v => !values.contains(v))
        if !hasPositive && r.notEquals.isEmpty then true
        else if values.isEmpty then false
        else positiveOk && negativeOk

  private def yearOrBase(answers: Map[String, String], baseId: String, taxYear: String): BigDecimal =
    val yearKey = YearAnswers.key(baseId, taxYear)
    if answers.contains(yearKey) then money(answers, yearKey)
    else money(answers, baseId)

  private def money(answers: Map[String, String], key: String): BigDecimal =
    answers
      .get(key)
      .map(_.replace(",", "").trim)
      .filter(_.nonEmpty)
      .flatMap(s => scala.util.Try(BigDecimal(s)).toOption)
      .getOrElse(BigDecimal(0))

  private def round(amount: BigDecimal, tax: TaxRules): BigDecimal =
    val mode = tax.rounding.toLowerCase match
      case "down" | "floor" => RoundingMode.DOWN
      case "up" | "ceiling" => RoundingMode.UP
      case _                => RoundingMode.HALF_UP
    amount.setScale(tax.scale, mode)

  private def gbp(amount: BigDecimal): String =
    f"£${amount.setScale(2, RoundingMode.HALF_UP)}%,.2f"

  private def pct(rate: BigDecimal): String =
    if rate * 100 == (rate * 100).setScale(0, RoundingMode.HALF_UP) then
      f"${(rate * 100).setScale(0, RoundingMode.HALF_UP)}%.0f%%"
    else
      f"${(rate * 100).setScale(2, RoundingMode.HALF_UP)}%.2f%%"

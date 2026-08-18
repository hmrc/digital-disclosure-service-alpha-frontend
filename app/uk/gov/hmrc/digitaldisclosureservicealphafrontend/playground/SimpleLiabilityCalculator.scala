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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.playground

/** Intentionally small liability demo — not production tax law.
  * Shows how year rate packs drive a reproducible result from journey answers.
  */
object SimpleLiabilityCalculator:

  def calculate(rates: RatePack, answers: Map[String, String]): LiabilityResult =
    val seTurnover  = money(answers, "selfEmploymentTurnover")
    val seExpenses  = money(answers, "selfEmploymentExpenses")
    val property    = money(answers, "propertyIncome")
    val dividends   = money(answers, "dividends")
    val seProfit    = (seTurnover - seExpenses).max(0)

    val totalIncome = seProfit + property + dividends

    val blindEligible = answers.get("blindPersonEligible").contains("yes")
    val blindUsed     = if blindEligible then rates.blindPersonsAllowance else BigDecimal(0)

    val taperedPa =
      if totalIncome > rates.taperThreshold then
        val reduction = ((totalIncome - rates.taperThreshold) / 2).setScale(0, BigDecimal.RoundingMode.DOWN)
        (rates.personalAllowance - reduction).max(0)
      else rates.personalAllowance

    val allowanceUsed = taperedPa + blindUsed
    val taxable       = (totalIncome - allowanceUsed).max(0)

    val basicSlice  = taxable.min(rates.basicRateBand)
    val higherSlice = (taxable - rates.basicRateBand).max(0)
    val taxDue =
      (basicSlice * rates.basicRate + higherSlice * rates.higherRate)
        .setScale(2, BigDecimal.RoundingMode.HALF_UP)

    LiabilityResult(
      taxYear              = rates.taxYear,
      ratePackVersion      = rates.version,
      totalIncome          = totalIncome,
      personalAllowanceUsed = taperedPa,
      blindAllowanceUsed   = blindUsed,
      taxableIncome        = taxable,
      taxDue               = taxDue,
      breakdown = Seq(
        "Self-employment profit" -> gbp(seProfit),
        "UK property income" -> gbp(property),
        "Dividend income" -> gbp(dividends),
        "Total income" -> gbp(totalIncome),
        s"Personal allowance (${rates.taxYear})" -> gbp(taperedPa),
        "Blind Person’s Allowance" -> gbp(blindUsed),
        "Taxable income" -> gbp(taxable),
        "Estimated income tax" -> gbp(taxDue)
      )
    )

  private def money(answers: Map[String, String], key: String): BigDecimal =
    answers
      .get(key)
      .map(_.replace(",", "").trim)
      .filter(_.nonEmpty)
      .flatMap(s => scala.util.Try(BigDecimal(s)).toOption)
      .getOrElse(BigDecimal(0))

  private def gbp(amount: BigDecimal): String =
    f"£${amount.setScale(2, BigDecimal.RoundingMode.HALF_UP)}%,.2f"

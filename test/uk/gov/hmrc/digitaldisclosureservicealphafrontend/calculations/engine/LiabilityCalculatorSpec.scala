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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.ConfigValidator
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{RateDefinition, RateKind, TaxBandRule}

class LiabilityCalculatorSpec extends AnyWordSpec with Matchers:

  private val rates2017 = ratesFor("2017-18")

  /** £16,500 total income: 4 × £250 savings and investments, £5,500 trading profit, £10,000 property profit. */
  private def designFocusIncome(taxYear: String): Map[String, String] =
    Map(
      "bankInterest" -> "250",
      "dividends" -> "250",
      "foreignIncome" -> "250",
      "trustsEstates" -> "250",
      "selfEmploymentTurnover" -> "6000",
      "selfEmploymentExpenses" -> "500",
      "propertyIncome" -> "12000",
      "propertyExpenses" -> "2000"
    ).map((field, value) => s"${field}__$taxYear" -> value)

  "LiabilityCalculator.calculate" should:
    "sum estimates across the selected tax years using each year's rates" in:
      val answers =
        Map("taxYears" -> "2015-16,2017-18", "blindPersonEligible" -> "no") ++
          designFocusIncome("2015-16") ++ designFocusIncome("2017-18")
      val result = LiabilityCalculator.calculate(catalog, spec, answers)

      result.years.map(_.taxYear) shouldBe Seq("2015-16", "2017-18")
      result.calculationVersion shouldBe "1.4.2"
      result.years.map(_.totalIncome) shouldBe Seq(BigDecimal(16500), BigDecimal(16500))
      // 2015-16: (16,500 − 10,600) × 20%; 2017-18: (16,500 − 11,500) × 20%
      result.years.map(_.taxDue) shouldBe Seq(BigDecimal("1180.00"), BigDecimal("1000.00"))
      result.totalTaxDue shouldBe BigDecimal("2180.00")

    "use every catalogue year when none are selected" in:
      LiabilityCalculator.calculate(catalog, spec, Map.empty).years.map(_.taxYear) shouldBe catalog.taxYears

  "LiabilityCalculator.calculateYear" should:
    "deduct each tax-paid field in the spec from the banded estimate" in:
      val base = Map("dividends" -> "20000", "blindPersonEligible" -> "no")
      val withoutPaid = LiabilityCalculator.calculateYear(rates2017, spec, base)
      val withPaid = LiabilityCalculator.calculateYear(
        rates2017,
        spec,
        base ++ Map("taxAlreadyPaid" -> "500", "bankInterestTaxDeducted" -> "100")
      )
      withPaid.taxDue shouldBe (withoutPaid.taxDue - BigDecimal(600))

      val paidIgnored = spec.copy(taxPaidFields = Seq("taxAlreadyPaid"))
      LiabilityCalculator.calculateYear(rates2017, paidIgnored, base ++ Map("taxAlreadyPaid" -> "500", "bankInterestTaxDeducted" -> "100"))
        .taxDue shouldBe (withoutPaid.taxDue - BigDecimal(500))

    "honour calculation-spec changes such as removing Blind Person's Allowance" in:
      val answers = Map("blindPersonEligible" -> "yes", "selfEmploymentTurnover" -> "20000", "selfEmploymentExpenses" -> "2000")
      val noBlind = spec.copy(allowances = spec.allowances.filterNot(_.id == "blindPersonsAllowance"))

      val withBlind = LiabilityCalculator.calculateYear(rates2017, spec, answers)
      val withoutBlind = LiabilityCalculator.calculateYear(rates2017, noBlind, answers)
      withBlind.allowancesUsed.get("blindPersonsAllowance") shouldBe Some(BigDecimal(2320))
      withoutBlind.allowancesUsed.get("blindPersonsAllowance") shouldBe None
      withoutBlind.taxDue should be > withBlind.taxDue

    "only give a conditional allowance when its condition holds" in:
      val answers = Map("blindPersonEligible" -> "no", "selfEmploymentTurnover" -> "20000")
      LiabilityCalculator.calculateYear(rates2017, spec, answers).allowancesUsed("blindPersonsAllowance") shouldBe BigDecimal(0)

    "taper the personal allowance above the threshold" in:
      val result = LiabilityCalculator.calculateYear(rates2017, spec, Map("employmentIncome" -> "110000"))
      // £1 lost for every £2 over £100,000
      result.allowancesUsed("personalAllowance") shouldBe BigDecimal(6500)

    "charge a new tax band declared only in config" in:
      val withAdditionalRate = catalog.copy(
        rates = catalog.rates ++ Seq(
          RateDefinition("higherRateLimit", "calculations.graph.rateKey.higherRateLimit", RateKind.amount),
          RateDefinition("additionalRate", "calculations.graph.rateKey.additionalRate", RateKind.percentage)
        ),
        years = catalog.years.map(y => y.copy(values = y.values ++ Seq("higherRateLimit" -> BigDecimal(150000), "additionalRate" -> BigDecimal("0.45"))))
      )
      val bands = spec.tax.bands.map(b => if b.rateKey == "higherRate" then b.copy(upToRateKey = Some("higherRateLimit")) else b) :+
        TaxBandRule(rateKey = "additionalRate", label = "Additional_rate")
      val config = ConfigValidator
        .validate(Json.stringify(Json.toJson(withAdditionalRate)), fullConfig.questionJson, Json.stringify(Json.toJson(spec.copy(tax = spec.tax.copy(bands = bands)))))
        .fold(errors => fail(ConfigValidator.formatErrors(errors)), identity)

      val result = LiabilityCalculator.calculateYear(config.rateCatalog.forYear("2017-18").get, config.calculationSpec, Map("dividends" -> "200000"))
      // No personal allowance left at £200,000. £33,500 × 20% + £116,500 × 40% + £50,000 × 45%
      result.taxDue shouldBe BigDecimal("75800.00")

  "LiabilityCalculator.yearFigures" should:
    "explain each step with the same figures it calculated" in:
      val figures = LiabilityCalculator.yearFigures(rates2017, spec, Map("dividends" -> "20000"))
      figures.totalIncome shouldBe BigDecimal(20000)
      figures.taxableIncome shouldBe BigDecimal(8500)
      figures.bands.map(_.tax).sum shouldBe figures.taxBeforePaid
      figures.taxDue shouldBe BigDecimal("1700.00")
      LiabilityExplanations.forYear(figures, spec.tax).last.result shouldBe "£1,700.00"

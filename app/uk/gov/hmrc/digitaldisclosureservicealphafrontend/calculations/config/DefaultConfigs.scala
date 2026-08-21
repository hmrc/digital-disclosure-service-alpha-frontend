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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  AllowanceRule,
  ArchitectureOption,
  CalculationSpec,
  ConfigQuestion,
  IncomeComponent,
  IncomeComponentKind,
  QuestionOption,
  QuestionPack,
  QuestionType,
  RateCatalog,
  RatePack,
  ShowIf,
  TaperRule,
  TaxBandRule,
  TaxRules
}

import play.api.libs.json.Json

/**
  * Defaults aligned to Version 1.4 “Understand tax calculation rules” mural /
  * design-focus scenario (2015–16 through 2017–18).
  *
  * Option 2 mirrors the board’s income + CGT category tree, Approach 1
  * already-declared / tax-paid loop, and key detail screens (employment PAYE,
  * SE business identity, rent-a-room, marriage allowance, CGT disposal inputs)
  * at a GDS one-question-per-page level.
  */
object DefaultConfigs:

  val rate2015_16: RatePack = RatePack(
    taxYear = "2015-16",
    version = "2015-16.1",
    personalAllowance = BigDecimal(10600),
    taperThreshold = BigDecimal(100000),
    blindPersonsAllowance = BigDecimal(2290),
    basicRateBand = BigDecimal(31785),
    basicRate = BigDecimal("0.20"),
    higherRate = BigDecimal("0.40")
  )

  val rate2016_17: RatePack = RatePack(
    taxYear = "2016-17",
    version = "2016-17.1",
    personalAllowance = BigDecimal(11000),
    taperThreshold = BigDecimal(100000),
    blindPersonsAllowance = BigDecimal(2290),
    basicRateBand = BigDecimal(32000),
    basicRate = BigDecimal("0.20"),
    higherRate = BigDecimal("0.40")
  )

  val rate2017_18: RatePack = RatePack(
    taxYear = "2017-18",
    version = "2017-18.1",
    personalAllowance = BigDecimal(11500),
    taperThreshold = BigDecimal(100000),
    blindPersonsAllowance = BigDecimal(2320),
    basicRateBand = BigDecimal(33500),
    basicRate = BigDecimal("0.20"),
    higherRate = BigDecimal("0.40")
  )

  val defaultRateCatalog: RateCatalog = RateCatalog(
    version = "design-focus-2015-18",
    years = Seq(rate2015_16, rate2016_17, rate2017_18)
  )

  /** @deprecated use defaultRateCatalog — kept for tests that need a single year pack */
  val defaultRatePack: RatePack = rate2017_18

  private val taxYearsQuestion = ConfigQuestion(
    id = QuestionEngine.TaxYearsQuestionId,
    `type` = QuestionType.checkboxes,
    title = "Which_tax_years_does_this_disclosure_relate_to",
    hint = Some("Select_all_that_apply_Options_come_from_the_rate_catalogue_years"),
    required = true,
    optionsFromRates = true
  )

  private val incomeTypeOptions = Seq(
    QuestionOption("employment", "Employment_income"),
    QuestionOption("selfEmployment", "Self_employment_income"),
    QuestionOption("ukProperty", "Income_from_letting_out_property"),
    QuestionOption("pension", "Pension_income"),
    QuestionOption("employmentBenefits", "Benefits_from_employment"),
    QuestionOption("trustsEstates", "Income_from_a_trust"),
    QuestionOption("chargeableEventGains", "Chargeable_event_gains"),
    QuestionOption("partnership", "Partnership_income"),
    QuestionOption("remittanceBasisCharge", "Remittance_basis_charge"),
    QuestionOption("pensionCharges", "Pension_charges"),
    QuestionOption("bankInterest", "Income_from_UK_savings"),
    QuestionOption("dividends", "Dividends_income"),
    QuestionOption("foreignIncome", "Foreign_income_or_dividends"),
    QuestionOption("otherUkIncome", "Other_UK_income"),
    QuestionOption("capitalGains", "Capital_gains")
  )

  private val capitalGainTypeOptions = Seq(
    QuestionOption("cgtResidentialProperty", "Gains_from_residential_property"),
    QuestionOption("cgtNonResidentialProperty", "Gains_from_non_residential_property"),
    QuestionOption("cgtPersonalAssets", "Gains_from_personal_assets"),
    QuestionOption("cgtSharesInvestments", "Gains_from_shares_and_investments"),
    QuestionOption("cgtBusinessAssets", "Gains_from_business_assets")
  )

  private def whenIncome(contains: String): Option[ShowIf] =
    Some(ShowIf(field = "incomeTypes", contains = Some(contains)))

  private def whenEquals(field: String, value: String): Option[ShowIf] =
    Some(ShowIf(field = field, equals = Some(value)))

  private def whenContains(field: String, value: String): Option[ShowIf] =
    Some(ShowIf(field = field, contains = Some(value)))

  private def whenNotEquals(field: String, value: String): Option[ShowIf] =
    Some(ShowIf(field = field, notEquals = Some(value)))

  private def text(
    id     : String,
    title  : String,
    showIf : Option[ShowIf] = None,
    hint   : Option[String] = None,
    perYear: Boolean = false
  ): ConfigQuestion =
    ConfigQuestion(
      id = id,
      `type` = QuestionType.text,
      title = title,
      hint = hint,
      showIf = showIf,
      perTaxYear = perYear
    )

  private def currency(
    id      : String,
    title   : String,
    feed    : String,
    showIf  : Option[ShowIf] = None,
    hint    : Option[String] = None,
    perYear : Boolean = true
  ): ConfigQuestion =
    ConfigQuestion(
      id = id,
      `type` = QuestionType.currency,
      title = title,
      hint = hint,
      showIf = showIf,
      feeds = Some(feed),
      perTaxYear = perYear
    )

  private def yesNo(
    id     : String,
    title  : String,
    showIf : Option[ShowIf] = None,
    hint   : Option[String] = None,
    perYear: Boolean = false
  ): ConfigQuestion =
    ConfigQuestion(
      id = id,
      `type` = QuestionType.yesNo,
      title = title,
      hint = hint,
      showIf = showIf,
      perTaxYear = perYear
    )

  /** Fixed journey for Option 1 — design-focus categories without branching. */
  val ratesOnlyQuestionPack: QuestionPack = QuestionPack(
    id = "rates-only-fixed",
    title = "Fixed_disclosure_questions_Option_1",
    questions = Seq(
      taxYearsQuestion,
      ConfigQuestion(
        id = "ageBand",
        `type` = QuestionType.singleChoice,
        title = "What_was_your_age_band_for_the_tax_years_you_are_disclosing",
        options = Some(
          Seq(
            QuestionOption("under65", "Aged_16_to_64"),
            QuestionOption("65to74", "Aged_65_to_74"),
            QuestionOption("75plus", "Aged_75_or_over")
          )
        )
      ),
      yesNo("marriedOrCivilPartnership", "Were_you_married_or_in_a_civil_partnership_in_any_of_those_tax_years"),
      yesNo(
        "blindPersonEligible",
        "Have_you_been_eligible_to_receive_Blind_Persons_Allowance",
        hint = Some("Applied_for_each_selected_tax_year_using_that_years_allowance_amount_from_the_rate_catalogue")
      ),
      currency("alreadyDeclaredIncome", "How_much_income_had_you_already_declared_for_this_tax_year", "declared.income"),
      currency("taxAlreadyPaid", "How_much_tax_had_you_already_paid_for_this_tax_year", "declared.taxPaid"),
      currency("bankInterest", "How_much_bank_and_building_society_interest_do_you_need_to_disclose", "undisclosed.bankInterest"),
      currency("dividends", "How_much_dividend_income_from_UK_companies_do_you_need_to_disclose", "undisclosed.dividends"),
      currency("foreignIncome", "How_much_foreign_income_or_dividends_do_you_need_to_disclose", "undisclosed.foreignIncome"),
      currency("trustsEstates", "How_much_income_from_trusts_or_estates_do_you_need_to_disclose", "undisclosed.trustsEstates"),
      currency(
        "selfEmploymentTurnover",
        "How_much_income_in_total_did_you_receive_from_self_employment",
        "undisclosed.selfEmployment",
        hint = Some("Include_all_self_employment_income_for_this_tax_year_before_expenses")
      ),
      currency(
        "selfEmploymentExpenses",
        "How_much_self_employment_allowable_expenses_do_you_want_to_deduct",
        "undisclosed.selfEmploymentExpenses"
      ),
      currency("propertyIncome", "How_much_UK_property_rental_income_do_you_need_to_disclose", "undisclosed.ukProperty"),
      currency(
        "propertyExpenses",
        "How_much_UK_property_allowable_expenses_do_you_want_to_deduct",
        "undisclosed.ukPropertyExpenses"
      ),
      currency(
        "capitalGains",
        "How_much_capital_gains_do_you_need_to_disclose",
        "undisclosed.capitalGains",
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      )
    )
  )

  /**
    * Option 2 — fuller mural journey:
    * shared circumstances → income/CGT category tree → per-lane detail →
    * Approach 1 already-declared / tax-paid amounts.
    */
  val ratesAndQuestionsPack: QuestionPack = QuestionPack(
    id = "design-focus-2015-18",
    title = "Design_focus_multi_year_disclosure_questions",
    questions = Seq(
      taxYearsQuestion,
      ConfigQuestion(
        id = "incomeTypes",
        `type` = QuestionType.checkboxes,
        title = "Which_categories_of_income_or_gains_do_you_need_to_disclose",
        hint = Some("Select_all_that_apply_Based_on_the_Version_1_4_onshore_liability_types"),
        options = Some(incomeTypeOptions)
      ),
      ConfigQuestion(
        id = "ageBand",
        `type` = QuestionType.singleChoice,
        title = "What_was_your_age_band_for_the_tax_years_you_are_disclosing",
        hint = Some("Used_when_allocating_Personal_Allowance_and_Married_Couples_Allowance"),
        options = Some(
          Seq(
            QuestionOption("under65", "Aged_16_to_64"),
            QuestionOption("65to74", "Aged_65_to_74"),
            QuestionOption("75plus", "Aged_75_or_over")
          )
        )
      ),
      yesNo(
        "marriedOrCivilPartnership",
        "Were_you_married_or_in_a_civil_partnership_in_any_of_those_tax_years",
        hint = Some("Needed_to_consider_Married_Couples_Allowance_on_older_tax_years")
      ),
      yesNo(
        "marriageAllowanceClaim",
        "Do_you_want_to_claim_Marriage_Allowance_for_any_of_those_tax_years",
        whenEquals("marriedOrCivilPartnership", "yes"),
        hint = Some("Marriage_Allowance_needs_different_treatment_because_it_affects_two_people")
      ),
      yesNo(
        "marriageAllowanceAlreadyInTaxCode",
        "Is_Marriage_Allowance_already_in_your_tax_code",
        whenEquals("marriageAllowanceClaim", "yes")
      ),
      yesNo(
        "blindPersonEligible",
        "Have_you_been_eligible_to_receive_Blind_Persons_Allowance",
        hint = Some("Applied_for_each_selected_tax_year_using_that_years_allowance_amount_from_the_rate_catalogue")
      ),
      yesNo(
        "blindAllowanceAlreadyClaimed",
        "Have_you_already_claimed_Blind_Persons_Allowance_through_your_tax_code",
        whenEquals("blindPersonEligible", "yes")
      ),

      // Approach 1 — income / tax already on the return
      currency(
        "alreadyDeclaredIncome",
        "How_much_income_had_you_already_declared_for_this_tax_year",
        "declared.income",
        hint = Some("Include_income_already_reported_to_HMRC_for_this_tax_year")
      ),
      currency(
        "taxAlreadyPaid",
        "How_much_tax_had_you_already_paid_for_this_tax_year",
        "declared.taxPaid",
        hint = Some("Include_PAYE_and_other_tax_already_paid_or_withheld")
      ),

      // Employment
      text(
        "employerName",
        "What_is_the_name_of_your_employer",
        whenIncome("employment"),
        perYear = true
      ),
      text(
        "payeReference",
        "What_is_the_employer_PAYE_reference",
        whenIncome("employment"),
        hint = Some("It_is_usually_on_your_P60_or_payslip"),
        perYear = true
      ),
      currency(
        "employmentIncome",
        "How_much_employment_income_do_you_need_to_disclose",
        "undisclosed.employment",
        whenIncome("employment")
      ),
      currency(
        "employmentTaxDeducted",
        "How_much_tax_was_deducted_from_that_employment_income",
        "undisclosed.employmentTax",
        whenIncome("employment")
      ),

      // Self-employment lane
      text(
        "selfEmploymentBusinessName",
        "What_is_the_name_of_your_self_employment_business",
        whenIncome("selfEmployment"),
        perYear = true
      ),
      text(
        "selfEmploymentDescription",
        "What_does_your_self_employment_business_do",
        whenIncome("selfEmployment"),
        hint = Some("For_example_wedding_photographer_or_selling_items_online"),
        perYear = true
      ),
      yesNo(
        "selfEmploymentStillTrading",
        "Is_the_business_still_trading",
        whenIncome("selfEmployment"),
        perYear = true
      ),
      text(
        "selfEmploymentCeasedDate",
        "When_did_the_business_cease",
        whenEquals("selfEmploymentStillTrading", "no"),
        hint = Some("Enter_the_month_and_year"),
        perYear = true
      ),
      yesNo(
        "selfEmploymentUseTradingAllowance",
        "Do_you_want_to_claim_the_trading_allowance_instead_of_expenses",
        whenIncome("selfEmployment"),
        hint = Some("Trading_allowance_is_up_to_1000_from_2017_18"),
        perYear = true
      ),
      currency(
        "selfEmploymentTurnover",
        "How_much_income_in_total_did_you_receive_from_all_your_self_employment_businesses",
        "undisclosed.selfEmployment",
        whenIncome("selfEmployment"),
        hint = Some("Include_all_self_employment_income_for_this_tax_year_before_expenses")
      ),
      currency(
        "selfEmploymentExpenses",
        "How_much_self_employment_allowable_expenses_do_you_want_to_deduct",
        "undisclosed.selfEmploymentExpenses",
        whenEquals("selfEmploymentUseTradingAllowance", "no")
      ),
      currency(
        "selfEmploymentTradingAllowance",
        "How_much_trading_allowance_do_you_want_to_claim",
        "undisclosed.selfEmploymentTradingAllowance",
        whenEquals("selfEmploymentUseTradingAllowance", "yes"),
        hint = Some("Enter_up_to_1000_or_your_turnover_if_lower")
      ),

      // Property lane
      ConfigQuestion(
        id = "propertyType",
        `type` = QuestionType.singleChoice,
        title = "What_is_the_type_of_UK_property",
        showIf = whenIncome("ukProperty"),
        options = Some(
          Seq(
            QuestionOption("residential", "Residential_property"),
            QuestionOption("fhl", "Furnished_holiday_let"),
            QuestionOption("commercial", "Commercial_property"),
            QuestionOption("rentARoom", "Rent_a_room")
          )
        ),
        perTaxYear = true
      ),
      yesNo(
        "claimRentARoomRelief",
        "Do_you_want_to_claim_Rent_a_Room_relief",
        whenEquals("propertyType", "rentARoom"),
        hint = Some("Rent_a_Room_relief_is_up_to_7500_per_year"),
        perYear = true
      ),
      currency(
        "rentARoomRelief",
        "How_much_Rent_a_Room_relief_do_you_want_to_claim",
        "undisclosed.rentARoomRelief",
        whenEquals("claimRentARoomRelief", "yes"),
        hint = Some("Enter_up_to_7500_or_your_rent_a_room_income_if_lower")
      ),
      yesNo(
        "propertyUseAllowance",
        "Do_you_want_to_claim_the_property_allowance_instead_of_expenses",
        whenNotEquals("propertyType", "rentARoom"),
        hint = Some("Property_allowance_is_up_to_1000"),
        perYear = true
      ),
      currency(
        "propertyIncome",
        "How_much_UK_property_rental_income_do_you_need_to_disclose",
        "undisclosed.ukProperty",
        whenIncome("ukProperty")
      ),
      currency(
        "propertyExpenses",
        "How_much_UK_property_allowable_expenses_do_you_want_to_deduct",
        "undisclosed.ukPropertyExpenses",
        whenEquals("propertyUseAllowance", "no")
      ),
      currency(
        "propertyAllowance",
        "How_much_property_allowance_do_you_want_to_claim",
        "undisclosed.ukPropertyAllowance",
        whenEquals("propertyUseAllowance", "yes"),
        hint = Some("Enter_up_to_1000_or_your_property_income_if_lower")
      ),
      currency(
        "propertyFinanceCosts",
        "How_much_residential_property_finance_costs_did_you_pay",
        "undisclosed.ukPropertyFinance",
        whenEquals("propertyType", "residential"),
        hint = Some("Mortgage_interest_and_similar_finance_costs_for_residential_lets")
      ),

      // Pension / benefits / trust / CEG / partnership / remittance / charges / other
      currency("pensionIncome", "How_much_pension_income_do_you_need_to_disclose", "undisclosed.pension", whenIncome("pension")),
      yesNo(
        "pensionTaxTakenOff",
        "Was_tax_taken_off_this_pension_income",
        whenIncome("pension"),
        perYear = true
      ),
      currency(
        "pensionTaxDeducted",
        "How_much_tax_was_taken_off_this_pension_income",
        "undisclosed.pensionTax",
        whenEquals("pensionTaxTakenOff", "yes")
      ),
      currency(
        "employmentBenefits",
        "How_much_employment_benefits_do_you_need_to_disclose",
        "undisclosed.employmentBenefits",
        whenIncome("employmentBenefits")
      ),
      currency(
        "trustsEstates",
        "How_much_income_from_trusts_or_estates_do_you_need_to_disclose",
        "undisclosed.trustsEstates",
        whenIncome("trustsEstates")
      ),
      currency(
        "chargeableEventGains",
        "How_much_chargeable_event_gains_do_you_need_to_disclose",
        "undisclosed.chargeableEventGains",
        whenIncome("chargeableEventGains")
      ),
      currency(
        "partnershipIncome",
        "How_much_partnership_income_do_you_need_to_disclose",
        "undisclosed.partnership",
        whenIncome("partnership")
      ),
      currency(
        "remittanceBasisCharge",
        "How_much_remittance_basis_charge_do_you_need_to_disclose",
        "undisclosed.remittanceBasisCharge",
        whenIncome("remittanceBasisCharge")
      ),
      currency(
        "pensionCharges",
        "How_much_pension_charges_do_you_need_to_disclose",
        "undisclosed.pensionCharges",
        whenIncome("pensionCharges")
      ),
      text(
        "otherUkIncomeDescription",
        "Describe_the_other_UK_income_you_need_to_disclose",
        whenIncome("otherUkIncome"),
        hint = Some("Use_this_for_amounts_that_do_not_fit_the_other_categories"),
        perYear = true
      ),
      currency(
        "otherUkIncome",
        "How_much_other_UK_income_do_you_need_to_disclose",
        "undisclosed.otherUkIncome",
        whenIncome("otherUkIncome")
      ),

      // UK savings / interest
      currency(
        "bankInterest",
        "How_much_bank_and_building_society_interest_do_you_need_to_disclose",
        "undisclosed.bankInterest",
        whenIncome("bankInterest")
      ),
      yesNo(
        "bankInterestTaxTakenOff",
        "Was_tax_taken_off_this_interest_before_you_received_it",
        whenIncome("bankInterest"),
        perYear = true
      ),
      currency(
        "bankInterestTaxDeducted",
        "How_much_tax_was_taken_off_this_interest",
        "undisclosed.bankInterestTax",
        whenEquals("bankInterestTaxTakenOff", "yes")
      ),

      // Dividends
      currency(
        "dividends",
        "How_much_dividend_income_from_UK_companies_do_you_need_to_disclose",
        "undisclosed.dividends",
        whenIncome("dividends")
      ),

      // Foreign
      currency(
        "foreignIncome",
        "How_much_foreign_income_or_dividends_do_you_need_to_disclose",
        "undisclosed.foreignIncome",
        whenIncome("foreignIncome")
      ),
      currency(
        "foreignTaxPaid",
        "How_much_foreign_tax_did_you_pay_on_that_income",
        "undisclosed.foreignTax",
        whenIncome("foreignIncome")
      ),

      // Capital gains lane
      ConfigQuestion(
        id = "capitalGainTypes",
        `type` = QuestionType.checkboxes,
        title = "Which_types_of_capital_gains_do_you_need_to_disclose",
        hint = Some("Select_all_that_apply"),
        showIf = whenIncome("capitalGains"),
        options = Some(capitalGainTypeOptions)
      ),
      text(
        "cgtResidentialDisposalDate",
        "When_did_you_dispose_of_the_residential_property",
        whenContains("capitalGainTypes", "cgtResidentialProperty"),
        hint = Some("Enter_the_day_month_and_year"),
        perYear = true
      ),
      currency(
        "cgtResidentialProceeds",
        "How_much_were_the_disposal_proceeds_for_the_residential_property",
        "undisclosed.cgt.residential.proceeds",
        whenContains("capitalGainTypes", "cgtResidentialProperty")
      ),
      currency(
        "cgtResidentialCosts",
        "How_much_were_the_allowable_costs_for_the_residential_property",
        "undisclosed.cgt.residential.costs",
        whenContains("capitalGainTypes", "cgtResidentialProperty"),
        hint = Some("Include_purchase_costs_improvement_costs_and_selling_costs")
      ),
      currency(
        "cgtResidentialProperty",
        "How_much_gain_from_residential_property_do_you_need_to_disclose",
        "undisclosed.cgt.residential",
        whenContains("capitalGainTypes", "cgtResidentialProperty"),
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      ),
      currency(
        "cgtNonResidentialProperty",
        "How_much_gain_from_non_residential_property_do_you_need_to_disclose",
        "undisclosed.cgt.nonResidential",
        whenContains("capitalGainTypes", "cgtNonResidentialProperty"),
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      ),
      currency(
        "cgtPersonalAssets",
        "How_much_gain_from_personal_assets_do_you_need_to_disclose",
        "undisclosed.cgt.personalAssets",
        whenContains("capitalGainTypes", "cgtPersonalAssets"),
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      ),
      currency(
        "cgtSharesInvestments",
        "How_much_gain_from_shares_and_investments_do_you_need_to_disclose",
        "undisclosed.cgt.shares",
        whenContains("capitalGainTypes", "cgtSharesInvestments"),
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      ),
      currency(
        "cgtBusinessAssets",
        "How_much_gain_from_business_assets_do_you_need_to_disclose",
        "undisclosed.cgt.businessAssets",
        whenContains("capitalGainTypes", "cgtBusinessAssets"),
        hint = Some("Collected_for_the_disclosure_journey_Not_included_in_the_income_tax_estimate")
      ),

      // Reliefs (Approach 1 A5–A6, simplified)
      yesNo(
        "claimAnyReliefs",
        "Do_you_want_to_claim_any_other_tax_reliefs_for_this_tax_year",
        hint = Some("For_example_loss_relief_or_other_allowable_reliefs"),
        perYear = true
      ),
      currency(
        "otherReliefs",
        "How_much_other_tax_relief_do_you_want_to_claim",
        "undisclosed.otherReliefs",
        whenEquals("claimAnyReliefs", "yes")
      )
    )
  )

  def prettyRateJson(catalog: RateCatalog = defaultRateCatalog): String =
    Json.prettyPrint(Json.toJson(catalog))

  def prettyQuestionJson(pack: QuestionPack): String =
    Json.prettyPrint(Json.toJson(pack))

  def prettyCalculationJson(spec: CalculationSpec = defaultCalculationSpec): String =
    Json.prettyPrint(Json.toJson(spec))

  /**
    * Income-tax estimate for disclosed amounts. Capital gains are collected for
    * design fidelity but are not banded here. Tax already paid (and tax taken
    * off at source) is deducted from the banded estimate. Self-employment /
    * property deductions prefer expenses, else allowance.
    */
  val defaultCalculationSpec: CalculationSpec = CalculationSpec(
    id = "income-tax-design-focus",
    version = "1.4.2",
    description = Some(
      "Design-focus prototype liability (v1.4.2): sum additional income from the mural category tree; deduct SE/property expenses or allowances; apply personal allowance (with taper) and optional Blind Person’s Allowance; apply basic/higher rate bands; then deduct tax already paid / taken off at source. Capital gains and foreign tax credits are collected in the journey but are not applied in this simplified estimate."
    ),
    incomeComponents = Seq(
      IncomeComponent(
        id = "employmentIncome",
        label = "Employment_income",
        kind = IncomeComponentKind.amount,
        field = Some("employmentIncome")
      ),
      IncomeComponent(
        id = "selfEmploymentProfit",
        label = "Self_employment_profit",
        kind = IncomeComponentKind.net,
        grossField = Some("selfEmploymentTurnover"),
        deductField = Some("selfEmploymentExpenses"),
        altDeductField = Some("selfEmploymentTradingAllowance"),
        floorAtZero = true
      ),
      IncomeComponent(
        id = "ukPropertyProfit",
        label = "UK_property_profit",
        kind = IncomeComponentKind.net,
        grossField = Some("propertyIncome"),
        deductField = Some("propertyExpenses"),
        altDeductField = Some("propertyAllowance"),
        floorAtZero = true
      ),
      IncomeComponent(
        id = "pensionIncome",
        label = "Pension_income",
        kind = IncomeComponentKind.amount,
        field = Some("pensionIncome")
      ),
      IncomeComponent(
        id = "employmentBenefits",
        label = "Benefits_from_employment",
        kind = IncomeComponentKind.amount,
        field = Some("employmentBenefits")
      ),
      IncomeComponent(
        id = "trustsEstates",
        label = "Income_from_a_trust",
        kind = IncomeComponentKind.amount,
        field = Some("trustsEstates")
      ),
      IncomeComponent(
        id = "chargeableEventGains",
        label = "Chargeable_event_gains",
        kind = IncomeComponentKind.amount,
        field = Some("chargeableEventGains")
      ),
      IncomeComponent(
        id = "partnershipIncome",
        label = "Partnership_income",
        kind = IncomeComponentKind.amount,
        field = Some("partnershipIncome")
      ),
      IncomeComponent(
        id = "remittanceBasisCharge",
        label = "Remittance_basis_charge",
        kind = IncomeComponentKind.amount,
        field = Some("remittanceBasisCharge")
      ),
      IncomeComponent(
        id = "pensionCharges",
        label = "Pension_charges",
        kind = IncomeComponentKind.amount,
        field = Some("pensionCharges")
      ),
      IncomeComponent(
        id = "bankInterest",
        label = "Income_from_UK_savings",
        kind = IncomeComponentKind.amount,
        field = Some("bankInterest")
      ),
      IncomeComponent(
        id = "dividends",
        label = "Dividends_income",
        kind = IncomeComponentKind.amount,
        field = Some("dividends")
      ),
      IncomeComponent(
        id = "foreignIncome",
        label = "Foreign_income_or_dividends",
        kind = IncomeComponentKind.amount,
        field = Some("foreignIncome")
      ),
      IncomeComponent(
        id = "otherUkIncome",
        label = "Other_UK_income",
        kind = IncomeComponentKind.amount,
        field = Some("otherUkIncome")
      )
    ),
    allowances = Seq(
      AllowanceRule(
        id = "personalAllowance",
        label = "Personal_allowance",
        kind = AllowanceKind.personalAllowance,
        rateKey = "personalAllowance",
        taper = Some(
          TaperRule(
            thresholdRateKey = "taperThreshold",
            reduceBy = BigDecimal(1),
            forEvery = BigDecimal(2)
          )
        )
      ),
      AllowanceRule(
        id = "blindPersonsAllowance",
        label = "Blind_Persons_Allowance",
        kind = AllowanceKind.conditionalAmount,
        rateKey = "blindPersonsAllowance",
        when = Some(ShowIf(field = "blindPersonEligible", equals = Some("yes")))
      )
    ),
    tax = TaxRules(
      bands = Seq(
        TaxBandRule(rateKey = "basicRate", label = "Basic_rate", upToRateKey = Some("basicRateBand")),
        TaxBandRule(rateKey = "higherRate", label = "Higher_rate")
      ),
      scale = 2,
      rounding = "halfUp"
    )
  )

  final case class OptionDefaults(
    rateJson        : String,
    questionJson    : String,
    calculationJson : String,
    catalog         : RateCatalog,
    questions       : QuestionPack,
    calculation     : CalculationSpec
  )

  def defaultsFor(option: ArchitectureOption): OptionDefaults =
    val calcJson = prettyCalculationJson()
    option match
      case ArchitectureOption.RatesOnly =>
        OptionDefaults(
          rateJson = prettyRateJson(),
          questionJson = prettyQuestionJson(ratesOnlyQuestionPack),
          calculationJson = calcJson,
          catalog = defaultRateCatalog,
          questions = ratesOnlyQuestionPack,
          calculation = defaultCalculationSpec
        )
      case ArchitectureOption.RatesAndQuestions | ArchitectureOption.FullEngine =>
        OptionDefaults(
          rateJson = prettyRateJson(),
          questionJson = prettyQuestionJson(ratesAndQuestionsPack),
          calculationJson = calcJson,
          catalog = defaultRateCatalog,
          questions = ratesAndQuestionsPack,
          calculation = defaultCalculationSpec
        )

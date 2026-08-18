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

import play.api.libs.json.Json

object DefaultConfigs:

  val defaultRatePack: RatePack = RatePack(
    taxYear               = "2017-18",
    version               = "2017-18.1",
    personalAllowance     = BigDecimal(11500),
    taperThreshold        = BigDecimal(100000),
    blindPersonsAllowance = BigDecimal(2320),
    basicRateBand         = BigDecimal(33500),
    basicRate             = BigDecimal("0.20"),
    higherRate            = BigDecimal("0.40")
  )

  /** Fixed journey used when demonstrating Option 1 (rates only). */
  val ratesOnlyQuestionPack: QuestionPack = QuestionPack(
    id = "rates-only-fixed",
    title = "Fixed disclosure questions (Option 1)",
    questions = Seq(
      ConfigQuestion(
        id = "blindPersonEligible",
        `type` = QuestionType.yesNo,
        title = "Have you been eligible to receive Blind Person’s Allowance?",
        hint = Some("You can claim if you have a certificate saying you are blind or severely sight impaired, or you are registered with your local council.")
      ),
      ConfigQuestion(
        id = "selfEmploymentTurnover",
        `type` = QuestionType.currency,
        title = "How much income in total did you receive from self-employment?",
        hint = Some("Include all self-employment income for this tax year before expenses."),
        feeds = Some("undisclosed.selfEmployment")
      ),
      ConfigQuestion(
        id = "selfEmploymentExpenses",
        `type` = QuestionType.currency,
        title = "How much allowable expenses do you want to deduct?",
        feeds = Some("undisclosed.selfEmploymentExpenses")
      ),
      ConfigQuestion(
        id = "dividends",
        `type` = QuestionType.currency,
        title = "How much dividend income do you need to disclose?",
        feeds = Some("undisclosed.dividends")
      )
    )
  )

  /** Editable mural-lite pack for Option 2. */
  val ratesAndQuestionsPack: QuestionPack = QuestionPack(
    id = "mural-lite-2017-18",
    title = "Mural-lite disclosure questions",
    questions = Seq(
      ConfigQuestion(
        id = "incomeTypes",
        `type` = QuestionType.checkboxes,
        title = "What type of onshore income do you need to disclose?",
        hint = Some("Select all that apply"),
        options = Some(
          Seq(
            QuestionOption("selfEmployment", "Self-employment"),
            QuestionOption("ukProperty", "UK property let"),
            QuestionOption("dividends", "Dividends")
          )
        )
      ),
      ConfigQuestion(
        id = "blindPersonEligible",
        `type` = QuestionType.yesNo,
        title = "Have you been eligible to receive Blind Person’s Allowance?",
        hint = Some("Verification can be handled during case review — this playground only uses the answer for the allowance amount.")
      ),
      ConfigQuestion(
        id = "selfEmploymentTurnover",
        `type` = QuestionType.currency,
        title = "How much income in total did you receive from all your self-employment businesses?",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("selfEmployment"))),
        feeds = Some("undisclosed.selfEmployment")
      ),
      ConfigQuestion(
        id = "selfEmploymentExpenses",
        `type` = QuestionType.currency,
        title = "How much allowable expenses do you want to deduct?",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("selfEmployment"))),
        feeds = Some("undisclosed.selfEmploymentExpenses")
      ),
      ConfigQuestion(
        id = "propertyIncome",
        `type` = QuestionType.currency,
        title = "How much UK property income do you need to disclose?",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("ukProperty"))),
        feeds = Some("undisclosed.ukProperty")
      ),
      ConfigQuestion(
        id = "propertyType",
        `type` = QuestionType.singleChoice,
        title = "What is the type of UK property let?",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("ukProperty"))),
        options = Some(
          Seq(
            QuestionOption("residential", "Residential property"),
            QuestionOption("fhl", "Furnished holiday let"),
            QuestionOption("rentARoom", "Letting part of your own home")
          )
        )
      ),
      ConfigQuestion(
        id = "dividends",
        `type` = QuestionType.currency,
        title = "How much dividend income do you need to disclose?",
        showIf = Some(ShowIf(field = "incomeTypes", contains = Some("dividends"))),
        feeds = Some("undisclosed.dividends")
      )
    )
  )

  def prettyRateJson(pack: RatePack = defaultRatePack): String =
    Json.prettyPrint(Json.toJson(pack))

  def prettyQuestionJson(pack: QuestionPack): String =
    Json.prettyPrint(Json.toJson(pack))

  def defaultsFor(option: PlaygroundOption): (String, String, RatePack, QuestionPack) =
    option match
      case PlaygroundOption.RatesOnly =>
        (
          prettyRateJson(),
          prettyQuestionJson(ratesOnlyQuestionPack),
          defaultRatePack,
          ratesOnlyQuestionPack
        )
      case PlaygroundOption.RatesAndQuestions | PlaygroundOption.FullEngine =>
        (
          prettyRateJson(),
          prettyQuestionJson(ratesAndQuestionsPack),
          defaultRatePack,
          ratesAndQuestionsPack
        )

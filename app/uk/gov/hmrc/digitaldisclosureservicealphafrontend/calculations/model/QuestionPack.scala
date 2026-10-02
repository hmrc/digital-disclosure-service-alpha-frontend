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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model

import play.api.libs.json.{Format, Json}

enum QuestionType:
  case yesNo, text, currency, singleChoice, checkboxes

object QuestionType:
  given Format[QuestionType] = EnumJson.format(QuestionType.values, "question type")

final case class QuestionOption(
  value: String,
  /** Message key: English words joined with underscores (resolved via messages / messages.cy). */
  label: String
)

object QuestionOption:
  given Format[QuestionOption] = Json.format[QuestionOption]

final case class ShowIf(
  field    : String,
  equals   : Option[String] = None,
  contains : Option[String] = None,
  notEquals: Option[String] = None
):
  /** Whether the rule holds for an answer's values (a checkbox answer has several). An unanswered field never matches. */
  def matches(values: Seq[String]): Boolean =
    val positive = equals.toSeq ++ contains.toSeq
    if positive.isEmpty && notEquals.isEmpty then true
    else if values.isEmpty then false
    else (positive.isEmpty || positive.exists(values.contains)) && notEquals.forall(v => !values.contains(v))

object ShowIf:
  given Format[ShowIf] = Json.format[ShowIf]

final case class ConfigQuestion(
  id              : String,
  `type`          : QuestionType,
  /** Message key: English words joined with underscores. */
  title           : String,
  /** Optional message key for the hint. */
  hint            : Option[String] = None,
  options         : Option[Seq[QuestionOption]] = None,
  required        : Boolean = true,
  showIf          : Option[ShowIf] = None,
  feeds           : Option[String] = None,
  /** When true, one page is generated per selected tax year. */
  perTaxYear      : Boolean = false,
  /** When true, checkbox/radio options are taken from the rate catalogue years. */
  optionsFromRates: Boolean = false
):
  def questionType: QuestionType = `type`

object ConfigQuestion:
  given Format[ConfigQuestion] = Json.using[Json.WithDefaultValues].format[ConfigQuestion]

final case class QuestionPack(
  id       : String,
  /** Message key: English words joined with underscores. */
  title    : String,
  questions: Seq[ConfigQuestion],
  taskList : TaskListConfig
)

object QuestionPack:
  given Format[QuestionPack] = Json.format[QuestionPack]

  /** Every pack asks this; its options come from the rate catalogue and it drives per-year questions. */
  val TaxYearsQuestionId = "taxYears"

/** A question template resolved for the current answers (and optional tax year). */
final case class ResolvedQuestion(
  template: ConfigQuestion,
  id      : String,
  title   : String,
  hint    : Option[String] = None,
  taxYear : Option[String] = None,
  options : Seq[QuestionOption] = Nil
):
  def questionType: QuestionType = template.questionType
  def required    : Boolean = template.required
  def feeds       : Option[String] = template.feeds
  def showIf      : Option[ShowIf] = template.showIf

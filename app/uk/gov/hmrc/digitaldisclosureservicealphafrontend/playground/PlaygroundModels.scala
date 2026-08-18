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

import play.api.libs.json.*

enum PlaygroundOption(val id: String, val title: String, val summary: String):
  case RatesOnly
      extends PlaygroundOption(
        "rates-only",
        "Option 1 — Rates config, coded questions",
        "Yearly allowances and bands come from config. The question set is fixed in this mode so you can see a stable journey while experimenting with rates."
      )
  case RatesAndQuestions
      extends PlaygroundOption(
        "rates-and-questions",
        "Option 2 — Rates + question config",
        "Both the rate pack and the question pack are editable. Generated pages follow the question config and stay GDS-shaped."
      )
  case FullEngine
      extends PlaygroundOption(
        "full-engine",
        "Option 3 — Full process engine (not built)",
        "A single process engine owning triage, questions and calc orchestration. Shown for comparison only — not recommended for the liability journey."
      )

object PlaygroundOption:
  def fromId(id: String): Option[PlaygroundOption] =
    values.find(_.id == id)

  val demoOptions: Seq[PlaygroundOption] =
    Seq(RatesOnly, RatesAndQuestions, FullEngine)

final case class RatePack(
  taxYear              : String,
  version              : String,
  personalAllowance    : BigDecimal,
  taperThreshold       : BigDecimal,
  blindPersonsAllowance: BigDecimal,
  basicRateBand        : BigDecimal,
  basicRate            : BigDecimal,
  higherRate           : BigDecimal
)

object RatePack:
  given Format[RatePack] = Json.format[RatePack]

enum QuestionType:
  case yesNo, text, currency, singleChoice, checkboxes

object QuestionType:
  given Format[QuestionType] = new Format[QuestionType]:
    def reads(json: JsValue): JsResult[QuestionType] =
      json.validate[String].flatMap:
        case "yesNo"        => JsSuccess(QuestionType.yesNo)
        case "text"         => JsSuccess(QuestionType.text)
        case "currency"     => JsSuccess(QuestionType.currency)
        case "singleChoice" => JsSuccess(QuestionType.singleChoice)
        case "checkboxes"   => JsSuccess(QuestionType.checkboxes)
        case other          => JsError(s"Unknown question type: $other")

    def writes(t: QuestionType): JsValue = JsString(t.toString)

final case class QuestionOption(value: String, label: String)

object QuestionOption:
  given Format[QuestionOption] = Json.format[QuestionOption]

final case class ShowIf(field: String, equals: Option[String] = None, contains: Option[String] = None)

object ShowIf:
  given Format[ShowIf] = Json.format[ShowIf]

final case class ConfigQuestion(
  id      : String,
  `type`  : QuestionType,
  title   : String,
  hint    : Option[String] = None,
  options : Option[Seq[QuestionOption]] = None,
  required: Boolean = true,
  showIf  : Option[ShowIf] = None,
  feeds   : Option[String] = None
):
  def questionType: QuestionType = `type`

object ConfigQuestion:
  given Format[ConfigQuestion] = Json.format[ConfigQuestion]

final case class QuestionPack(
  id       : String,
  title    : String,
  questions: Seq[ConfigQuestion]
)

object QuestionPack:
  given Format[QuestionPack] = Json.format[QuestionPack]

final case class PlaygroundConfig(
  option      : PlaygroundOption,
  ratePack    : RatePack,
  questionPack: QuestionPack
)

final case class LiabilityResult(
  taxYear             : String,
  ratePackVersion     : String,
  totalIncome         : BigDecimal,
  personalAllowanceUsed: BigDecimal,
  blindAllowanceUsed  : BigDecimal,
  taxableIncome       : BigDecimal,
  taxDue              : BigDecimal,
  breakdown           : Seq[(String, String)]
)

final case class PlaygroundState(
  id          : String,
  option      : PlaygroundOption,
  rateJson    : String,
  questionJson: String,
  ratePack    : RatePack,
  questionPack: QuestionPack,
  answers     : Map[String, String] = Map.empty
)

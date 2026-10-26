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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{Answers, QuestionType, ResolvedQuestion}

/**
  * Checks a submitted answer before it goes on the log. Errors are message keys. A choice must be one of the
  * question's options, and an amount must be a sensible size: the log keeps what it is given, so it must be valid.
  */
object AnswerValidator:

  /** The largest amount accepted. A huge number costs real CPU to calculate with: 1e8000000 took 56 seconds. */
  val MaxAmount: BigDecimal = BigDecimal("1000000000000")

  def validate(question: ResolvedQuestion, raw: String): Either[String, String] =
    val value = raw.trim
    if question.required && value.isEmpty then Left("calculations.error.required")
    else if value.isEmpty then Right(value)
    else
      question.questionType match
        case QuestionType.currency =>
          Answers.parseAmount(value).filter(a => a.abs <= MaxAmount && a.scale <= 2).map(_ => value).toRight("calculations.error.currency")
        case QuestionType.yesNo =>
          Either.cond(value == "yes" || value == "no", value, "calculations.error.choice")
        case QuestionType.singleChoice =>
          Either.cond(question.options.exists(_.value == value), value, "calculations.error.choice")
        case QuestionType.checkboxes =>
          Either.cond(Answers.splitMulti(value).forall(v => question.options.exists(_.value == v)), value, "calculations.error.choice")
        case QuestionType.text =>
          Right(value)

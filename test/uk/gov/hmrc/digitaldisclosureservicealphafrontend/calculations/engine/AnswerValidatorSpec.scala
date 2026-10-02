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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ConfigQuestion, QuestionType, ResolvedQuestion}

class AnswerValidatorSpec extends AnyWordSpec with Matchers:

  private def question(questionType: QuestionType, required: Boolean = true): ResolvedQuestion =
    val template = ConfigQuestion(id = "q", `type` = questionType, title = "Question", required = required)
    ResolvedQuestion(template = template, id = "q", title = "Question")

  "AnswerValidator.validate" should:
    "trim and accept a valid answer" in:
      AnswerValidator.validate(question(QuestionType.text), "  hello ") shouldBe Right("hello")
      AnswerValidator.validate(question(QuestionType.currency), "1,250.50") shouldBe Right("1,250.50")

    "require an answer to required questions" in:
      AnswerValidator.validate(question(QuestionType.text), "   ") shouldBe Left("calculations.error.required")
      AnswerValidator.validate(question(QuestionType.text, required = false), "") shouldBe Right("")

    "reject amounts that are not numbers" in:
      AnswerValidator.validate(question(QuestionType.currency), "lots") shouldBe Left("calculations.error.currency")
      AnswerValidator.validate(question(QuestionType.currency, required = false), "") shouldBe Right("")

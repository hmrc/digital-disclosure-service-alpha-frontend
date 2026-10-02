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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AnswersSpec extends AnyWordSpec with Matchers:

  "YearAnswers" should:
    "store per-year answers under the question id and tax year" in:
      YearAnswers.key("dividends", "2017-18") shouldBe "dividends__2017-18"
      YearAnswers.parse("dividends__2017-18") shouldBe ("dividends", Some("2017-18"))
      YearAnswers.parse("incomeTypes") shouldBe ("incomeTypes", None)

  "Answers" should:
    "split and join checkbox answers, ignoring blanks" in:
      Answers.splitMulti(" a, b ,,c") shouldBe Seq("a", "b", "c")
      Answers.joinMulti(Seq("a", " ", "b")) shouldBe "a,b"
      Answers.values(Map("incomeTypes" -> "dividends,bankInterest"), "incomeTypes") shouldBe Seq("dividends", "bankInterest")
      Answers.values(Map.empty, "incomeTypes") shouldBe Nil

    "parse amounts with thousands separators" in:
      Answers.parseAmount("1,250.50") shouldBe Some(BigDecimal("1250.50"))
      Answers.parseAmount(" ") shouldBe None
      Answers.parseAmount("lots") shouldBe None

    "prefer the year's answer and treat missing or invalid amounts as £0" in:
      val answers = Map("dividends__2017-18" -> "250", "dividends" -> "100", "bankInterest__2017-18" -> "oops")
      Answers.amountForYear(answers, "dividends", "2017-18") shouldBe BigDecimal(250)
      Answers.amountForYear(answers, "dividends", "2016-17") shouldBe BigDecimal(100)
      Answers.amountForYear(answers, "bankInterest", "2017-18") shouldBe BigDecimal(0)
      Answers.amountForYear(answers, "foreignIncome", "2017-18") shouldBe BigDecimal(0)

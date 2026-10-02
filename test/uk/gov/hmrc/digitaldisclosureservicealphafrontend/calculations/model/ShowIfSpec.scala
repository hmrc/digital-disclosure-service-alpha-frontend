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

class ShowIfSpec extends AnyWordSpec with Matchers:

  "ShowIf.matches" should:
    "match equals and contains against any of the answer's values" in:
      ShowIf("f", equals = Some("yes")).matches(Seq("yes")) shouldBe true
      ShowIf("f", equals = Some("yes")).matches(Seq("no")) shouldBe false
      ShowIf("f", contains = Some("dividends")).matches(Seq("bankInterest", "dividends")) shouldBe true
      ShowIf("f", contains = Some("dividends")).matches(Seq("bankInterest")) shouldBe false

    "match notEquals only when the answer has a different value" in:
      ShowIf("f", notEquals = Some("rentARoom")).matches(Seq("fhl")) shouldBe true
      ShowIf("f", notEquals = Some("rentARoom")).matches(Seq("rentARoom")) shouldBe false

    "never match an unanswered field" in:
      ShowIf("f", equals = Some("yes")).matches(Nil) shouldBe false
      ShowIf("f", notEquals = Some("yes")).matches(Nil) shouldBe false

    "always match a rule with no condition" in:
      ShowIf("f").matches(Nil) shouldBe true

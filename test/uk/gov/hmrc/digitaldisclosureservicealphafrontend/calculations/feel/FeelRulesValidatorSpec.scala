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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{ConfigViolation, DefaultConfigs}

class FeelRulesValidatorSpec extends AnyWordSpec with Matchers:

  private val defaults = DefaultConfigs.feelRules.json

  private def errors(json: String): Seq[ConfigViolation] =
    FeelRulesValidator.validate(json, fullConfig, DefaultConfigs.workedExamples).swap.getOrElse(Nil)

  private def changed(from: String, to: String): String =
    defaults.indexOf(from) should be >= 0
    defaults.replaceFirst(java.util.regex.Pattern.quote(from), java.util.regex.Matcher.quoteReplacement(to))

  "The default FEEL rules" should:
    "be valid against the full question pack, and calculate every worked example" in:
      errors(defaults) shouldBe empty
      DefaultConfigs.feelRules.compiled.steps.size shouldBe 40

  "FeelRulesValidator" should:
    "point at a step that is not valid FEEL" in:
      val found = errors(changed("\"sum(groups.income)\"", "\"sum(groups.income\""))
      found.map(_.path) shouldBe Seq("feelRules.steps[14].feel")
      found.head.message should startWith("Not valid FEEL")

    "reject an answer that is not a question, so a typo cannot silently count as £0" in:
      errors(changed("answers.employmentIncome)", "answers.employmentIncom)")) shouldBe Seq(
        ConfigViolation("feelRules.steps[0].feel", "'answers.employmentIncom' is not a question in the question pack")
      )

    "reject a rate the catalogue does not have" in:
      errors(changed("* rates.basicRate\"", "* rates.basicRat\"")).map(_.message) shouldBe
        Seq("'rates.basicRat' is not a rate in the rate catalogue")

    "only let a step read the steps before it" in:
      errors(changed("\"sum(groups.income)\"", "\"sum(groups.income) + taxDue\"")).map(_.message) shouldBe
        Seq("'taxDue' is worked out later; a step can only use earlier steps")

    "reject a misspelt property instead of ignoring it" in:
      errors(changed("\"format\": \"yesNo\"", "\"fromat\": \"yesNo\"")).map(_.message) should contain("Unexpected property 'fromat'")

    "fail a rule that gives no value for a worked example, naming the step and the year" in:
      val found = errors(changed("\"feel\": \"0.30\" }", "\"feel\": \"0.30 + \\\"thirty\\\"\" }"))
      found.map(_.path).distinct shouldBe Seq("feelRules.steps[29].feel")
      found.head.message should include("Gives no value for the worked example 'simple' in 2015-16")

    "reject outputs that name a step that does not exist" in:
      errors(changed("\"taxDue\": \"taxDue\"", "\"taxDue\": \"tax\"")).map(_.message) shouldBe Seq("'tax' is not a step")

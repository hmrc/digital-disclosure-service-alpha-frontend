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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{
  ConfigJsonHighlight,
  ConfigValidator,
  DefaultConfigs
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.ArchitectureOption

class ConfigJsonHighlightSpec extends AnyWordSpec with Matchers:

  "ConfigJsonHighlight.locateLine" should:
    "find a property line in pretty-printed JSON" in:
      val json = Json.prettyPrint(Json.toJson(DefaultConfigs.ratesAndQuestionsPack))
      val line = ConfigJsonHighlight.locateLine(json, "questionPack.questions[0].title")
      line shouldBe defined
      val text = json.split("\n")(line.get - 1)
      text should include("title")

  "ConfigJsonHighlight.build" should:
    "mark the offending line when a title is invalid" in:
      val pack = DefaultConfigs.ratesAndQuestionsPack.copy(
        questions = DefaultConfigs.ratesAndQuestionsPack.questions.map: question =>
          if question.id == "taxYears" then question.copy(title = "Which tax years?") else question
      )
      val questionJson = Json.prettyPrint(Json.toJson(pack))
      val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
      val Left(violations) = ConfigValidator.validate(
        defaults.rateJson,
        questionJson,
        defaults.calculationJson
      ): @unchecked

      val highlights = ConfigJsonHighlight.build(
        defaults.rateJson,
        questionJson,
        defaults.calculationJson,
        violations
      )
      val questionIssues = highlights.find(_.fieldId == "questionJson").get
      questionIssues.lines.exists(_.hasError) shouldBe true
      questionIssues.lines.filter(_.hasError).flatMap(_.issues).mkString should include("title")

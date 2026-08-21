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
  ConfigSchemas,
  ConfigValidator,
  ConfigViolation,
  DefaultConfigs
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  ConfigQuestion,
  QuestionPack,
  QuestionType,
  ShowIf,
  TaxBandRule
}

class ConfigValidatorSpec extends AnyWordSpec with Matchers:

  private def leftErrors[A](result: Either[Seq[ConfigViolation], A]): Seq[ConfigViolation] =
    result.fold(identity, _ => fail("Expected validation to fail"))

  "ConfigValidator" should:
    "accept the default Option 1 and Option 2 configs" in:
      val option1 = DefaultConfigs.defaultsFor(ArchitectureOption.RatesOnly)
      ConfigValidator.validate(option1.rateJson, option1.questionJson, option1.calculationJson) match
        case Right(_)     => succeed
        case Left(errors) => fail(ConfigValidator.formatErrors(errors))

      val option2 = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
      ConfigValidator.validate(option2.rateJson, option2.questionJson, option2.calculationJson) match
        case Right(_)     => succeed
        case Left(errors) => fail(ConfigValidator.formatErrors(errors))

    "reject a rate catalogue with no years" in:
      val raw = """{"version":"x","years":[]}"""
      val errors = leftErrors(ConfigValidator.validateRateCatalog(raw))
      errors.map(_.message).mkString should include("at least 1 item")

    "reject a rate catalogue with duplicate tax years" in:
      val year = Json.toJson(DefaultConfigs.rate2017_18)
      val raw = Json.obj("version" -> "dup", "years" -> Json.arr(year, year)).toString
      val errors = leftErrors(ConfigValidator.validateRateCatalog(raw))
      errors.exists(_.message.contains("Duplicate tax year")) shouldBe true

    "reject a title that is not an underscored message key" in:
      val pack = DefaultConfigs.ratesAndQuestionsPack.copy(
        questions = DefaultConfigs.ratesAndQuestionsPack.questions.map: question =>
          if question.id == "taxYears" then question.copy(title = "Which tax years?") else question
      )
      val errors = leftErrors(ConfigValidator.validateQuestionPack(Json.toJson(pack).toString))
      errors.nonEmpty shouldBe true

    "reject choice questions without options" in:
      val bad = ConfigQuestion(
        id = "colour",
        `type` = QuestionType.singleChoice,
        title = "What_colour"
      )
      val pack = QuestionPack(
        id = "bad",
        title = "Bad_pack",
        questions = Seq(
          DefaultConfigs.ratesAndQuestionsPack.questions.head,
          bad
        )
      )
      val errors = leftErrors(ConfigValidator.validateQuestionPack(Json.prettyPrint(Json.toJson(pack))))
      errors.exists(_.message.contains("need at least one option")) shouldBe true

    "reject showIf that points at an unknown question" in:
      val pack = DefaultConfigs.ratesAndQuestionsPack.copy(
        questions = DefaultConfigs.ratesAndQuestionsPack.questions.map: question =>
          if question.id == "dividends" then
            question.copy(showIf = Some(ShowIf(field = "missingField", contains = Some("x"))))
          else question
      )
      val errors = leftErrors(ConfigValidator.validateQuestionPack(Json.toJson(pack).toString))
      errors.exists(_.message.contains("Unknown question id 'missingField'")) shouldBe true

    "reject calculation allowance when-clauses that do not match question ids" in:
      val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
      val badCalc = defaults.calculation.copy(
        allowances = defaults.calculation.allowances.map: allowance =>
          if allowance.id == "blindPersonsAllowance" then
            allowance.copy(when = Some(ShowIf(field = "notAQuestion", equals = Some("yes"))))
          else allowance
      )
      val errors = leftErrors(
        ConfigValidator.validate(
          defaults.rateJson,
          defaults.questionJson,
          Json.prettyPrint(Json.toJson(badCalc))
        )
      )
      errors.exists(_.message.contains("notAQuestion")) shouldBe true

    "reject unknown rateKey values in the calculation schema" in:
      val defaults = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)
      val badCalc = defaults.calculation.copy(
        tax = defaults.calculation.tax.copy(
          bands = Seq(TaxBandRule(rateKey = "mysteryRate", label = "Mystery_rate"))
        )
      )
      val errors = leftErrors(
        ConfigValidator.validateCalculationSpec(Json.prettyPrint(Json.toJson(badCalc)))
      )
      errors.exists(_.message.contains("mysteryRate")) shouldBe true

    "ship JSON Schema resources on the classpath" in:
      ConfigValidator.schemaResourceExists(ConfigSchemas.RateCatalogSchemaPath) shouldBe true
      ConfigValidator.schemaResourceExists(ConfigSchemas.QuestionPackSchemaPath) shouldBe true
      ConfigValidator.schemaResourceExists(ConfigSchemas.CalculationSpecSchemaPath) shouldBe true

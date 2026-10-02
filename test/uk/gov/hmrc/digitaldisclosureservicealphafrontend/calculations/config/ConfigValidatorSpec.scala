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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
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

  private def withQuestion(id: String)(change: ConfigQuestion => ConfigQuestion): QuestionPack =
    fullPack.copy(questions = fullPack.questions.map(q => if q.id == id then change(q) else q))

  "ConfigValidator" should:
    "accept the default config for every option" in:
      ArchitectureOption.values.filter(_.implemented).foreach: option =>
        val defaults = DefaultConfigs.defaultsFor(option)
        ConfigValidator.validate(defaults.rateJson, defaults.questionJson, defaults.calculationJson) match
          case Right(config) => config shouldBe defaults
          case Left(errors)  => fail(s"$option: ${ConfigValidator.formatErrors(errors)}")

    "reject a rate catalogue with no years" in:
      val errors = leftErrors(ConfigValidator.validateRateCatalog("""{"version":"x","years":[]}"""))
      errors.map(_.message).mkString should include("at least 1 item")

    "reject a rate catalogue with duplicate tax years" in:
      val year = Json.toJson(ratesFor("2017-18"))
      val raw = Json.obj("version" -> "dup", "years" -> Json.arr(year, year)).toString
      leftErrors(ConfigValidator.validateRateCatalog(raw)).exists(_.message.contains("Duplicate tax year")) shouldBe true

    "reject a title that is not an underscored message key" in:
      val pack = withQuestion("taxYears")(_.copy(title = "Which tax years?"))
      leftErrors(ConfigValidator.validateQuestionPack(Json.toJson(pack).toString)) should not be empty

    "reject choice questions without options" in:
      val pack = QuestionPack(
        id = "bad",
        title = "Bad_pack",
        questions = Seq(fullPack.questions.head, ConfigQuestion(id = "colour", `type` = QuestionType.singleChoice, title = "What_colour"))
      )
      val errors = leftErrors(ConfigValidator.validateQuestionPack(Json.prettyPrint(Json.toJson(pack))))
      errors.exists(_.message.contains("need at least one option")) shouldBe true

    "reject showIf that points at an unknown question" in:
      val pack = withQuestion("dividends")(_.copy(showIf = Some(ShowIf(field = "missingField", contains = Some("x")))))
      val errors = leftErrors(ConfigValidator.validateQuestionPack(Json.toJson(pack).toString))
      errors.exists(_.message.contains("Unknown question id 'missingField'")) shouldBe true

    "reject calculation allowance when-clauses that do not match question ids" in:
      val badCalc = spec.copy(
        allowances = spec.allowances.map: allowance =>
          if allowance.id == "blindPersonsAllowance" then
            allowance.copy(when = Some(ShowIf(field = "notAQuestion", equals = Some("yes"))))
          else allowance
      )
      val errors = leftErrors(
        ConfigValidator.validate(fullConfig.rateJson, fullConfig.questionJson, Json.prettyPrint(Json.toJson(badCalc)))
      )
      errors.exists(_.message.contains("notAQuestion")) shouldBe true

    "reject unknown rateKey values in the calculation spec" in:
      val badCalc = spec.copy(tax = spec.tax.copy(bands = Seq(TaxBandRule(rateKey = "mysteryRate", label = "Mystery_rate"))))
      val errors = leftErrors(ConfigValidator.validateCalculationSpec(Json.prettyPrint(Json.toJson(badCalc))))
      errors.exists(_.message.contains("mysteryRate")) shouldBe true

    "reject blank tax-paid fields" in:
      val badCalc = spec.copy(taxPaidFields = Seq(""))
      leftErrors(ConfigValidator.validateCalculationSpec(Json.toJson(badCalc).toString)) should not be empty

  "ConfigSchemas" should:
    "ship the JSON Schema documents on the classpath" in:
      Seq(ConfigSchemas.RateCatalogSchemaPath, ConfigSchemas.QuestionPackSchemaPath, ConfigSchemas.CalculationSpecSchemaPath)
        .foreach(path => Option(getClass.getResource(path)) shouldBe defined)

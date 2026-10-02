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
import play.api.libs.json.{Json, Writes}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  CategoryTask,
  ConfigQuestion,
  QuestionPack,
  QuestionType,
  RateCatalog,
  RateDefinition,
  RateKind,
  ShowIf,
  TaskConfig,
  TaskListConfig
}

class ConfigValidatorSpec extends AnyWordSpec with Matchers:

  private def leftErrors[A](result: Either[Seq[ConfigViolation], A]): Seq[ConfigViolation] =
    result.fold(identity, _ => fail("Expected validation to fail"))

  private def json[A: Writes](value: A): String = Json.prettyPrint(Json.toJson(value))

  private def messages(result: Either[Seq[ConfigViolation], ?]): String =
    leftErrors(result).map(_.toString).mkString("\n")

  private def withQuestion(id: String)(change: ConfigQuestion => ConfigQuestion): QuestionPack =
    fullPack.copy(questions = fullPack.questions.map(q => if q.id == id then change(q) else q))

  private def withTaskList(change: TaskListConfig => TaskListConfig): QuestionPack =
    fullPack.copy(taskList = change(fullPack.taskList))

  private def withYears(change: RateCatalog => RateCatalog): Either[Seq[ConfigViolation], RateCatalog] =
    ConfigValidator.validateRateCatalog(json(change(catalog)))

  "ConfigValidator" should:
    "accept the default config for every option" in:
      ArchitectureOption.values.filter(_.implemented).foreach: option =>
        val defaults = DefaultConfigs.defaultsFor(option)
        ConfigValidator.validate(defaults.rateJson, defaults.questionJson, defaults.calculationJson) match
          case Right(config) => config shouldBe defaults
          case Left(errors)  => fail(s"$option: ${ConfigValidator.formatErrors(errors)}")

    "report the path of a property that does not belong" in:
      val raw = fullConfig.questionJson.replaceFirst("\"id\"", "\"colour\" : \"blue\", \"id\"")
      leftErrors(ConfigValidator.validateQuestionPack(raw)) should contain(ConfigViolation("questionPack.colour", "Unexpected property 'colour'"))

  "The rate catalogue" should:
    "need at least one year" in:
      messages(withYears(_.copy(years = Nil))) should include("rateCatalogue.years: must have at least 1 item")

    "reject duplicate tax years" in:
      messages(withYears(c => c.copy(years = c.years :+ c.years.head))) should include("Duplicate tax year '2015-16'")

    "reject duplicate rate keys" in:
      messages(withYears(c => c.copy(rates = c.rates :+ c.rates.head))) should include("Duplicate rate key 'personalAllowance'")

    "need a value for every declared rate in every year" in:
      val extra = RateDefinition("savingsAllowance", "Savings_allowance", RateKind.amount)
      messages(withYears(c => c.copy(rates = c.rates :+ extra))) should include(
        "rateCatalogue.years[0].values: Missing value for rate 'savingsAllowance'"
      )

    "reject a value for a rate that is not declared" in:
      val result = withYears(c => c.copy(years = c.years.map(y => y.copy(values = y.values.updated("mystery", BigDecimal(1))))))
      messages(result) should include("rateCatalogue.years[0].values.mystery: 'mystery' is not declared in rates")

    "keep percentages between 0 and 1" in:
      val result = withYears(c => c.copy(years = c.years.map(y => y.copy(values = y.values.updated("basicRate", BigDecimal(20))))))
      messages(result) should include("values.basicRate: must be between 0 and 1 because 'basicRate' is a percentage")

    "accept a new rate declared only in config" in:
      val extra = RateDefinition("savingsAllowance", "Savings_allowance", RateKind.amount)
      val result = withYears(c =>
        c.copy(rates = c.rates :+ extra, years = c.years.map(y => y.copy(values = y.values.updated("savingsAllowance", BigDecimal(1000)))))
      )
      result.map(_.forYear("2017-18").map(_.value("savingsAllowance"))) shouldBe Right(Some(BigDecimal(1000)))

  "The question pack" should:
    "reject a title that is not a message key" in:
      messages(ConfigValidator.validateQuestionPack(json(withQuestion("taxYears")(_.copy(title = "Which tax years?"))))) should
        include("does not match required pattern")

    "reject choice questions without options" in:
      val colour = ConfigQuestion(id = "colour", `type` = QuestionType.singleChoice, title = "What_colour", perTaxYear = true)
      messages(ConfigValidator.validateQuestionPack(json(fullPack.copy(questions = fullPack.questions :+ colour)))) should
        include("singleChoice questions need at least one option")

    "reject showIf that points at an unknown question" in:
      val pack = withQuestion("dividends")(_.copy(showIf = Some(ShowIf(field = "missingField", contains = Some("x")))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should include("Unknown question id 'missingField'")

  "The question pack's task list" should:
    "reject a task that names an unknown question" in:
      val pack = withTaskList(t => t.copy(sections = t.sections.map(s => s.copy(tasks = s.tasks.map(_.copy(questions = Seq("nope")))))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should
        include("questionPack.taskList.sections[0].tasks[0].questions[0]: Unknown question id 'nope'")

    "reject a question that no task asks" in:
      val pack = withTaskList(t => t.copy(sections = t.sections.map(s => s.copy(tasks = s.tasks.filterNot(_.id == "about-you")))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should
        include("'ageBand' is not in any task in taskList, so it would never be asked")

    "reject a question asked by two tasks" in:
      val pack = withTaskList(t => t.copy(eachYear = t.eachYear.map(y => y.copy(after = y.after :+ TaskConfig("again", "Again", None, Seq("taxAlreadyPaid"))))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should include("Duplicate question in tasks 'taxAlreadyPaid'")

    "reject a category task for an option the question does not have" in:
      val pack = withTaskList: t =>
        t.copy(sections = t.sections.map(s => s.copy(forEachSelected = s.forEachSelected.map(f => f.copy(tasks = f.tasks :+ CategoryTask("lottery", "Lottery"))))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should include("'lottery' is not an option of 'incomeTypes'")

    "only let a section start after an earlier one" in:
      val pack = withTaskList(t => t.copy(sections = t.sections.map(s => if s.id == "prepare" then s.copy(startsAfter = Seq("income")) else s)))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should include("'income' is not an earlier section")

    "only put per-year questions in a tax-year task" in:
      val pack = withTaskList(t => t.copy(eachYear = t.eachYear.map(y => y.copy(before = y.before :+ TaskConfig("age", "Age", None, Seq("ageBand"))))))
      messages(ConfigValidator.validateQuestionPack(json(pack))) should include("'ageBand' is not perTaxYear")

  "Checks across documents" should:
    "reject allowance conditions on questions the pack does not have" in:
      val badCalc = spec.copy(allowances = spec.allowances.map(a => a.copy(when = a.when.map(_.copy(field = "notAQuestion")))))
      messages(ConfigValidator.validate(fullConfig.rateJson, fullConfig.questionJson, json(badCalc))) should include("notAQuestion")

    "reject rate keys the rate catalogue does not declare" in:
      val badCalc = spec.copy(tax = spec.tax.copy(bands = spec.tax.bands.map(_.copy(rateKey = "mysteryRate"))))
      messages(ConfigValidator.validate(fullConfig.rateJson, fullConfig.questionJson, json(badCalc))) should
        include("calculationSpec.tax.bands[0].rateKey: 'mysteryRate' is not a rate in the rate catalogue")

    "reject blank tax-paid fields" in:
      leftErrors(ConfigValidator.validateCalculationSpec(json(spec.copy(taxPaidFields = Seq(""))))) should not be empty

  "ConfigSchemas" should:
    "ship the JSON Schema documents on the classpath" in:
      Seq(ConfigSchemas.RateCatalogSchemaPath, ConfigSchemas.QuestionPackSchemaPath, ConfigSchemas.CalculationSpecSchemaPath)
        .foreach(path => Option(getClass.getResource(path)) shouldBe defined)

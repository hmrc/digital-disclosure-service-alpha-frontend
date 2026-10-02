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

import play.api.libs.json.{JsValue, Json, Reads}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  CalculationsConfig,
  IncomeComponentKind,
  Lane,
  QuestionPack,
  QuestionType,
  RateCatalog,
  RateKind,
  TaskConfig
}

import scala.util.Try

/**
  * Checks the three calculations config documents. Each document goes through four steps, and stops at the first
  * step that finds problems:
  *
  *   1. parse the JSON
  *   2. check its shape against [[ConfigShapes]] (the Scala copy of the JSON Schema files)
  *   3. decode it into the model case classes
  *   4. check rules a schema cannot express, such as ids that must be unique or refer to something that exists
  *
  * When all three documents are valid on their own, [[validate]] also checks the references between them.
  * Error paths start with the document name (`rateCatalogue`, `questionPack`, `calculationSpec`) so the config
  * page can highlight the right line.
  */
object ConfigValidator:

  private type Result[A] = Either[Seq[ConfigViolation], A]

  def validate(rateJson: String, questionJson: String, calculationJson: String): Result[CalculationsConfig] =
    val ratesResult = validateRateCatalog(rateJson)
    val questionsResult = validateQuestionPack(questionJson)
    val calculationResult = validateCalculationSpec(calculationJson)

    (ratesResult, questionsResult, calculationResult) match
      case (Right(catalog), Right(pack), Right(spec)) =>
        failIf(crossDocumentChecks(catalog, pack, spec)).map(_ => CalculationsConfig(catalog, pack, spec))
      case _ =>
        Left(Seq(ratesResult, questionsResult, calculationResult).flatMap(_.swap.getOrElse(Nil)))

  def validateRateCatalog(raw: String): Result[RateCatalog] =
    validateDocument[RateCatalog]("rateCatalogue", raw, ConfigShapes.rateCatalog, rateCatalogRules)

  def validateQuestionPack(raw: String): Result[QuestionPack] =
    validateDocument[QuestionPack]("questionPack", raw, ConfigShapes.questionPack, questionPackRules)

  def validateCalculationSpec(raw: String): Result[CalculationSpec] =
    validateDocument[CalculationSpec]("calculationSpec", raw, ConfigShapes.calculationSpec, calculationSpecRules)

  /** Which config page field (`rateJson`, `questionJson` or `calculationJson`) each error belongs to. */
  def groupByField(errors: Seq[ConfigViolation]): Map[String, Seq[ConfigViolation]] =
    errors.groupBy: err =>
      if err.path.startsWith("questionPack") then "questionJson"
      else if err.path.startsWith("calculationSpec") then "calculationJson"
      else "rateJson"

  def formatErrors(errors: Seq[ConfigViolation]): String =
    errors.map(_.toString).mkString("; ")

  private def validateDocument[A: Reads](root: String, raw: String, shape: JsonShape, rules: A => Seq[ConfigViolation]): Result[A] =
    for
      json  <- Try(Json.parse(raw)).toEither.left.map(err => Seq(ConfigViolation(root, s"Invalid JSON: ${err.getMessage}")))
      _     <- failIf(shape.check(json, root))
      value <- decode[A](root, json)
      _     <- failIf(rules(value))
    yield value

  private def decode[A: Reads](root: String, json: JsValue): Result[A] =
    json.validate[A].asEither.left.map: errs =>
      errs.map((path, errors) => ConfigViolation(s"$root${path.toJsonString.stripPrefix("$")}", errors.map(_.message).mkString(", "))).toSeq

  // Rate catalogue

  private def rateCatalogRules(catalog: RateCatalog): Seq[ConfigViolation] =
    val declared = catalog.rates.map(_.key)
    val yearErrors = catalog.years.zipWithIndex.flatMap: (year, idx) =>
      val path = s"rateCatalogue.years[$idx].values"
      val missing = declared.filterNot(year.values.contains).map(key => ConfigViolation(path, s"Missing value for rate '$key'"))
      val unknown = year.values.keys.toSeq.filterNot(declared.contains).sorted.map: key =>
        ConfigViolation(s"$path.$key", s"'$key' is not declared in rates")
      val outOfRange = catalog.rates.filter(_.kind == RateKind.percentage).flatMap: rate =>
        year.values.get(rate.key).filter(_ > 1).map: _ =>
          ConfigViolation(s"$path.${rate.key}", s"must be between 0 and 1 because '${rate.key}' is a percentage")
      missing ++ unknown ++ outOfRange

    duplicates(declared, "rateCatalogue.rates", "rate key") ++
      duplicates(catalog.taxYears, "rateCatalogue.years", "tax year") ++
      yearErrors

  // Question pack

  private def questionPackRules(pack: QuestionPack): Seq[ConfigViolation] =
    val ids = pack.questions.map(_.id)
    duplicates(ids, "questionPack.questions", "question id") ++
      pack.questions.zipWithIndex.flatMap((_, idx) => questionRules(pack, idx)) ++
      taskListRules(pack)

  private def questionRules(pack: QuestionPack, idx: Int): Seq[ConfigViolation] =
    val q = pack.questions(idx)
    val path = s"questionPack.questions[$idx]"
    val isChoice = Set(QuestionType.singleChoice, QuestionType.checkboxes).contains(q.questionType)
    val optionErrors =
      if q.optionsFromRates && !isChoice then
        Seq(ConfigViolation(s"$path.optionsFromRates", "optionsFromRates requires type singleChoice or checkboxes"))
      else if q.optionsFromRates && q.options.exists(_.nonEmpty) then
        Seq(ConfigViolation(s"$path.options", "options must be empty when optionsFromRates is true"))
      else if !q.optionsFromRates && isChoice && q.options.forall(_.isEmpty) then
        Seq(ConfigViolation(s"$path.options", s"${q.questionType} questions need at least one option (or optionsFromRates)"))
      else Nil
    val showIfErrors = q.showIf.toSeq.flatMap: rule =>
      if rule.equals.isEmpty && rule.contains.isEmpty && rule.notEquals.isEmpty then
        Seq(ConfigViolation(s"$path.showIf", "showIf needs equals, contains or notEquals"))
      else if !pack.questions.exists(_.id == rule.field) then
        Seq(ConfigViolation(s"$path.showIf.field", s"Unknown question id '${rule.field}'"))
      else Nil
    optionErrors ++ showIfErrors

  private def taskListRules(pack: QuestionPack): Seq[ConfigViolation] =
    val layout = pack.taskList
    val byId = pack.questions.map(q => q.id -> q).toMap
    val root = "questionPack.taskList"

    def taskErrors(task: TaskConfig, path: String, perYearOnly: Boolean): Seq[ConfigViolation] =
      task.questions.zipWithIndex.flatMap: (id, idx) =>
        byId.get(id) match
          case None                                  => Seq(ConfigViolation(s"$path.questions[$idx]", s"Unknown question id '$id'"))
          case Some(q) if perYearOnly && !q.perTaxYear =>
            Seq(ConfigViolation(s"$path.questions[$idx]", s"'$id' is not perTaxYear, so it cannot be in a tax-year task"))
          case _ => Nil

    val sectionErrors = layout.sections.zipWithIndex.flatMap: (section, sIdx) =>
      val path = s"$root.sections[$sIdx]"
      val earlier = layout.sections.take(sIdx).map(_.id)
      val order = section.startsAfter.zipWithIndex.collect:
        case (id, idx) if !earlier.contains(id) => ConfigViolation(s"$path.startsAfter[$idx]", s"'$id' is not an earlier section")
      val tasks = section.tasks.zipWithIndex.flatMap((t, tIdx) => taskErrors(t, s"$path.tasks[$tIdx]", perYearOnly = false))
      val category = section.forEachSelected.toSeq.flatMap: each =>
        val options = byId.get(each.question).flatMap(_.options).getOrElse(Nil).map(_.value)
        val question = byId.get(each.question) match
          case None => Seq(ConfigViolation(s"$path.forEachSelected.question", s"Unknown question id '${each.question}'"))
          case Some(q) if q.questionType != QuestionType.checkboxes =>
            Seq(ConfigViolation(s"$path.forEachSelected.question", s"'${each.question}' must be a checkboxes question"))
          case _ => Nil
        val values = each.tasks.zipWithIndex.collect:
          case (t, idx) if byId.contains(each.question) && !options.contains(t.value) =>
            ConfigViolation(s"$path.forEachSelected.tasks[$idx].value", s"'${t.value}' is not an option of '${each.question}'")
        question ++ values ++ duplicates(each.tasks.map(_.value), s"$path.forEachSelected.tasks", "value")
      val reserved = Option.when(section.id == "final" || section.id.startsWith("year-")):
        ConfigViolation(s"$path.id", s"'${section.id}' is reserved for the sections the service adds")
      order ++ tasks ++ category ++ reserved

    val yearErrors = layout.eachYear.toSeq.flatMap: eachYear =>
      val path = s"$root.eachYear"
      val sectionIds = layout.sections.map(_.id)
      val order = eachYear.startsAfter.zipWithIndex.collect:
        case (id, idx) if !sectionIds.contains(id) => ConfigViolation(s"$path.startsAfter[$idx]", s"Unknown section id '$id'")
      def tasks(list: Seq[TaskConfig], name: String) =
        list.zipWithIndex.flatMap((t, idx) => taskErrors(t, s"$path.$name[$idx]", perYearOnly = true))
      order ++ tasks(eachYear.before, "before") ++ tasks(eachYear.after, "after") ++
        duplicates((eachYear.fixedTasks.map(_.id) ++ eachYear.leftover.map(_.id)), path, "task id")

    val sectionTaskIds = layout.sections.flatMap: section =>
      section.tasks.map(_.id) ++ section.forEachSelected.toSeq.flatMap(_.tasks.map(t => s"${section.id}-${t.value}"))

    duplicates(layout.sections.map(_.id), s"$root.sections", "section id") ++
      duplicates(sectionTaskIds, s"$root.sections", "task id") ++
      duplicates(layout.fixedTasks.flatMap(_.questions), root, "question in tasks") ++
      sectionErrors ++
      yearErrors ++
      unaskedQuestions(pack)

  /** Questions the task list never shows, which would otherwise silently never be asked. */
  private def unaskedQuestions(pack: QuestionPack): Seq[ConfigViolation] =
    val layout = pack.taskList
    val inFixedTask = layout.fixedTasks.flatMap(_.questions).toSet
    val lanesWithTasks = layout.categories.flatMap(c => c.tasks.map(t => Lane(c.question, t.value))).toSet
    val hasLeftover = layout.eachYear.exists(_.leftover.isDefined)
    pack.questions.zipWithIndex.collect:
      case (q, idx)
          if !inFixedTask.contains(q.id) &&
            !Lane.of(q, pack).exists(lanesWithTasks.contains) &&
            !(q.perTaxYear && hasLeftover) =>
        ConfigViolation(s"questionPack.questions[$idx]", s"'${q.id}' is not in any task in taskList, so it would never be asked")

  // Calculation spec

  private def calculationSpecRules(spec: CalculationSpec): Seq[ConfigViolation] =
    val componentErrors = spec.incomeComponents.zipWithIndex.flatMap: (c, idx) =>
      val path = s"calculationSpec.incomeComponents[$idx]"
      c.kind match
        case IncomeComponentKind.amount if c.field.forall(_.isBlank) =>
          Seq(ConfigViolation(s"$path.field", "amount components require field"))
        case IncomeComponentKind.net if c.grossField.forall(_.isBlank) =>
          Seq(ConfigViolation(s"$path.grossField", "net components require grossField"))
        case _ => Nil

    duplicates(spec.incomeComponents.map(_.id), "calculationSpec.incomeComponents", "income component id") ++
      componentErrors ++
      duplicates(spec.allowances.map(_.id), "calculationSpec.allowances", "allowance id")

  // Across documents

  private def crossDocumentChecks(catalog: RateCatalog, pack: QuestionPack, spec: CalculationSpec): Seq[ConfigViolation] =
    val questionIds = pack.questions.map(_.id).toSet
    val rateKeys = catalog.rates.map(_.key)

    def rate(key: String, path: String): Option[ConfigViolation] =
      Option.when(!rateKeys.contains(key)):
        ConfigViolation(path, s"'$key' is not a rate in the rate catalogue (${rateKeys.mkString(", ")})")

    val allowanceRateErrors = spec.allowances.zipWithIndex.flatMap: (a, idx) =>
      rate(a.rateKey, s"calculationSpec.allowances[$idx].rateKey") ++
        a.taper.flatMap(t => rate(t.thresholdRateKey, s"calculationSpec.allowances[$idx].taper.thresholdRateKey"))
    val bandRateErrors = spec.tax.bands.zipWithIndex.flatMap: (b, idx) =>
      rate(b.rateKey, s"calculationSpec.tax.bands[$idx].rateKey") ++
        b.upToRateKey.flatMap(rate(_, s"calculationSpec.tax.bands[$idx].upToRateKey"))

    // Income fields may name questions a pack leaves out (their answers count as zero), but an allowance
    // condition on a missing question could never be met.
    val whenErrors = spec.allowances.zipWithIndex.flatMap: (a, idx) =>
      a.when.filterNot(w => questionIds.contains(w.field)).map: w =>
        ConfigViolation(s"calculationSpec.allowances[$idx].when.field", s"References unknown question id '${w.field}'")

    val taxYearsQuestion = Option.unless(pack.questions.exists(q => q.id == QuestionPack.TaxYearsQuestionId && q.optionsFromRates)):
      ConfigViolation(
        "questionPack.questions",
        s"Pack should include a '${QuestionPack.TaxYearsQuestionId}' question with optionsFromRates for multi-year journeys"
      )

    allowanceRateErrors ++ bandRateErrors ++ whenErrors ++ taxYearsQuestion

  private def duplicates(values: Seq[String], path: String, what: String): Seq[ConfigViolation] =
    values.groupBy(identity).collect { case (value, xs) if xs.size > 1 => value }.toSeq.sorted.map: value =>
      ConfigViolation(path, s"Duplicate $what '$value'")

  private def failIf(errors: Seq[ConfigViolation]): Result[Unit] =
    if errors.isEmpty then Right(()) else Left(errors)

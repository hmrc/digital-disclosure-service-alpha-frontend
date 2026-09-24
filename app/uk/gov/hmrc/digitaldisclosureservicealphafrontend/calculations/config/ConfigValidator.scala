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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine.QuestionEngine
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  CalculationSpec,
  IncomeComponentKind,
  QuestionPack,
  QuestionType,
  RateCatalog
}

import play.api.libs.json.{JsArray, JsObject, JsValue, Json, Reads}

import scala.util.Try

final case class ConfigViolation(path: String, message: String):
  override def toString: String =
    if path.isEmpty || path == "$" then message else s"$path: $message"

final case class ValidatedConfig(
  catalog    : RateCatalog,
  questions  : QuestionPack,
  calculation: CalculationSpec
)

/**
  * Contract for calculations JSON configs.
  *
  * JSON Schema documents under conf/calculations/schemas/ are the published shape.
  * This validator enforces that shape (plus cross-document rules) in Scala so
  * defaults and edited configs are easy to unit-test without pulling a JSON
  * Schema runtime onto the Play classpath.
  */
object ConfigValidator:

  val RateCatalogSchemaPath     = ConfigSchemas.RateCatalogSchemaPath
  val QuestionPackSchemaPath    = ConfigSchemas.QuestionPackSchemaPath
  val CalculationSpecSchemaPath = ConfigSchemas.CalculationSpecSchemaPath

  val KnownRateKeys: Set[String] = Set(
    "personalAllowance",
    "taperThreshold",
    "blindPersonsAllowance",
    "basicRateBand",
    "basicRate",
    "higherRate"
  )

  private val MessageKey     = "^[A-Za-z][A-Za-z0-9_]*$".r
  private val TaxYear        = "^[0-9]{4}-[0-9]{2}$".r
  private val QuestionTypes  = Set("yesNo", "text", "currency", "singleChoice", "checkboxes")
  private val IncomeKinds    = Set("amount", "net")
  private val AllowanceKinds = Set("personalAllowance", "conditionalAmount")
  private val RoundingModes  = Set("halfUp", "up", "down", "floor", "ceiling")

  def validate(
    rateJson       : String,
    questionJson   : String,
    calculationJson: String
  ): Either[Seq[ConfigViolation], ValidatedConfig] =
    val ratesResult = validateRateCatalog(rateJson)
    val questionsResult = validateQuestionPack(questionJson)
    val calculationResult = validateCalculationSpec(calculationJson)

    (ratesResult, questionsResult, calculationResult) match
      case (Right(catalog), Right(questions), Right(calculation)) =>
        semanticChecks(questions, calculation) match
          case Right(_)              => Right(ValidatedConfig(catalog, questions, calculation))
          case Left(semanticErrors)  => Left(semanticErrors)
      case _ =>
        Left(
          ratesResult.swap.getOrElse(Nil) ++
            questionsResult.swap.getOrElse(Nil) ++
            calculationResult.swap.getOrElse(Nil)
        )

  def groupByField(errors: Seq[ConfigViolation]): Map[String, Seq[ConfigViolation]] =
    errors.groupBy: err =>
      err.path match
        case p if p.startsWith("rateCatalogue") || p == "rateCatalogue"             => "rateJson"
        case p if p.startsWith("questionPack") || p == "questionPack"               => "questionJson"
        case p if p.startsWith("calculationSpec") || p == "calculationSpec"         => "calculationJson"
        case _                                                                       => "rateJson"

  def validateRateCatalog(raw: String): Either[Seq[ConfigViolation], RateCatalog] =
    for
      json    <- parseJson("rateCatalogue", raw)
      _       <- structuralRateCatalog(json)
      catalog <- decode[RateCatalog]("rateCatalogue", json)
      _       <- semanticRateCatalog(catalog)
    yield catalog

  def validateQuestionPack(raw: String): Either[Seq[ConfigViolation], QuestionPack] =
    for
      json <- parseJson("questionPack", raw)
      _    <- structuralQuestionPack(json)
      pack <- decode[QuestionPack]("questionPack", json)
      _    <- semanticQuestionPack(pack)
    yield pack

  def validateCalculationSpec(raw: String): Either[Seq[ConfigViolation], CalculationSpec] =
    for
      json <- parseJson("calculationSpec", raw)
      _    <- structuralCalculation(json)
      spec <- decode[CalculationSpec]("calculationSpec", json)
      _    <- semanticCalculation(spec)
    yield spec

  def formatErrors(errors: Seq[ConfigViolation]): String =
    errors.map(_.toString).mkString("; ")

  def schemaResourceExists(path: String): Boolean =
    Option(getClass.getResourceAsStream(path)).exists: stream =>
      stream.close()
      true

  private def parseJson(root: String, raw: String): Either[Seq[ConfigViolation], JsValue] =
    Try(Json.parse(raw)).toEither.left.map: err =>
      Seq(ConfigViolation(root, s"Invalid JSON: ${err.getMessage}"))

  private def decode[A: Reads](root: String, json: JsValue): Either[Seq[ConfigViolation], A] =
    json
      .validate[A]
      .asEither
      .left
      .map: errs =>
        errs.map { case (path, errors) =>
          ConfigViolation(
            s"$root${path.toJsonString.stripPrefix("$")}",
            errors.map(_.message).mkString(", ")
          )
        }.toSeq

  private def structuralRateCatalog(json: JsValue): Either[Seq[ConfigViolation], Unit] =
    json match
      case obj: JsObject =>
        val errors =
          requireKeys(obj, "rateCatalogue", "version", "years") ++
            forbidExtra(obj, "rateCatalogue", Set("version", "years")) ++
            nonEmptyString(obj, "rateCatalogue.version", "version") ++
            (obj.value.get("years") match
              case Some(arr: JsArray) if arr.value.isEmpty =>
                Seq(ConfigViolation("rateCatalogue.years", "must have at least 1 item"))
              case Some(arr: JsArray) =>
                arr.value.zipWithIndex.flatMap { case (yearJson, idx) =>
                  structuralRatePack(yearJson, s"rateCatalogue.years[$idx]")
                }.toSeq
              case Some(_) => Seq(ConfigViolation("rateCatalogue.years", "must be an array"))
              case None    => Nil)
        failIf(errors)
      case _ => Left(Seq(ConfigViolation("rateCatalogue", "must be an object")))

  private def structuralRatePack(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        val required = Set(
          "taxYear",
          "version",
          "personalAllowance",
          "taperThreshold",
          "blindPersonsAllowance",
          "basicRateBand",
          "basicRate",
          "higherRate"
        )
        requireKeys(obj, path, required.toSeq*) ++
          forbidExtra(obj, path, required) ++
          patternString(obj, s"$path.taxYear", "taxYear", TaxYear) ++
          nonEmptyString(obj, s"$path.version", "version") ++
          nonNegativeNumber(obj, s"$path.personalAllowance", "personalAllowance") ++
          nonNegativeNumber(obj, s"$path.taperThreshold", "taperThreshold") ++
          nonNegativeNumber(obj, s"$path.blindPersonsAllowance", "blindPersonsAllowance") ++
          nonNegativeNumber(obj, s"$path.basicRateBand", "basicRateBand") ++
          rateFraction(obj, s"$path.basicRate", "basicRate") ++
          rateFraction(obj, s"$path.higherRate", "higherRate")
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralQuestionPack(json: JsValue): Either[Seq[ConfigViolation], Unit] =
    json match
      case obj: JsObject =>
        val errors =
          requireKeys(obj, "questionPack", "id", "title", "questions") ++
            forbidExtra(obj, "questionPack", Set("id", "title", "questions")) ++
            nonEmptyString(obj, "questionPack.id", "id") ++
            patternString(obj, "questionPack.title", "title", MessageKey) ++
            (obj.value.get("questions") match
              case Some(arr: JsArray) if arr.value.isEmpty =>
                Seq(ConfigViolation("questionPack.questions", "must have at least 1 item"))
              case Some(arr: JsArray) =>
                arr.value.zipWithIndex.flatMap { case (q, idx) =>
                  structuralQuestion(q, s"questionPack.questions[$idx]")
                }.toSeq
              case Some(_) => Seq(ConfigViolation("questionPack.questions", "must be an array"))
              case None    => Nil)
        failIf(errors)
      case _ => Left(Seq(ConfigViolation("questionPack", "must be an object")))

  private def structuralQuestion(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        val allowed = Set(
          "id",
          "type",
          "title",
          "hint",
          "options",
          "required",
          "showIf",
          "feeds",
          "perTaxYear",
          "optionsFromRates"
        )
        requireKeys(obj, path, "id", "type", "title") ++
          forbidExtra(obj, path, allowed) ++
          patternString(obj, s"$path.id", "id", MessageKey) ++
          enumString(obj, s"$path.type", "type", QuestionTypes) ++
          patternString(obj, s"$path.title", "title", MessageKey) ++
          optionalPatternString(obj, s"$path.hint", "hint", MessageKey) ++
          (obj.value.get("options") match
            case Some(arr: JsArray) =>
              arr.value.zipWithIndex.flatMap { case (opt, idx) =>
                structuralOption(opt, s"$path.options[$idx]")
              }.toSeq
            case Some(_) => Seq(ConfigViolation(s"$path.options", "must be an array"))
            case None    => Nil) ++
          (obj.value.get("showIf") match
            case Some(showIf) => structuralShowIf(showIf, s"$path.showIf")
            case None         => Nil)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralOption(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "value", "label") ++
          forbidExtra(obj, path, Set("value", "label")) ++
          nonEmptyString(obj, s"$path.value", "value") ++
          patternString(obj, s"$path.label", "label", MessageKey)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralShowIf(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "field") ++
          forbidExtra(obj, path, Set("field", "equals", "contains", "notEquals")) ++
          nonEmptyString(obj, s"$path.field", "field")
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralCalculation(json: JsValue): Either[Seq[ConfigViolation], Unit] =
    json match
      case obj: JsObject =>
        val errors =
          requireKeys(obj, "calculationSpec", "id", "version", "incomeComponents", "allowances", "tax") ++
            forbidExtra(
              obj,
              "calculationSpec",
              Set("id", "version", "description", "incomeComponents", "allowances", "tax")
            ) ++
            nonEmptyString(obj, "calculationSpec.id", "id") ++
            nonEmptyString(obj, "calculationSpec.version", "version") ++
            (obj.value.get("incomeComponents") match
              case Some(arr: JsArray) if arr.value.isEmpty =>
                Seq(ConfigViolation("calculationSpec.incomeComponents", "must have at least 1 item"))
              case Some(arr: JsArray) =>
                arr.value.zipWithIndex.flatMap { case (c, idx) =>
                  structuralIncomeComponent(c, s"calculationSpec.incomeComponents[$idx]")
                }.toSeq
              case Some(_) => Seq(ConfigViolation("calculationSpec.incomeComponents", "must be an array"))
              case None    => Nil) ++
            (obj.value.get("allowances") match
              case Some(arr: JsArray) =>
                arr.value.zipWithIndex.flatMap { case (a, idx) =>
                  structuralAllowance(a, s"calculationSpec.allowances[$idx]")
                }.toSeq
              case Some(_) => Seq(ConfigViolation("calculationSpec.allowances", "must be an array"))
              case None    => Nil) ++
            (obj.value.get("tax") match
              case Some(tax) => structuralTaxRules(tax, "calculationSpec.tax")
              case None      => Nil)
        failIf(errors)
      case _ => Left(Seq(ConfigViolation("calculationSpec", "must be an object")))

  private def structuralIncomeComponent(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "id", "label", "kind") ++
          forbidExtra(obj, path, Set("id", "label", "kind", "field", "grossField", "deductField", "altDeductField", "floorAtZero")) ++
          nonEmptyString(obj, s"$path.id", "id") ++
          patternString(obj, s"$path.label", "label", MessageKey) ++
          enumString(obj, s"$path.kind", "kind", IncomeKinds)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralAllowance(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "id", "label", "kind", "rateKey") ++
          forbidExtra(obj, path, Set("id", "label", "kind", "rateKey", "taper", "when")) ++
          nonEmptyString(obj, s"$path.id", "id") ++
          patternString(obj, s"$path.label", "label", MessageKey) ++
          enumString(obj, s"$path.kind", "kind", AllowanceKinds) ++
          enumString(obj, s"$path.rateKey", "rateKey", KnownRateKeys) ++
          (obj.value.get("taper") match
            case Some(taper: JsObject) =>
              requireKeys(taper, s"$path.taper", "thresholdRateKey", "reduceBy", "forEvery") ++
                enumString(taper, s"$path.taper.thresholdRateKey", "thresholdRateKey", KnownRateKeys)
            case Some(_) => Seq(ConfigViolation(s"$path.taper", "must be an object"))
            case None    => Nil) ++
          (obj.value.get("when") match
            case Some(when) => structuralShowIf(when, s"$path.when")
            case None       => Nil)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralTaxRules(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "bands") ++
          forbidExtra(obj, path, Set("bands", "scale", "rounding")) ++
          (obj.value.get("bands") match
            case Some(arr: JsArray) if arr.value.isEmpty =>
              Seq(ConfigViolation(s"$path.bands", "must have at least 1 item"))
            case Some(arr: JsArray) =>
              arr.value.zipWithIndex.flatMap { case (band, idx) =>
                structuralTaxBand(band, s"$path.bands[$idx]")
              }.toSeq
            case Some(_) => Seq(ConfigViolation(s"$path.bands", "must be an array"))
            case None    => Nil) ++
          (obj.value.get("rounding") match
            case Some(_) => enumString(obj, s"$path.rounding", "rounding", RoundingModes)
            case None    => Nil)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def structuralTaxBand(json: JsValue, path: String): Seq[ConfigViolation] =
    json match
      case obj: JsObject =>
        requireKeys(obj, path, "rateKey", "label") ++
          forbidExtra(obj, path, Set("rateKey", "label", "upToRateKey")) ++
          enumString(obj, s"$path.rateKey", "rateKey", KnownRateKeys) ++
          patternString(obj, s"$path.label", "label", MessageKey) ++
          (obj.value.get("upToRateKey") match
            case Some(_) => enumString(obj, s"$path.upToRateKey", "upToRateKey", KnownRateKeys)
            case None    => Nil)
      case _ => Seq(ConfigViolation(path, "must be an object"))

  private def semanticRateCatalog(catalog: RateCatalog): Either[Seq[ConfigViolation], Unit] =
    val dupYears = catalog.years.map(_.taxYear).groupBy(identity).collect {
      case (year, xs) if xs.size > 1 =>
        ConfigViolation("rateCatalogue.years", s"Duplicate tax year '$year'")
    }.toSeq
    failIf(dupYears)

  private def semanticQuestionPack(pack: QuestionPack): Either[Seq[ConfigViolation], Unit] =
    val ids = pack.questions.map(_.id)
    val dupIds = ids.groupBy(identity).collect {
      case (id, xs) if xs.size > 1 =>
        ConfigViolation("questionPack.questions", s"Duplicate question id '$id'")
    }.toSeq

    val questionErrors = pack.questions.zipWithIndex.flatMap { case (q, idx) =>
      val base = s"questionPack.questions[$idx]"
      val choiceTypes = Set(QuestionType.singleChoice, QuestionType.checkboxes)
      val optionsErrors =
        if q.optionsFromRates then
          if !choiceTypes.contains(q.questionType) then
            Seq(ConfigViolation(s"$base.optionsFromRates", "optionsFromRates requires type singleChoice or checkboxes"))
          else if q.options.exists(_.nonEmpty) then
            Seq(ConfigViolation(s"$base.options", "options must be empty when optionsFromRates is true"))
          else Nil
        else if choiceTypes.contains(q.questionType) && q.options.forall(_.isEmpty) then
          Seq(
            ConfigViolation(
              s"$base.options",
              s"${q.questionType} questions need at least one option (or optionsFromRates)"
            )
          )
        else Nil

      val showIfErrors = q.showIf.toSeq.flatMap: rule =>
        if rule.equals.isEmpty && rule.contains.isEmpty && rule.notEquals.isEmpty then
          Seq(ConfigViolation(s"$base.showIf", "showIf needs equals, contains or notEquals"))
        else if !ids.contains(rule.field) then
          Seq(ConfigViolation(s"$base.showIf.field", s"Unknown question id '${rule.field}'"))
        else Nil

      optionsErrors ++ showIfErrors
    }

    failIf(dupIds ++ questionErrors)

  private def semanticCalculation(spec: CalculationSpec): Either[Seq[ConfigViolation], Unit] =
    val componentIds = spec.incomeComponents.map(_.id)
    val dupComponents = componentIds.groupBy(identity).collect {
      case (id, xs) if xs.size > 1 =>
        ConfigViolation("calculationSpec.incomeComponents", s"Duplicate income component id '$id'")
    }.toSeq

    val componentErrors = spec.incomeComponents.zipWithIndex.flatMap { case (c, idx) =>
      val base = s"calculationSpec.incomeComponents[$idx]"
      c.kind match
        case IncomeComponentKind.amount if c.field.forall(_.isBlank) =>
          Seq(ConfigViolation(s"$base.field", "amount components require field"))
        case IncomeComponentKind.net if c.grossField.forall(_.isBlank) =>
          Seq(ConfigViolation(s"$base.grossField", "net components require grossField"))
        case _ => Nil
    }

    val allowanceIds = spec.allowances.map(_.id)
    val dupAllowances = allowanceIds.groupBy(identity).collect {
      case (id, xs) if xs.size > 1 =>
        ConfigViolation("calculationSpec.allowances", s"Duplicate allowance id '$id'")
    }.toSeq

    failIf(dupComponents ++ componentErrors ++ dupAllowances)

  private def semanticChecks(
    pack       : QuestionPack,
    calculation: CalculationSpec
  ): Either[Seq[ConfigViolation], Unit] =
    val questionIds = pack.questions.map(_.id).toSet

    // Calculation may reference optional question ids that are absent from a given pack
    // (answers default to zero). Only reject allowance "when" clauses that cannot ever fire.
    val allowanceWhenErrors = calculation.allowances.zipWithIndex.flatMap { case (a, idx) =>
      a.when.toSeq.flatMap: rule =>
        if questionIds.contains(rule.field) then Nil
        else
          Seq(
            ConfigViolation(
              s"calculationSpec.allowances[$idx].when.field",
              s"References unknown question id '${rule.field}'"
            )
          )
    }

    val taxYearsQuestionOk =
      if pack.questions.exists(q => q.id == QuestionEngine.TaxYearsQuestionId && q.optionsFromRates) then Nil
      else
        Seq(
          ConfigViolation(
            "questionPack.questions",
            s"Pack should include a '${QuestionEngine.TaxYearsQuestionId}' question with optionsFromRates for multi-year journeys"
          )
        )

    failIf(allowanceWhenErrors ++ taxYearsQuestionOk)

  private def requireKeys(obj: JsObject, path: String, keys: String*): Seq[ConfigViolation] =
    keys.filterNot(obj.keys.contains).map: key =>
      ConfigViolation(path, s"Missing required property '$key'")

  private def forbidExtra(obj: JsObject, path: String, allowed: Set[String]): Seq[ConfigViolation] =
    obj.keys.toSeq.filterNot(allowed.contains).map: key =>
      ConfigViolation(s"$path.$key", s"Unexpected property '$key'")

  private def nonEmptyString(obj: JsObject, path: String, key: String): Seq[ConfigViolation] =
    obj.value.get(key) match
      case Some(play.api.libs.json.JsString(s)) if s.nonEmpty => Nil
      case Some(play.api.libs.json.JsString(_))               => Seq(ConfigViolation(path, "must be a non-empty string"))
      case Some(_)                                            => Seq(ConfigViolation(path, "must be a string"))
      case None                                               => Nil

  private def patternString(
    obj    : JsObject,
    path   : String,
    key    : String,
    pattern: scala.util.matching.Regex
  ): Seq[ConfigViolation] =
    obj.value.get(key) match
      case Some(play.api.libs.json.JsString(s)) if pattern.matches(s) => Nil
      case Some(play.api.libs.json.JsString(s)) =>
        Seq(ConfigViolation(path, s"'$s' does not match required pattern ${pattern.regex}"))
      case Some(_) => Seq(ConfigViolation(path, "must be a string"))
      case None    => Nil

  private def optionalPatternString(
    obj    : JsObject,
    path   : String,
    key    : String,
    pattern: scala.util.matching.Regex
  ): Seq[ConfigViolation] =
    if obj.keys.contains(key) then patternString(obj, path, key, pattern) else Nil

  private def enumString(obj: JsObject, path: String, key: String, allowed: Set[String]): Seq[ConfigViolation] =
    obj.value.get(key) match
      case Some(play.api.libs.json.JsString(s)) if allowed.contains(s) => Nil
      case Some(play.api.libs.json.JsString(s)) =>
        Seq(ConfigViolation(path, s"'$s' is not one of ${allowed.toSeq.sorted.mkString(", ")}"))
      case Some(_) => Seq(ConfigViolation(path, "must be a string"))
      case None    => Nil

  private def nonNegativeNumber(obj: JsObject, path: String, key: String): Seq[ConfigViolation] =
    obj.value.get(key) match
      case Some(play.api.libs.json.JsNumber(n)) if n >= 0 => Nil
      case Some(play.api.libs.json.JsNumber(_))           => Seq(ConfigViolation(path, "must be >= 0"))
      case Some(_)                                        => Seq(ConfigViolation(path, "must be a number"))
      case None                                           => Nil

  private def rateFraction(obj: JsObject, path: String, key: String): Seq[ConfigViolation] =
    obj.value.get(key) match
      case Some(play.api.libs.json.JsNumber(n)) if n >= 0 && n <= 1 => Nil
      case Some(play.api.libs.json.JsNumber(_))                     => Seq(ConfigViolation(path, "must be between 0 and 1"))
      case Some(_)                                                  => Seq(ConfigViolation(path, "must be a number"))
      case None                                                     => Nil

  private def failIf(errors: Seq[ConfigViolation]): Either[Seq[ConfigViolation], Unit] =
    if errors.isEmpty then Right(()) else Left(errors)

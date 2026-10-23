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

import org.camunda.feel.api.{FeelEngineApi, FeelEngineBuilder}
import org.camunda.feel.syntaxtree.{Exp, NamedFunctionParameters, ParsedExpression, PathExpression, PositionalFunctionParameters, Ref}
import play.api.libs.json.{JsValue, Json}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{ConfigShapes, ConfigViolation, JsonShape}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.JsonShape.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{CalculationsConfig, WorkedExampleScenario}

import scala.util.Try

/** The FEEL engine. It keeps no state between evaluations, so one is shared. */
object Feel:
  val engine: FeelEngineApi = FeelEngineBuilder.create().build()

/** A name an expression reads: `answers.dividends` is Reference("answers", Some("dividends")); `totalIncome` has no key. */
final case class Reference(root: String, key: Option[String])

object FeelReferences:

  /** Every name the expression reads, in the order it reads them. */
  def of(exp: Exp): Seq[Reference] =
    exp match
      case ref: Ref =>
        ref.names match
          case scala.Nil         => Nil
          case root :: scala.Nil => Seq(Reference(root, None))
          case root :: rest      => Seq(Reference(root, Some(rest.mkString("."))))
      case path: PathExpression =>
        path.path match
          case ref: Ref if ref.names.size == 1 => Seq(Reference(ref.names.head, Some(path.key)))
          case other                           => of(other)
      case other => children(other).flatMap(of)

  /** The sub-expressions of any FEEL syntax node: every node is a case class, so its fields hold them. */
  def children(exp: Exp): Seq[Exp] =
    exp match
      case node: Product =>
        node.productIterator.toSeq.flatMap:
          case child: Exp                           => Seq(child)
          case params: PositionalFunctionParameters => params.params
          case params: NamedFunctionParameters      => params.params.values.toSeq
          case items: Iterable[?]                   => items.collect { case child: Exp => child }
          case _                                    => Nil

/**
  * Checks a FEEL rules document in the same steps as [[uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.ConfigValidator]]:
  * parse the JSON, check its shape, decode it, then the rules a schema cannot express. Here those are:
  * every expression is valid FEEL, every name it reads exists, a step only reads earlier steps, and the
  * worked examples can be calculated, so a rule that gives no value fails here rather than on a user's result page.
  */
object FeelRulesValidator:

  val Root = "feelRules"

  private val Reserved = Set("answers", "rates", "groups")

  private val id = pattern(ConfigShapes.IdPattern)

  private val step = obj(
    required("id", id),
    required("label", pattern(ConfigShapes.MessageKeyPattern)),
    required("feel", nonEmptyString),
    optional("part", oneOf(FeelPart.values)),
    optional("group", id),
    optional("format", oneOf(FeelValueFormat.values))
  )

  private val outputs = obj(
    required("totalIncome", id),
    required("taxableIncome", id),
    required("taxDue", id),
    optional("allowances", arrayOf(id)),
    optional("penalty", id),
    optional("penaltyAnswered", id)
  )

  val shape: JsonShape = obj(
    required("id", nonEmptyString),
    required("version", nonEmptyString),
    optional("description", string(allowEmpty = true)),
    required("steps", arrayOf(step, minItems = 1)),
    required("outputs", outputs)
  )

  private type Result[A] = Either[Seq[ConfigViolation], A]

  /** Validates the document against the session's config and calculates every worked example with it. */
  def validate(raw: String, config: CalculationsConfig, examples: Seq[WorkedExampleScenario]): Result[FeelRulesConfig] =
    for
      json     <- Try(Json.parse(raw)).toEither.left.map(err => Seq(ConfigViolation(Root, s"Invalid JSON: ${err.getMessage}")))
      _        <- failIf(shape.check(json, Root))
      rules    <- decode(json)
      _        <- failIf(stepRules(rules))
      compiled <- compile(rules)
      _        <- failIf(referenceRules(compiled, config) ++ outputRules(rules))
      _        <- failIf(exampleRules(compiled, config, examples))
    yield FeelRulesConfig(raw, compiled)

  private def decode(json: JsValue): Result[FeelRules] =
    json.validate[FeelRules].asEither.left.map: errs =>
      errs.map((path, errors) => ConfigViolation(s"$Root${path.toJsonString.stripPrefix("$")}", errors.map(_.message).mkString(", "))).toSeq

  private def stepRules(rules: FeelRules): Seq[ConfigViolation] =
    val duplicates = rules.steps.groupBy(_.id).collect { case (stepId, xs) if xs.size > 1 => stepId }.toSeq.sorted.map: stepId =>
      ConfigViolation(s"$Root.steps", s"Duplicate step id '$stepId'")
    val reserved = rules.steps.zipWithIndex.collect:
      case (s, idx) if Reserved.contains(s.id) =>
        ConfigViolation(s"$Root.steps[$idx].id", s"'${s.id}' is reserved for ${Reserved.toSeq.sorted.mkString(", ")}")
    duplicates ++ reserved

  private def compile(rules: FeelRules): Result[CompiledFeelRules] =
    val parsed = rules.steps.zipWithIndex.map: (s, idx) =>
      val result = Feel.engine.parseExpression(s.feel)
      if result.isSuccess then Right(s.id -> result.parsedExpression)
      else Left(ConfigViolation(s"$Root.steps[$idx].feel", s"Not valid FEEL: ${result.failure.message}"))
    val errors = parsed.collect { case Left(violation) => violation }
    if errors.nonEmpty then Left(errors)
    else Right(CompiledFeelRules(rules, parsed.collect { case Right(entry) => entry }.toMap))

  private def referenceRules(compiled: CompiledFeelRules, config: CalculationsConfig): Seq[ConfigViolation] =
    val questions = config.questionPack.questions.map(_.id).toSet
    val rates = config.rateCatalog.rates.map(_.key).toSet
    val steps = compiled.steps
    steps.zipWithIndex.flatMap: (s, idx) =>
      val earlier = steps.take(idx)
      val earlierIds = earlier.map(_.id).toSet
      val groups = earlier.flatMap(_.group).toSet
      val path = s"$Root.steps[$idx].feel"
      FeelReferences.of(compiled.expression(s)).distinct.flatMap:
        case Reference("answers", Some(q)) if questions.contains(q) => Nil
        case Reference("answers", key) =>
          Seq(ConfigViolation(path, s"'answers.${key.getOrElse("")}' is not a question in the question pack"))
        case Reference("rates", Some(k)) if rates.contains(k) => Nil
        case Reference("rates", key) =>
          Seq(ConfigViolation(path, s"'rates.${key.getOrElse("")}' is not a rate in the rate catalogue"))
        case Reference("groups", Some(g)) if groups.contains(g) => Nil
        case Reference("groups", key) =>
          Seq(ConfigViolation(path, s"'groups.${key.getOrElse("")}' is not the group of an earlier step"))
        case Reference(name, None) if earlierIds.contains(name) => Nil
        case Reference(name, None) if steps.exists(_.id == name) =>
          Seq(ConfigViolation(path, s"'$name' is worked out later; a step can only use earlier steps"))
        case Reference(name, _) => Seq(ConfigViolation(path, s"Unknown name '$name'"))

  private def outputRules(rules: FeelRules): Seq[ConfigViolation] =
    val ids = rules.steps.map(_.id).toSet
    rules.outputs.stepIds.filterNot(ids.contains).distinct.map: missing =>
      ConfigViolation(s"$Root.outputs", s"'$missing' is not a step")

  /** Each worked example must calculate: a FEEL type error or a missing value fails here, with the step that caused it. */
  private def exampleRules(
    compiled: CompiledFeelRules,
    config  : CalculationsConfig,
    examples: Seq[WorkedExampleScenario]
  ): Seq[ConfigViolation] =
    for
      example <- examples
      answers = example.answersFor(config.rateCatalog.taxYears.take(example.taxYearCount))
      rates   <- config.rateCatalog.years.take(example.taxYearCount)
      failure <- FeelCalculator.year(rates, config.rateCatalog, config.questionPack, compiled, answers).left.toOption.toSeq
    yield ConfigViolation(
      s"$Root.steps[${failure.stepIndex}].feel",
      s"Gives no value for the worked example '${example.id}' in ${failure.taxYear}: ${failure.message}"
    )

  private def failIf(errors: Seq[ConfigViolation]): Result[Unit] =
    if errors.isEmpty then Right(()) else Left(errors)

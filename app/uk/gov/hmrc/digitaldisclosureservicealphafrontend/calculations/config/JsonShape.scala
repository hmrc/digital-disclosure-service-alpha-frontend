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

import play.api.libs.json.{JsArray, JsBoolean, JsNumber, JsObject, JsString, JsValue}

import scala.util.matching.Regex

final case class ConfigViolation(path: String, message: String):
  override def toString: String =
    if path.isEmpty || path == "$" then message else s"$path: $message"

/**
  * The expected shape of a piece of JSON, checked before it is decoded. Each constructor matches a JSON Schema
  * keyword, so a shape reads like the schema file it mirrors:
  *
  * {{{
  *   obj(required("taxYear", pattern(TaxYear)), optional("hint", messageKey))   // properties, required, additionalProperties: false
  *   arrayOf(question, minItems = 1)                                            // type: array, items, minItems
  *   mapOf(number(min = 0))                                                      // type: object, additionalProperties
  *   oneOf(QuestionType.values)                                                  // enum
  * }}}
  */
trait JsonShape:
  /** Every way `json` differs from this shape. `path` names the value in messages, e.g. `questionPack.questions[2]`. */
  def check(json: JsValue, path: String): Seq[ConfigViolation]

object JsonShape:

  final case class Property(name: String, shape: JsonShape, isRequired: Boolean)

  def required(name: String, shape: JsonShape): Property = Property(name, shape, isRequired = true)

  def optional(name: String, shape: JsonShape): Property = Property(name, shape, isRequired = false)

  /** An object with exactly these properties: required ones must be present and no others are allowed. */
  def obj(properties: Property*): JsonShape = (json, path) =>
    json match
      case o: JsObject =>
        val missing = properties.filter(p => p.isRequired && !o.keys.contains(p.name)).map: p =>
          ConfigViolation(path, s"Missing required property '${p.name}'")
        val unexpected = o.keys.toSeq.filterNot(properties.map(_.name).contains).map: key =>
          ConfigViolation(s"$path.$key", s"Unexpected property '$key'")
        val invalid = properties.flatMap(p => o.value.get(p.name).toSeq.flatMap(p.shape.check(_, s"$path.${p.name}")))
        missing ++ unexpected ++ invalid
      case _ => Seq(ConfigViolation(path, "must be an object"))

  /** An object used as a lookup table: any property names, every value of the given shape. */
  def mapOf(values: JsonShape): JsonShape = (json, path) =>
    json match
      case o: JsObject => o.value.toSeq.flatMap((key, value) => values.check(value, s"$path.$key"))
      case _           => Seq(ConfigViolation(path, "must be an object"))

  def arrayOf(items: JsonShape, minItems: Int = 0): JsonShape = (json, path) =>
    json match
      case JsArray(values) if values.size < minItems =>
        Seq(ConfigViolation(path, s"must have at least $minItems item${if minItems == 1 then "" else "s"}"))
      case JsArray(values) =>
        values.zipWithIndex.flatMap((value, idx) => items.check(value, s"$path[$idx]")).toSeq
      case _ => Seq(ConfigViolation(path, "must be an array"))

  /** A string, which must be non-empty unless `allowEmpty`. */
  def string(allowEmpty: Boolean = false): JsonShape = (json, path) =>
    json match
      case JsString(s) if s.nonEmpty || allowEmpty => Nil
      case JsString(_)                             => Seq(ConfigViolation(path, "must be a non-empty string"))
      case _                                       => Seq(ConfigViolation(path, "must be a string"))

  val nonEmptyString: JsonShape = string()

  def pattern(regex: Regex): JsonShape = (json, path) =>
    json match
      case JsString(s) if regex.matches(s) => Nil
      case JsString(s)                     => Seq(ConfigViolation(path, s"'$s' does not match required pattern ${regex.regex}"))
      case _                               => Seq(ConfigViolation(path, "must be a string"))

  /** A string from a fixed list, usually a Scala enum's cases. */
  def oneOf[E](allowed: Array[E]): JsonShape =
    val names = allowed.toSeq.map(_.toString)
    (json, path) =>
      json match
        case JsString(s) if names.contains(s) => Nil
        case JsString(s)                      => Seq(ConfigViolation(path, s"'$s' is not one of ${names.sorted.mkString(", ")}"))
        case _                                => Seq(ConfigViolation(path, "must be a string"))

  /** A number, optionally bounded. `above` is an exclusive minimum. */
  def number(min: Option[BigDecimal] = None, max: Option[BigDecimal] = None, above: Option[BigDecimal] = None): JsonShape =
    (json, path) =>
      json match
        case JsNumber(n) =>
          val tooLow = min.exists(n < _) || above.exists(n <= _)
          val tooHigh = max.exists(n > _)
          if !tooLow && !tooHigh then Nil
          else
            val message = (min, max, above) match
              case (Some(lo), Some(hi), _) => s"must be between $lo and $hi"
              case (Some(lo), None, _)     => s"must be >= $lo"
              case (None, Some(hi), _)     => s"must be <= $hi"
              case (None, None, Some(lo))  => s"must be > $lo"
              case (None, None, None)      => "must be a number"
            Seq(ConfigViolation(path, message))
        case _ => Seq(ConfigViolation(path, "must be a number"))

  def integer(min: BigDecimal, max: BigDecimal): JsonShape = (json, path) =>
    json match
      case JsNumber(n) if !n.isWhole => Seq(ConfigViolation(path, "must be a whole number"))
      case other                     => number(Some(min), Some(max)).check(other, path)

  val boolean: JsonShape = (json, path) =>
    json match
      case JsBoolean(_) => Nil
      case _            => Seq(ConfigViolation(path, "must be true or false"))

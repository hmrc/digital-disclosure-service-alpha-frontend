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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.JsonShape.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  AllowanceKind,
  IncomeComponentKind,
  QuestionType,
  RateKind,
  Rounding
}

/**
  * The shape of each config document, kept in step with the JSON Schema files in conf/calculations/schemas/.
  * Each `val` matches the `$defs` entry of the same name, so a new property is added in three places:
  * the schema file, the shape here, and the case class it decodes into.
  */
object ConfigShapes:

  /** A key in conf/messages, e.g. `Total_income` or `calculations.taskList.item.aboutYou`. */
  val MessageKeyPattern = "^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)*$".r
  /** Question and rate ids; they become answer keys, so no dots or dashes. */
  val IdPattern = "^[A-Za-z][A-Za-z0-9_]*$".r
  /** Task and section ids; they appear in URLs. */
  val TaskIdPattern = "^[A-Za-z][A-Za-z0-9-]*$".r
  val TaxYearPattern = "^[0-9]{4}-[0-9]{2}$".r

  private val messageKey = pattern(MessageKeyPattern)
  private val id = pattern(IdPattern)
  private val taskId = pattern(TaskIdPattern)

  private val showIf = obj(
    required("field", nonEmptyString),
    optional("equals", string(allowEmpty = true)),
    optional("contains", string(allowEmpty = true)),
    optional("notEquals", string(allowEmpty = true))
  )

  // rate-catalog.schema.json

  private val rate = obj(
    required("key", id),
    required("label", messageKey),
    required("kind", oneOf(RateKind.values))
  )

  private val ratePack = obj(
    required("taxYear", pattern(TaxYearPattern)),
    required("version", nonEmptyString),
    required("values", mapOf(number(min = Some(BigDecimal(0)))))
  )

  val rateCatalog: JsonShape = obj(
    required("version", nonEmptyString),
    required("rates", arrayOf(rate, minItems = 1)),
    required("years", arrayOf(ratePack, minItems = 1))
  )

  // question-pack.schema.json

  private val option = obj(
    required("value", nonEmptyString),
    required("label", messageKey)
  )

  private val question = obj(
    required("id", id),
    required("type", oneOf(QuestionType.values)),
    required("title", messageKey),
    optional("hint", messageKey),
    optional("options", arrayOf(option)),
    optional("required", boolean),
    optional("showIf", showIf),
    optional("feeds", nonEmptyString),
    optional("perTaxYear", boolean),
    optional("optionsFromRates", boolean)
  )

  private val task = obj(
    required("id", taskId),
    required("title", messageKey),
    optional("hint", messageKey),
    required("questions", arrayOf(id))
  )

  private val leftoverTask = obj(
    required("id", taskId),
    required("title", messageKey),
    optional("hint", messageKey)
  )

  private val categoryTask = obj(
    required("value", nonEmptyString),
    required("title", messageKey),
    optional("yearTitle", messageKey)
  )

  private val forEachSelected = obj(
    required("question", id),
    required("tasks", arrayOf(categoryTask, minItems = 1))
  )

  private val section = obj(
    required("id", taskId),
    required("title", messageKey),
    optional("intro", messageKey),
    optional("startsAfter", arrayOf(taskId)),
    optional("inOrder", boolean),
    optional("tasks", arrayOf(task)),
    optional("forEachSelected", forEachSelected)
  )

  private val eachYear = obj(
    required("title", messageKey),
    optional("intro", messageKey),
    optional("startsAfter", arrayOf(taskId)),
    optional("before", arrayOf(task)),
    optional("leftover", leftoverTask),
    optional("after", arrayOf(task))
  )

  private val taskList = obj(
    required("sections", arrayOf(section, minItems = 1)),
    optional("eachYear", eachYear)
  )

  val questionPack: JsonShape = obj(
    required("id", nonEmptyString),
    required("title", messageKey),
    required("questions", arrayOf(question, minItems = 1)),
    required("taskList", taskList)
  )

  // calculation-spec.schema.json

  private val incomeComponent = obj(
    required("id", nonEmptyString),
    required("label", messageKey),
    required("kind", oneOf(IncomeComponentKind.values)),
    optional("field", nonEmptyString),
    optional("grossField", nonEmptyString),
    optional("deductField", nonEmptyString),
    optional("altDeductField", nonEmptyString),
    optional("floorAtZero", boolean)
  )

  private val taper = obj(
    required("thresholdRateKey", id),
    required("reduceBy", number(above = Some(BigDecimal(0)))),
    required("forEvery", number(above = Some(BigDecimal(0))))
  )

  private val allowance = obj(
    required("id", nonEmptyString),
    required("label", messageKey),
    required("kind", oneOf(AllowanceKind.values)),
    required("rateKey", id),
    optional("taper", taper),
    optional("when", showIf)
  )

  private val taxBand = obj(
    required("rateKey", id),
    required("label", messageKey),
    optional("upToRateKey", id)
  )

  private val taxRules = obj(
    required("bands", arrayOf(taxBand, minItems = 1)),
    optional("scale", integer(min = 0, max = 6)),
    optional("rounding", oneOf(Rounding.values))
  )

  val calculationSpec: JsonShape = obj(
    required("id", nonEmptyString),
    required("version", nonEmptyString),
    optional("description", string(allowEmpty = true)),
    required("incomeComponents", arrayOf(incomeComponent, minItems = 1)),
    required("allowances", arrayOf(allowance)),
    optional("taxPaidFields", arrayOf(nonEmptyString)),
    required("tax", taxRules)
  )

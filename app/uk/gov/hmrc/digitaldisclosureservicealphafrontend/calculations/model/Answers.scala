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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model

import scala.util.Try

/** Per-tax-year answers are stored under `<questionId>__<taxYear>`, e.g. `employmentIncome__2017-18`. */
object YearAnswers:
  private val Separator = "__"

  def key(baseId: String, taxYear: String): String =
    s"$baseId$Separator$taxYear"

  def parse(id: String): (String, Option[String]) =
    id.split(Separator, 2) match
      case Array(base, year) => (base, Some(year))
      case Array(base)       => (base, None)
      case _                 => (id, None)

/** Reads raw answer strings. Checkbox answers hold several values separated by commas. */
object Answers:

  def splitMulti(raw: String): Seq[String] =
    raw.split(',').toSeq.map(_.trim).filter(_.nonEmpty)

  def joinMulti(values: Seq[String]): String =
    values.map(_.trim).filter(_.nonEmpty).mkString(",")

  def values(answers: Map[String, String], field: String): Seq[String] =
    splitMulti(answers.getOrElse(field, ""))

  /** Parses a money answer, allowing thousands separators. Blank or invalid input gives None. */
  def parseAmount(raw: String): Option[BigDecimal] =
    Option(raw.replace(",", "").trim).filter(_.nonEmpty).flatMap(s => Try(BigDecimal(s)).toOption)

  /** The amount for one tax year, preferring the year-specific answer. Missing answers count as £0. */
  def amountForYear(answers: Map[String, String], field: String, taxYear: String): BigDecimal =
    answers
      .get(YearAnswers.key(field, taxYear))
      .orElse(answers.get(field))
      .flatMap(parseAmount)
      .getOrElse(BigDecimal(0))

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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.QuestionOption

import play.api.i18n.Messages

/**
  * Config titles/hints/labels are message keys: English words joined with underscores.
  * Example config value: How_much_dividend_income_do_you_need_to_disclose
  */
object CalculationsI18n:

  def text(key: String, translate: String => String): String =
    translate(key)

  def text(key: Option[String], translate: String => String): Option[String] =
    key.map(translate)

  def optionLabel(option: QuestionOption, translate: String => String): QuestionOption =
    option.copy(label = text(option.label, translate))

  /**
    * Resolve a calculation breakdown label.
    * Supports `Personal_allowance.withYear:2015-16` → messages("Personal_allowance_with_year", …).
    */
  def breakdownLabel(raw: String)(implicit messages: Messages): String =
    val withYearSuffix = ".withYear:"
    val idx = raw.indexOf(withYearSuffix)
    if idx > 0 then
      val baseKey = raw.substring(0, idx)
      val year = raw.substring(idx + withYearSuffix.length)
      messages(s"${baseKey}_with_year", messages(baseKey), year)
    else messages(raw)

  def breakdownLabel(raw: String, translate: String => String): String =
    val withYearSuffix = ".withYear:"
    val idx = raw.indexOf(withYearSuffix)
    if idx > 0 then
      val baseKey = raw.substring(0, idx)
      val year = raw.substring(idx + withYearSuffix.length)
      translate(s"${baseKey}_with_year").replace("{0}", translate(baseKey)).replace("{1}", year)
    else translate(raw)

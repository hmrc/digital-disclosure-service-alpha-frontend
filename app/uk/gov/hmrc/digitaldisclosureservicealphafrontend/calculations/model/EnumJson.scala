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

import play.api.libs.json.{Format, JsError, JsResult, JsString, JsSuccess, JsValue}

/** JSON for a Scala 3 enum whose case names are the values allowed in config. */
object EnumJson:

  def format[E](values: Array[E], what: String): Format[E] =
    val byName = values.map(value => value.toString -> value).toMap
    new Format[E]:
      def reads(json: JsValue): JsResult[E] =
        json.validate[String].flatMap: raw =>
          byName.get(raw) match
            case Some(value) => JsSuccess(value)
            case None        => JsError(s"Unknown $what '$raw'")
      def writes(value: E): JsValue = JsString(value.toString)

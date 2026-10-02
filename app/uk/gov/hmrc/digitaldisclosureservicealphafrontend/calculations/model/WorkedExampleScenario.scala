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

import play.api.libs.json.{Json, OFormat}

/**
  * A sample customer shown on the graph page. Answers cover every question pack; only
  * those for questions the current pack would ask are used.
  *
  * @param taxYearCount    how many of the catalogue's tax years (from the earliest) the example covers
  * @param answersEachYear answers repeated for each of those years, keyed by base question id
  */
final case class WorkedExampleScenario(
  id             : String,
  title          : String,
  summary        : String,
  taxYearCount   : Int,
  answers        : Map[String, String],
  answersEachYear: Map[String, String]
):
  def answersFor(taxYears: Seq[String]): Map[String, String] =
    Map(QuestionPack.TaxYearsQuestionId -> taxYears.mkString(",")) ++
      answers ++
      taxYears.flatMap(year => answersEachYear.map((id, value) => YearAnswers.key(id, year) -> value))

object WorkedExampleScenario:
  given OFormat[WorkedExampleScenario] = Json.format[WorkedExampleScenario]

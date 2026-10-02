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

import play.api.libs.json.{Json, Writes}

/** The three config documents as JSON text (what the config page edits) alongside their validated models. */
final case class CalculationsConfig(
  rateJson       : String,
  questionJson   : String,
  calculationJson: String,
  rateCatalog    : RateCatalog,
  questionPack   : QuestionPack,
  calculationSpec: CalculationSpec
):
  def withRateCatalog(catalog: RateCatalog): CalculationsConfig =
    copy(rateCatalog = catalog, rateJson = CalculationsConfig.prettyJson(catalog))

object CalculationsConfig:

  def apply(rateCatalog: RateCatalog, questionPack: QuestionPack, calculationSpec: CalculationSpec): CalculationsConfig =
    CalculationsConfig(
      rateJson = prettyJson(rateCatalog),
      questionJson = prettyJson(questionPack),
      calculationJson = prettyJson(calculationSpec),
      rateCatalog = rateCatalog,
      questionPack = questionPack,
      calculationSpec = calculationSpec
    )

  def prettyJson[A: Writes](value: A): String =
    Json.prettyPrint(Json.toJson(value))

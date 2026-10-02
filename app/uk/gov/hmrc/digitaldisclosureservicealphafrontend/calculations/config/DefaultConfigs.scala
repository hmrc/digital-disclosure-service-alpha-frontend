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

import play.api.libs.json.Json
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  CalculationsConfig,
  WorkedExampleScenario
}

import scala.io.Source
import scala.util.Using

/**
  * The shipped config documents under conf/calculations/defaults, validated with the same
  * rules as edited config. Defaults follow the Version 1.4 design-focus scenario (2015–16 to 2017–18).
  *
  *  - Options 1 and 4 use the fixed question pack (no branching; questions cannot be edited).
  *  - Option 2 uses the full pack: shared circumstances, then the income and capital gains category tree.
  */
object DefaultConfigs:

  val RateCatalogueResource   = "calculations/defaults/rate-catalogue.json"
  val FixedQuestionsResource  = "calculations/defaults/question-pack-fixed.json"
  val FullQuestionsResource   = "calculations/defaults/question-pack-full.json"
  val CalculationSpecResource = "calculations/defaults/calculation-spec.json"
  val WorkedExamplesResource  = "calculations/examples/worked-examples.json"

  def defaultsFor(option: ArchitectureOption): CalculationsConfig =
    if option.questionsEditable then fullQuestions else fixedQuestions

  lazy val workedExamples: Seq[WorkedExampleScenario] =
    Json.parse(read(WorkedExamplesResource)).as[Seq[WorkedExampleScenario]]

  private lazy val fixedQuestions = load(FixedQuestionsResource)
  private lazy val fullQuestions  = load(FullQuestionsResource)

  private def load(questionResource: String): CalculationsConfig =
    ConfigValidator.validate(
      read(RateCatalogueResource),
      read(questionResource),
      read(CalculationSpecResource)
    ) match
      case Right(config) => config
      case Left(errors) =>
        throw new IllegalStateException(
          s"Default calculations config ($questionResource) is invalid: ${ConfigValidator.formatErrors(errors)}"
        )

  private def read(resource: String): String =
    Using.resource(Source.fromResource(resource))(_.mkString)

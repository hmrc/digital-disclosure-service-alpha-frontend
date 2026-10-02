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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations

import play.api.Configuration
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.DefaultConfigs
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.downstream.DownstreamRateCatalogService
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ArchitectureOption,
  CalculationSpec,
  CalculationsConfig,
  QuestionPack,
  RateCatalog,
  RatePack,
  SessionState
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.InProcessIncomeTaxCalculationConnector
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import scala.concurrent.ExecutionContext

/** The shipped default config, and sessions built from it, for calculations specs. */
object CalculationsFixtures:

  val fixedConfig: CalculationsConfig = DefaultConfigs.defaultsFor(ArchitectureOption.RatesOnly)
  val fullConfig: CalculationsConfig = DefaultConfigs.defaultsFor(ArchitectureOption.RatesAndQuestions)

  val catalog: RateCatalog = fullConfig.rateCatalog
  val fixedPack: QuestionPack = fixedConfig.questionPack
  val fullPack: QuestionPack = fullConfig.questionPack
  val spec: CalculationSpec = fullConfig.calculationSpec

  def ratesFor(taxYear: String): RatePack =
    catalog.forYear(taxYear).getOrElse(throw new NoSuchElementException(s"No default rates for $taxYear"))

  /** Answers to the shared "about you" questions for someone with no extra allowances. */
  val aboutYou: Map[String, String] = Map(
    "ageBand" -> "under65",
    "marriedOrCivilPartnership" -> "no",
    "blindPersonEligible" -> "no"
  )

  def stateFor(option: ArchitectureOption, answers: Map[String, String] = Map.empty): SessionState =
    SessionState(id = "test", option = option, config = DefaultConfigs.defaultsFor(option), answers = answers)

  /** Option 4's rates service wired to the in-process HIP 5294 stub. */
  def downstreamRatesService(using ExecutionContext): DownstreamRateCatalogService =
    val config = Configuration(
      "appName"                                     -> "digital-disclosure-service-alpha-frontend",
      "auth.sign-in-url"                            -> "http://localhost:9949/auth-login-stub/gg-sign-in",
      "upscan.max-file-size"                        -> 10485760L,
      "microservice.services.dds-frontend.host"     -> "localhost",
      "microservice.services.dds-frontend.port"     -> 9000,
      "microservice.services.dds-frontend.protocol" -> "http",
      "calculations.mtd-retrieve.nino"              -> "AA123456A"
    )
    new DownstreamRateCatalogService(
      new InProcessIncomeTaxCalculationConnector,
      new AppConfig(config, new ServicesConfig(config))
    )

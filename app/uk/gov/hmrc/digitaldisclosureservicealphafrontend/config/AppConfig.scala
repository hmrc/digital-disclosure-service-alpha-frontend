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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.config

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

@Singleton
class AppConfig @Inject()(config: Configuration, servicesConfig: ServicesConfig):

  val welshLanguageSupportEnabled: Boolean =
    config.getOptional[Boolean]("features.welsh-language-support").getOrElse(false)

  val appName: String =
    config.get[String]("appName")

  val ddsBaseUrl: String =
    servicesConfig.baseUrl("dds-frontend")

  // Where to send a user with no active session. Locally this is the
  // auth-login-stub ("authority wizard"); on a deployed environment it would be
  // the bas-gateway sign-in URL.
  val signInUrl: String =
    config.get[String]("auth.sign-in-url")

  val upscanMaxFileSize: Long =
    config.get[Long]("upscan.max-file-size")

  // Tax type sent on the charge-reference notification. A production DDS
  // integration would use the tax type ETMP expects for a disclosure charge.
  val paymentsChargeTaxType: String =
    config.getOptional[String]("payments.charge-notification-tax-type").getOrElse("DDS")

  val individualPocEnabled: Boolean =
    config.getOptional[Boolean]("features.individual-auth-poc").getOrElse(false)

  val individualPocIvOrigin: String =
    config.get[String]("individual-poc.iv-origin")

  val individualPocTargetConfidenceLevel: Int =
    config.get[Int]("individual-poc.target-confidence-level")

  val individualPocStandInService: String =
    config.get[String]("individual-poc.stand-in-service-key")

  val individualPocStandInIdentifierKey: String =
    config.get[String]("individual-poc.stand-in-identifier-key")

  // BAS gateway sign-in for the individual IV chain (accountType=individual).
  val individualSignInUrl: String =
    config.get[String]("auth.individual-sign-in-url")

  val ivUpliftUrl: String =
    config.get[String]("external-urls.iv-uplift")

  // Base URL for IV journey status polling (use IV stub base when stubbing locally).
  val ivJourneyStatusUrl: String =
    config.get[String]("external-urls.iv-journey-status")

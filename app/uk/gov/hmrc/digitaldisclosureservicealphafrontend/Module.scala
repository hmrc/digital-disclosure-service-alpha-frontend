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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend

import play.api.{Configuration, Environment}
import play.api.inject.{Binding, Module => AppModule}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.{
  CardPaymentInternalAuthInitialiser,
  CardPaymentInternalAuthInitialiserImpl,
  NoOpCardPaymentInternalAuthInitialiser
}

import java.time.Clock

class Module extends AppModule:

  override def bindings(
    environment  : Environment,
    configuration: Configuration
  ): Seq[Binding[_]] =

    val cardPaymentAuthBindings: Seq[Binding[_]] =
      if configuration.getOptional[Boolean]("payments.seed-card-payment-internal-auth-on-start").getOrElse(false) then
        Seq(bind[CardPaymentInternalAuthInitialiser].to[CardPaymentInternalAuthInitialiserImpl].eagerly())
      else
        Seq(bind[CardPaymentInternalAuthInitialiser].to[NoOpCardPaymentInternalAuthInitialiser].eagerly())

    bind[Clock].toInstance(Clock.systemDefaultZone) +: // inject if current time needs to be controlled in unit tests
      cardPaymentAuthBindings

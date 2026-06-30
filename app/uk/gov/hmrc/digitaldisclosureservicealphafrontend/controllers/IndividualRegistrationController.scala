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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers

import play.api.i18n.I18nSupport
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.AgentsExternalStubsConnector
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.{StubKnownFactsRequest, StubVerifier}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.IndividualRegistrationPage
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class IndividualRegistrationController @Inject()(
  mcc                   : MessagesControllerComponents,
  defaultAction         : play.api.mvc.DefaultActionBuilder,
  individualPocEnabled  : IndividualPocEnabledAction,
  individualAuthOrchestrator: IndividualAuthOrchestrator,
  individualIdentifier  : IndividualIdentifierAction,
  stubsConnector        : AgentsExternalStubsConnector,
  appConfig             : AppConfig,
  registrationPage      : IndividualRegistrationPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport:

  private val registerAction =
    defaultAction andThen individualPocEnabled andThen individualAuthOrchestrator andThen individualIdentifier

  val register: Action[AnyContent] = registerAction:
    implicit request =>
      Ok(registrationPage(request.nino, None, appConfig))

  val submitRegister: Action[AnyContent] = registerAction.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      val service      = appConfig.individualPocStandInService
      val mtdItId      = s"X${request.nino.replaceAll("\\s", "").take(8)}D"
      val enrolmentKey = s"$service~${appConfig.individualPocStandInIdentifierKey}~$mtdItId"
      val payload      = Json.toJson(
        StubKnownFactsRequest(
          enrolmentKey = enrolmentKey,
          verifiers    = Seq(StubVerifier("NINO", request.nino))
        )
      )

      stubsConnector.createKnownFacts(payload).map { ok =>
        val message =
          if ok then
            s"Known facts created for $enrolmentKey (stand-in for a DDS principal enrolment). " +
              "In production EACD would allocate a claimed principal enrolment to your credential. " +
              "Locally, add the enrolment to your auth-login-stub user and sign in again to see it on the session page."
          else
            "Could not reach agents-external-stubs. Start AGENTS_STUBS (port 9009) and try again."

        Ok(registrationPage(request.nino, Some(message), appConfig))
      }

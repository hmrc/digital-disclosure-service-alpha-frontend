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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions

import play.api.mvc.*
import uk.gov.hmrc.auth.core.{AuthConnector, AuthorisedFunctions, NoActiveSession}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** Requires an active MDTP session. Without one, the user is redirected to the
  * sign-in URL (locally the auth-login-stub) with a continue URL back to where
  * they were heading.
  *
  * The payment origins are authenticated journeys, so the user must be signed in
  * before being handed off to pay-frontend; this also gives the SPJ call the
  * `sessionId` that pay-api requires.
  */
@Singleton
class AuthenticatedAction @Inject()(
  override val authConnector: AuthConnector,
  appConfig                 : AppConfig,
  val parser                : BodyParsers.Default
)(using val executionContext: ExecutionContext)
  extends ActionBuilder[Request, AnyContent]
  with AuthorisedFunctions:

  override def invokeBlock[A](request: Request[A], block: Request[A] => Future[Result]): Future[Result] =
    given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorised() {
      block(request)
    }.recover {
      case _: NoActiveSession =>
        val continueUrl = s"${appConfig.ddsBaseUrl}${request.uri}"
        Results.Redirect(
          appConfig.signInUrl,
          Map(
            "continue" -> Seq(continueUrl),
            "origin"   -> Seq(appConfig.appName)
          )
        )
    }

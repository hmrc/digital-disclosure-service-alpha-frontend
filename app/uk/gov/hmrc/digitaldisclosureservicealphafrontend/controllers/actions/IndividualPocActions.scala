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
import uk.gov.hmrc.auth.core.AffinityGroup.Individual
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class IndividualRequest[A](
  nino            : String,
  confidenceLevel : ConfidenceLevel,
  affinityGroup   : Option[AffinityGroup],
  enrolments      : Set[Enrolment],
  request         : Request[A]
) extends WrappedRequest[A](request)

@Singleton
class IndividualPocEnabledAction @Inject()(
  appConfig: AppConfig
)(using ec: ExecutionContext)
  extends ActionFilter[Request]:

  override protected def executionContext: ExecutionContext = ec

  override def filter[A](request: Request[A]): Future[Option[Result]] =
    if appConfig.individualPocEnabled then Future.successful(None)
    else Future.successful(Some(Results.NotFound))

/** Redirects unauthenticated users to sign-in and users without NINO/CL to IV uplift
  * (PTA-style orchestration). Returns `Left(Redirect)` when the user must leave the
  * journey; `Right(request)` when identity prerequisites are satisfied.
  */
@Singleton
class IndividualAuthOrchestrator @Inject()(
  override val authConnector: AuthConnector,
  appConfig                 : AppConfig
)(using val executionContext: ExecutionContext)
  extends ActionRefiner[Request, Request]
  with AuthorisedFunctions:

  private val servicePrefix = "/digital-disclosure-service-alpha-frontend"

  override protected def refine[A](request: Request[A]): Future[Either[Result, Request[A]]] =
    given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorised()
      .retrieve(Retrievals.nino and Retrievals.confidenceLevel) {
        case Some(_) ~ confidenceLevel
            if confidenceLevel.level >= appConfig.individualPocTargetConfidenceLevel =>
          Future.successful(Right(request))
        case _ =>
          Future.successful(Left(ivUpliftRedirect(request)))
      }
      .recover {
        case _: NoActiveSession =>
          Left(signInRedirect(request))
      }

  private def signInRedirect[A](request: Request[A]): Result =
    val continueUrl = s"${appConfig.ddsBaseUrl}${request.uri}"
    Results.Redirect(
      appConfig.individualSignInUrl,
      Map(
        "continue"    -> Seq(continueUrl),
        "origin"      -> Seq(appConfig.appName),
        "accountType" -> Seq("individual")
      )
    )

  private def ivUpliftRedirect[A](request: Request[A]): Result =
    val continuePath  = request.uri
    val completionUrl = s"$servicePrefix/individual-poc/iv-complete?continueUrl=$continuePath"
    val failureUrl    = s"$servicePrefix/individual-poc/iv-failed?continueUrl=$continuePath"

    Results.Redirect(
      appConfig.ivUpliftUrl,
      Map(
        "origin"          -> Seq(appConfig.individualPocIvOrigin),
        "confidenceLevel" -> Seq(appConfig.individualPocTargetConfidenceLevel.toString),
        "completionURL"   -> Seq(completionUrl),
        "failureURL"      -> Seq(failureUrl)
      )
    )

@Singleton
class IndividualIdentifierAction @Inject()(
  override val authConnector: AuthConnector,
  val parser                : BodyParsers.Default
)(using val executionContext: ExecutionContext)
  extends ActionRefiner[Request, IndividualRequest]
  with AuthorisedFunctions:

  override protected def refine[A](request: Request[A]): Future[Either[Result, IndividualRequest[A]]] =
    given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorised(Individual)
      .retrieve(
        Retrievals.nino and
          Retrievals.confidenceLevel and
          Retrievals.affinityGroup and
          Retrievals.allEnrolments
      ) {
        case Some(nino) ~ confidenceLevel ~ affinityGroup ~ Enrolments(enrolments) =>
          Future.successful(
            Right(
              IndividualRequest(
                nino            = nino,
                confidenceLevel = confidenceLevel,
                affinityGroup   = affinityGroup,
                enrolments      = enrolments,
                request         = request
              )
            )
          )
        case _ =>
          Future.successful(Left(Results.Forbidden("NINO not found on session")))
      }
      .recover {
        case _: AuthorisationException =>
          Left(Results.Forbidden("Individual authorisation failed"))
      }

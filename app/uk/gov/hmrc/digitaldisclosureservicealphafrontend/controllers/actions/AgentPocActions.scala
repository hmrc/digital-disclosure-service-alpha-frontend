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
import uk.gov.hmrc.auth.core.AffinityGroup.Agent
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.retrieve.*
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class AgentRequest[A](
  arn        : String,
  internalId : String,
  request    : Request[A]
) extends WrappedRequest[A](request)

@Singleton
class AgentIdentifierAction @Inject()(
  override val authConnector: AuthConnector,
  appConfig                 : AppConfig,
  val parser                : BodyParsers.Default
)(using val executionContext: ExecutionContext)
  extends ActionBuilder[AgentRequest, AnyContent]
  with AuthorisedFunctions:

  override def invokeBlock[A](request: Request[A], block: AgentRequest[A] => Future[Result]): Future[Result] =
    given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

    authorised(Enrolment("HMRC-AS-AGENT") and Agent)
      .retrieve(Retrievals.internalId and Retrievals.allEnrolments) { case internalIdOpt ~ enrolments =>
        val arnOpt = enrolments
          .getEnrolment("HMRC-AS-AGENT")
          .flatMap(_.getIdentifier("AgentReferenceNumber").map(_.value))

        (internalIdOpt, arnOpt) match
          case (Some(internalId), Some(arn)) =>
            block(AgentRequest(arn, internalId, request))
          case _ =>
            Future.successful(Results.Forbidden("Agent enrolment or ARN not found"))
      }
      .recover {
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

@Singleton
class AgentPocEnabledAction @Inject()(
  appConfig: AppConfig
)(using ec: ExecutionContext)
  extends ActionFilter[Request]:

  override protected def executionContext: ExecutionContext = ec

  override def filter[A](request: Request[A]): Future[Option[Result]] =
    if appConfig.agentPocEnabled then Future.successful(None)
    else Future.successful(Some(Results.NotFound))

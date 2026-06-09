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

import play.api.Configuration
import play.api.Logging
import play.api.libs.json.Json
import play.api.libs.ws.writeableOf_JsValue
import uk.gov.hmrc.http.{HeaderCarrier, HttpResponse}
import uk.gov.hmrc.http.HttpReads.Implicits.*
import uk.gov.hmrc.http.client.HttpClientV2
import uk.gov.hmrc.http.StringContextOps
import uk.gov.hmrc.play.bootstrap.config.ServicesConfig

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.control.NonFatal

/** Local-dev helper: registers the internal-auth token that card-payment-frontend
  * uses to call card-payment, so teammates do not need to run curl manually.
  *
  * This is not part of the DDS integration — DDS never calls card-payment. It
  * only runs when `payments.seed-card-payment-internal-auth-on-start` is true
  * (the default in this PoC's application.conf).
  */
abstract class CardPaymentInternalAuthInitialiser:
  val initialised: Future[Unit]

@Singleton
class NoOpCardPaymentInternalAuthInitialiser @Inject() () extends CardPaymentInternalAuthInitialiser:
  override val initialised: Future[Unit] = Future.successful(())

@Singleton
class CardPaymentInternalAuthInitialiserImpl @Inject()(
  configuration : Configuration,
  servicesConfig: ServicesConfig,
  httpClient    : HttpClientV2
)(using ec: ExecutionContext)
  extends CardPaymentInternalAuthInitialiser
  with Logging:

  private val internalAuthBaseUrl: String =
    servicesConfig.baseUrl("internal-auth")

  private val token: String =
    configuration.get[String]("payments.card-payment-internal-auth-token")

  private val principal: String =
    configuration.get[String]("payments.card-payment-internal-auth-principal")

  override val initialised: Future[Unit] =
    ensureToken().recover { case NonFatal(e) =>
      logger.warn(
        s"Could not seed card-payment internal-auth token (${e.getMessage}). " +
          "Card payments may fail at 'check your details' until internal-auth is running."
      )
    }

  Await.result(initialised, 30.seconds)

  private def ensureToken(): Future[Unit] =
    tokenIsValid.flatMap:
      case true =>
        logger.info("card-payment internal-auth token is already registered")
        Future.successful(())
      case false =>
        registerToken()

  private def tokenIsValid: Future[Boolean] =
    given HeaderCarrier = HeaderCarrier()
    httpClient
      .get(url"$internalAuthBaseUrl/test-only/token")
      .setHeader("Authorization" -> token)
      .execute[HttpResponse]
      .map(_.status == 200)
      .recover { case NonFatal(_) => false }

  private def registerToken(): Future[Unit] =
    given HeaderCarrier = HeaderCarrier()
    logger.info("Registering card-payment internal-auth token for local development")
    httpClient
      .post(url"$internalAuthBaseUrl/test-only/token")
      .withBody(
        Json.obj(
          "token"       -> token,
          "principal"   -> principal,
          "permissions" -> Seq(
            Json.obj(
              "resourceType"     -> "card-payment",
              "resourceLocation" -> "*",
              "actions"          -> Json.arr("*")
            )
          )
        )
      )
      .execute[HttpResponse]
      .flatMap:
        case response if response.status == 201 =>
          logger.info("card-payment internal-auth token registered")
          Future.successful(())
        case response =>
          Future.failed(
            new RuntimeException(
              s"Unexpected response registering card-payment internal-auth token: ${response.status}"
            )
          )

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

import play.api.Logging
import play.api.data.Form
import play.api.data.Forms.*
import play.api.libs.json.Json
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.UpscanConnector
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.UploadJourneyRepository
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.*
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import java.time.Instant
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

case class DisclosureFormData(
  fullName   : String,
  description: String,
  amount     : String
)

@Singleton
class UpscanController @Inject()(
  mcc              : MessagesControllerComponents,
  upscanConnector  : UpscanConnector,
  repository       : UploadJourneyRepository,
  appConfig        : AppConfig,
  demoPage         : UpscanDemoPage,
  userUploadPage   : UserUploadPage,
  generatePage     : GenerateAndUploadPage,
  waitingPage      : UploadWaitingPage,
  resultPage       : UploadResultPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with Logging:

  private val disclosureForm: Form[DisclosureFormData] = Form(
    mapping(
      "fullName"    -> nonEmptyText,
      "description" -> nonEmptyText,
      "amount"      -> nonEmptyText
    )(DisclosureFormData.apply)(d => Some(Tuple.fromProductTyped(d)))
  )

  val demo: Action[AnyContent] = Action:
    implicit request =>
      Ok(demoPage())

  val userUpload: Action[AnyContent] = Action.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      val callbackUrl     = s"${appConfig.selfUrl}/digital-disclosure-service-alpha-frontend/upscan/callback"
      val successRedirect = s"${appConfig.selfUrl}/digital-disclosure-service-alpha-frontend/upscan/upload-result"
      val errorRedirect   = s"${appConfig.selfUrl}/digital-disclosure-service-alpha-frontend/upscan/upload-error"

      val initiateRequest = UpscanInitiateRequest(
        callbackUrl     = callbackUrl,
        successRedirect = Some(successRedirect),
        errorRedirect   = Some(errorRedirect),
        maximumFileSize = Some(10 * 1024 * 1024)
      )

      upscanConnector.initiate(initiateRequest).flatMap: response =>
        val journey = UploadJourney(
          reference  = response.reference,
          uploadType = "user",
          status     = "Initiated"
        )
        repository.upsert(journey).map: _ =>
          Ok(userUploadPage(response))

  val showGenerateForm: Action[AnyContent] = Action:
    implicit request =>
      Ok(generatePage(disclosureForm))

  val submitGenerateForm: Action[AnyContent] = Action.async:
    implicit request =>
      given HeaderCarrier = HeaderCarrierConverter.fromRequestAndSession(request, request.session)

      disclosureForm.bindFromRequest().fold(
        formWithErrors =>
          Future.successful(BadRequest(generatePage(formWithErrors))),
        formData =>
          val callbackUrl = s"${appConfig.selfUrl}/digital-disclosure-service-alpha-frontend/upscan/callback"

          val initiateRequest = UpscanInitiateRequest(
            callbackUrl     = callbackUrl,
            maximumFileSize = Some(10 * 1024 * 1024)
          )

          upscanConnector.initiate(initiateRequest).flatMap: response =>
            val fileContent = Json.prettyPrint(Json.obj(
              "disclosureType" -> "Digital Disclosure Service Alpha",
              "fullName"       -> formData.fullName,
              "description"    -> formData.description,
              "amount"         -> formData.amount,
              "generatedAt"    -> Instant.now().toString
            ))

            val journey = UploadJourney(
              reference  = response.reference,
              uploadType = "service",
              status     = "Initiated"
            )

            for
              _      <- repository.upsert(journey)
              status <- upscanConnector.uploadFile(
                          uploadUrl = response.uploadRequest.href,
                          fields    = response.uploadRequest.fields,
                          fileName  = "disclosure-summary.json",
                          mimeType  = "application/json",
                          content   = fileContent.getBytes("UTF-8")
                        )
              _      =  logger.info(s"Server-side upload for reference=${response.reference} returned status=$status")
            yield Redirect(routes.UpscanController.uploadWaiting(response.reference))
      )

  def uploadResult: Action[AnyContent] = Action.async:
    implicit request =>
      val reference = request.getQueryString("key").getOrElse("unknown")
      repository.findByReference(reference).map:
        case Some(journey) if journey.status == "Ready" || journey.status == "Failed" =>
          Ok(resultPage(journey))
        case _ =>
          Redirect(routes.UpscanController.uploadWaiting(reference))

  def uploadError: Action[AnyContent] = Action:
    implicit request =>
      val errorCode    = request.getQueryString("errorCode").getOrElse("Unknown")
      val errorMessage = request.getQueryString("errorMessage").getOrElse("An unknown error occurred")
      val reference    = request.getQueryString("key").getOrElse("unknown")

      Ok(resultPage(UploadJourney(
        reference      = reference,
        uploadType     = "user",
        status         = "Failed",
        failureReason  = Some(errorCode),
        failureMessage = Some(errorMessage),
        createdAt      = Instant.now()
      )))

  def uploadWaiting(reference: String): Action[AnyContent] = Action.async:
    implicit request =>
      repository.findByReference(reference).map:
        case Some(journey) if journey.status == "Ready" || journey.status == "Failed" =>
          Redirect(routes.UpscanController.uploadResultDirect(reference))
        case Some(journey) =>
          Ok(waitingPage(reference))
        case None =>
          Ok(waitingPage(reference))

  def uploadResultDirect(reference: String): Action[AnyContent] = Action.async:
    implicit request =>
      repository.findByReference(reference).map:
        case Some(journey) => Ok(resultPage(journey))
        case None          => NotFound

  def uploadStatus(reference: String): Action[AnyContent] = Action.async:
    implicit request =>
      repository.findByReference(reference).map:
        case Some(journey) =>
          Ok(Json.obj(
            "reference" -> journey.reference,
            "status"    -> journey.status
          ))
        case None =>
          Ok(Json.obj(
            "reference" -> reference,
            "status"    -> "Initiated"
          ))

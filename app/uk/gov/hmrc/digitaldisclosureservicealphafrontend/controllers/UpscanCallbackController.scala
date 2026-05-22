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
import play.api.libs.json.JsValue
import play.api.mvc.{Action, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.repositories.UploadJourneyRepository
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class UpscanCallbackController @Inject()(
  mcc       : MessagesControllerComponents,
  repository: UploadJourneyRepository
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with Logging:

  val callback: Action[JsValue] = Action.async(parse.json):
    request =>
      request.body.validate[UpscanCallback].fold(
        errors =>
          logger.error(s"Failed to parse upscan callback: $errors")
          scala.concurrent.Future.successful(BadRequest("Invalid callback payload")),
        upscanCallback =>
          logger.info(s"Received upscan callback for reference=${upscanCallback.reference}, status=${upscanCallback.fileStatus}")

          val update = upscanCallback.fileStatus match
            case "READY" =>
              UploadJourney(
                reference    = upscanCallback.reference,
                uploadType   = "",
                status       = "Ready",
                downloadUrl  = upscanCallback.downloadUrl,
                fileName     = upscanCallback.uploadDetails.map(_.fileName),
                fileMimeType = upscanCallback.uploadDetails.map(_.fileMimeType),
                fileSize     = upscanCallback.uploadDetails.map(_.size)
              )
            case "FAILED" =>
              UploadJourney(
                reference      = upscanCallback.reference,
                uploadType     = "",
                status         = "Failed",
                failureReason  = upscanCallback.failureDetails.map(_.failureReason),
                failureMessage = upscanCallback.failureDetails.map(_.message)
              )
            case other =>
              logger.warn(s"Unexpected file status: $other for reference=${upscanCallback.reference}")
              UploadJourney(
                reference  = upscanCallback.reference,
                uploadType = "",
                status     = other
              )

          repository.findByReference(upscanCallback.reference).flatMap:
            case Some(existing) =>
              val merged = update.copy(
                uploadType = existing.uploadType,
                createdAt  = existing.createdAt
              )
              repository.upsert(merged).map(_ => Ok)
            case None =>
              repository.upsert(update).map(_ => Ok)
      )

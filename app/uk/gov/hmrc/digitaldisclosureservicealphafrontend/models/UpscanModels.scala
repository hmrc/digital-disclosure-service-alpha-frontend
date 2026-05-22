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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.models

import play.api.libs.json.*

case class UpscanInitiateRequest(
  callbackUrl    : String,
  successRedirect: Option[String] = None,
  errorRedirect  : Option[String] = None,
  minimumFileSize: Option[Long]   = None,
  maximumFileSize: Option[Long]   = None
)

object UpscanInitiateRequest:
  given OWrites[UpscanInitiateRequest] = Json.writes[UpscanInitiateRequest]

case class UploadFormTemplate(
  href  : String,
  fields: Map[String, String]
)

object UploadFormTemplate:
  given Reads[UploadFormTemplate] = Json.reads[UploadFormTemplate]

case class UpscanInitiateResponse(
  reference    : String,
  uploadRequest: UploadFormTemplate
)

object UpscanInitiateResponse:
  given Reads[UpscanInitiateResponse] = Json.reads[UpscanInitiateResponse]

case class UploadDetails(
  fileName       : String,
  fileMimeType   : String,
  uploadTimestamp: String,
  checksum       : String,
  size           : Long
)

object UploadDetails:
  given Format[UploadDetails] = Json.format[UploadDetails]

case class ErrorDetails(
  failureReason: String,
  message      : String
)

object ErrorDetails:
  given Format[ErrorDetails] = Json.format[ErrorDetails]

case class UpscanCallback(
  reference     : String,
  fileStatus    : String,
  downloadUrl   : Option[String]       = None,
  uploadDetails : Option[UploadDetails] = None,
  failureDetails: Option[ErrorDetails]  = None
)

object UpscanCallback:
  given Reads[UpscanCallback] = Json.reads[UpscanCallback]

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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.services

import play.api.libs.json.{JsObject, JsString}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors.{CesaCall, CesaConnector}
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

/** Pulls everything CESA holds for a UTR: the root resource, every resource it links to (and their
  * links, one level further, e.g. designatory-details -> taxpayer), plus calendar and future liabilities.
  */
@Singleton
class CesaLookupService @Inject() (connector: CesaConnector)(using ec: ExecutionContext):

  private val MaxLinkDepth = 2

  def lookup(utr: String)(using HeaderCarrier): Future[Seq[CesaCall]] =
    val rootF     = connector.individual(utr)
    val calendarF = connector.calendar(utr)
    val futureF   = connector.futureLiabilities(utr)
    for
      root     <- rootF
      linked   <- followLinks(root, MaxLinkDepth)
      calendar <- calendarF
      future   <- futureF
    yield (root +: linked) ++ Seq(calendar, future)

  private def followLinks(call: CesaCall, depth: Int)(using HeaderCarrier): Future[Seq[CesaCall]] =
    if depth <= 0 || !call.isSuccess then Future.successful(Nil)
    else
      val links: Seq[(String, String)] =
        call.json
          .flatMap(j => (j \ "links").asOpt[JsObject])
          .map(_.fields.collect { case (name, JsString(href)) => name -> href }.toSeq)
          .getOrElse(Nil)

      Future
        .traverse(links): (name, href) =>
          connector.followLink(name, href).flatMap(child => followLinks(child, depth - 1).map(child +: _))
        .map(_.flatten)

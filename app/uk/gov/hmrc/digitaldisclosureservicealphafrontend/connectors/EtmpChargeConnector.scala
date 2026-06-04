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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.connectors

import play.api.Logging
import uk.gov.hmrc.http.HeaderCarrier

import javax.inject.{Inject, Singleton}
import scala.concurrent.Future

/** Stand-in for the corporate-tier step that raises a charge for a disclosure.
  *
  * In production, before sending the user to pay, DDS (via HIP/DES) would raise
  * a charge against the disclosure on ETMP and receive the charge reference that
  * is associated with it. That reference is then carried into the payment journey
  * and used to reconcile the payment back to the charge.
  *
  * There is no ETMP stub available locally, so this connector simulates that
  * boundary in-process: it returns a generated reference in the format the PoC's
  * payment origin accepts (`X` followed by 13 alphanumerics). Wiring a real call
  * here is the remaining production step.
  */
@Singleton
class EtmpChargeConnector @Inject()() extends Logging:

  def raiseCharge(disclosureId: String, amountInPence: Long)(using HeaderCarrier): Future[String] =
    // Ends in a non-digit so the local DES notification stub returns a clean 200
    // (it uses a trailing digit to simulate retry/error scenarios).
    val chargeReference = f"XDDS${scala.util.Random.nextInt(1000000000)}%09dC"
    logger.info(
      s"[STUB ETMP] Raised charge for disclosure $disclosureId, amount=${amountInPence}p, chargeReference=$chargeReference"
    )
    Future.successful(chargeReference)

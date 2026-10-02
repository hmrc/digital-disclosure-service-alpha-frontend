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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.RatePack

import scala.math.BigDecimal.RoundingMode

object Formats:

  /** £1,234.50 */
  def gbp(amount: BigDecimal): String =
    f"£${amount.setScale(2, RoundingMode.HALF_UP)}%,.2f"

  /** £1,234 for whole amounts, otherwise £1,234.50 */
  def wholePounds(amount: BigDecimal): String =
    if amount == amount.setScale(0, RoundingMode.DOWN) then f"£${amount.setScale(0, RoundingMode.DOWN)}%,.0f"
    else gbp(amount)

  /** 0.2 → 20%, 0.125 → 12.5% */
  def percent(rate: BigDecimal): String =
    s"${(rate * 100).bigDecimal.stripTrailingZeros.toPlainString}%"

  /** A rate catalogue value: a percentage for rates, otherwise an amount. */
  def rateValue(rateKey: String, value: BigDecimal): String =
    if RatePack.isRate(rateKey) then percent(value) else wholePounds(value)

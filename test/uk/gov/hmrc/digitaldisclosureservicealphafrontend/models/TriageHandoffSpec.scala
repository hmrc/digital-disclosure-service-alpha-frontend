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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TriageHandoffSpec extends AnyWordSpec with Matchers:

  private val secret = "alpha-poc-not-for-production"

  "isKnownGovukOption" should:
    "accept GOV.UK-owned options and reject the rest" in:
      TriageHandoff.isKnownGovukOption("two-urls") shouldBe true
      TriageHandoff.isKnownGovukOption("preauth") shouldBe false
      TriageHandoff.isKnownGovukOption("nope") shouldBe false

  "optionTitle" should:
    "return the title or the raw id" in:
      TriageHandoff.optionTitle("query") shouldBe "Query parameters"
      TriageHandoff.optionTitle("missing") shouldBe "missing"

  "startNowTarget" should:
    "map each option and exit" in:
      TriageHandoff.startNowTarget("two-urls", "income-tax") shouldBe StartNowTarget.Ready
      TriageHandoff.startNowTarget("two-urls", "skip") shouldBe StartNowTarget.Default
      TriageHandoff.startNowTarget("two-urls", "mixed") shouldBe StartNowTarget.WrongPlace
      TriageHandoff.startNowTarget("confirm", "income-tax") shouldBe StartNowTarget.Confirm
      TriageHandoff.startNowTarget("confirm", "skip") shouldBe StartNowTarget.Default
      TriageHandoff.startNowTarget("confirm", "mixed") shouldBe StartNowTarget.WrongPlace
      TriageHandoff.startNowTarget("query", "income-tax") shouldBe StartNowTarget.Query("income-tax")
      TriageHandoff.startNowTarget("query", "mixed") shouldBe StartNowTarget.Query("mixed")
      TriageHandoff.startNowTarget("query", "skip") shouldBe StartNowTarget.Query("unknown")
      TriageHandoff.startNowTarget("path", "income-tax") shouldBe StartNowTarget.Path("income-tax")
      TriageHandoff.startNowTarget("path", "mixed") shouldBe StartNowTarget.Path("mixed")
      TriageHandoff.startNowTarget("path", "skip") shouldBe StartNowTarget.Path("untriaged")
      TriageHandoff.startNowTarget("token", "income-tax") shouldBe StartNowTarget.Token("income-tax")
      TriageHandoff.startNowTarget("token", "mixed") shouldBe StartNowTarget.Token("mixed")
      TriageHandoff.startNowTarget("token", "skip") shouldBe StartNowTarget.Token("untriaged")
      TriageHandoff.startNowTarget("unknown", "income-tax") shouldBe StartNowTarget.Default

  "fromReady / fromConfirm / untriaged" should:
    "describe the three URL-hint shapes" in:
      val ready = TriageHandoff.fromReady
      ready.kind shouldBe HandoffKind.ReadyHint
      ready.skipRouting shouldBe true
      ready.showConfirm shouldBe false
      ready.regimeLabel shouldBe "Income Tax only"
      ready.sourceLabel shouldBe "url-hint"

      val confirm = TriageHandoff.fromConfirm
      confirm.kind shouldBe HandoffKind.ConfirmHint
      confirm.showConfirm shouldBe true
      confirm.sourceLabel shouldBe "confirmation"

      val none = TriageHandoff.untriaged
      none.kind shouldBe HandoffKind.Untriaged
      none.skipRouting shouldBe false
      none.regimeLabel shouldBe "None"
      none.sourceLabel shouldBe "answered-in-dds"

  "fromPath" should:
    "interpret known and unknown exits" in:
      TriageHandoff.fromPath("income-tax").kind shouldBe HandoffKind.PathHint
      TriageHandoff.fromPath("mixed").kind shouldBe HandoffKind.WrongPlace
      TriageHandoff.fromPath("mixed").sourceLabel shouldBe "url-hint"
      TriageHandoff.fromPath("untriaged").kind shouldBe HandoffKind.Untriaged
      val unknown = TriageHandoff.fromPath("cgt")
      unknown.kind shouldBe HandoffKind.Untriaged
      unknown.warning should not be empty
      unknown.regimeLabel shouldBe "None"

  "fromQuery" should:
    "treat missing params as untriaged" in:
      TriageHandoff.fromQuery(None, None).kind shouldBe HandoffKind.Untriaged

    "read income-tax, mixed, and anything else" in:
      TriageHandoff.fromQuery(Some("govuk"), Some("income-tax")).kind shouldBe HandoffKind.QueryHint
      TriageHandoff.fromQuery(Some("govuk"), Some("mixed")).kind shouldBe HandoffKind.WrongPlace
      val other = TriageHandoff.fromQuery(Some("govuk"), Some("cgt"))
      other.kind shouldBe HandoffKind.Untriaged
      other.warning should not be empty
      val emptyRegimes = TriageHandoff.fromQuery(Some("govuk"), None)
      emptyRegimes.kind shouldBe HandoffKind.Untriaged

  "fromSession" should:
    "trust income-tax, reject mixed, and fall back otherwise" in:
      val ok = TriageHandoff.fromSession(Some("income-tax"))
      ok.kind shouldBe HandoffKind.Session
      ok.sourceLabel shouldBe "session"
      ok.regimeLabel shouldBe "Income Tax only"
      TriageHandoff.fromSession(Some("mixed")).kind shouldBe HandoffKind.WrongPlace
      val other = TriageHandoff.fromSession(Some("skip"))
      other.kind shouldBe HandoffKind.Untriaged
      other.warning should not be empty
      val missing = TriageHandoff.fromSession(None)
      missing.kind shouldBe HandoffKind.Untriaged
      missing.warning should not be empty

  "fromToken" should:
    "accept a valid signature for income-tax" in:
      val token = TriageHandoff.sign("income-tax", secret)
      val handoff = TriageHandoff.fromToken(Some("income-tax"), Some(token), secret)
      handoff.kind shouldBe HandoffKind.TokenValid
      handoff.sourceLabel shouldBe "signed-token"

    "send a valid mixed token to the other service" in:
      val token = TriageHandoff.sign("mixed", secret)
      TriageHandoff.fromToken(Some("mixed"), Some(token), secret).kind shouldBe HandoffKind.WrongPlace

    "fall back when the signed regime is unknown" in:
      val token = TriageHandoff.sign("untriaged", secret)
      val handoff = TriageHandoff.fromToken(Some("untriaged"), Some(token), secret)
      handoff.kind shouldBe HandoffKind.Untriaged
      handoff.regimeLabel shouldBe "None"

    "reject a tampered token" in:
      val handoff = TriageHandoff.fromToken(Some("income-tax"), Some("deadbeef"), secret)
      handoff.kind shouldBe HandoffKind.TokenInvalid
      handoff.skipRouting shouldBe false
      handoff.sourceLabel shouldBe "answered-in-dds"

    "fall back when fields are missing" in:
      TriageHandoff.fromToken(None, None, secret).kind shouldBe HandoffKind.Untriaged
      TriageHandoff.fromToken(Some("income-tax"), None, secret).kind shouldBe HandoffKind.Untriaged

  "sign / verify" should:
    "round-trip and fail a different payload" in:
      val token = TriageHandoff.sign("income-tax", secret)
      TriageHandoff.verify("income-tax", token, secret) shouldBe true
      TriageHandoff.verify("mixed", token, secret) shouldBe false
      TriageHandoff.verify("income-tax", "short", secret) shouldBe false

  "regimeLabel" should:
    "cover every stored value" in:
      DdsHandoff(
        HandoffKind.PathHint, "t", "m", "r", Some("mixed"), false, false, "n", None
      ).regimeLabel shouldBe "Mixed or other regimes"
      DdsHandoff(
        HandoffKind.Untriaged, "t", "m", "r", Some("untriaged"), false, false, "n", None
      ).regimeLabel shouldBe "Not classified"
      DdsHandoff(
        HandoffKind.Untriaged, "t", "m", "r", Some("cgt"), false, false, "n", None
      ).regimeLabel shouldBe "cgt"

  "demoOptions" should:
    "list six walkable options" in:
      TriageHandoff.demoOptions.map(_.id) shouldBe Seq(
        "two-urls", "query", "path", "confirm", "token", "preauth"
      )

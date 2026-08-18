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

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

enum HandoffKind:
  case Untriaged, ReadyHint, ConfirmHint, PathHint, QueryHint, Session, TokenValid, TokenInvalid, WrongPlace

enum StartNowTarget:
  case Ready
  case Confirm
  case Default
  case WrongPlace
  case Path(exit: String)
  case Query(regimes: String)
  case Token(regime: String)

final case class DemoOption(
  id         : String,
  title      : String,
  summary    : String,
  govukOwned : Boolean
)

final case class DdsHandoff(
  kind         : HandoffKind,
  optionTitle  : String,
  mechanism    : String,
  received     : String,
  regime       : Option[String],
  skipRouting  : Boolean,
  showConfirm  : Boolean,
  trustNote    : String,
  warning      : Option[String]
):
  def regimeLabel: String =
    regime match
      case Some("income-tax") => "Income Tax only"
      case Some("mixed")      => "Mixed or other regimes"
      case Some("untriaged")  => "Not classified"
      case Some(other)        => other
      case None               => "None"

  def sourceLabel: String =
    kind match
      case HandoffKind.Session     => "session"
      case HandoffKind.TokenValid  => "signed-token"
      case HandoffKind.ConfirmHint => "confirmation"
      case HandoffKind.Untriaged   => "answered-in-dds"
      case HandoffKind.TokenInvalid => "answered-in-dds"
      case _                       => "url-hint"

object TriageHandoff:

  val SessionRegimeKey : String = "triagePoc.regime"
  val DraftRegimeKey   : String = "triagePoc.draft.regime"
  val DraftSourceKey   : String = "triagePoc.draft.source"
  val DraftSkippedKey  : String = "triagePoc.draft.skippedRouting"

  val sessionKeys: Seq[String] =
    Seq(SessionRegimeKey, DraftRegimeKey, DraftSourceKey, DraftSkippedKey)

  val govukExits: Set[String] = Set("income-tax", "mixed", "skip")

  val demoOptions: Seq[DemoOption] = Seq(
    DemoOption(
      "two-urls",
      "Two entry URLs",
      "GOV.UK emits /start/ready if triage succeeded, or /start if it did not.",
      govukOwned = true
    ),
    DemoOption(
      "query",
      "Query parameters",
      "GOV.UK appends ?origin=govuk&regimes=income-tax to a single Start now URL.",
      govukOwned = true
    ),
    DemoOption(
      "path",
      "Path-encoded outcomes",
      "GOV.UK emits /start/path/income-tax, /start/path/mixed, or /start/path/untriaged.",
      govukOwned = true
    ),
    DemoOption(
      "confirm",
      "Re-ask or confirm",
      "Same ready URL as two-entry, but DDS shows a confirmation instead of skipping.",
      govukOwned = true
    ),
    DemoOption(
      "token",
      "Signed token in the URL",
      "GOV.UK would mint an HMAC. Whitehall cannot; this option shows why it is a dead end.",
      govukOwned = true
    ),
    DemoOption(
      "preauth",
      "Pre-auth MDTP session",
      "GOV.UK has one Start now. Questions run on this service before login, stored in Play session.",
      govukOwned = false
    )
  )

  def isKnownGovukOption(option: String): Boolean =
    demoOptions.exists(o => o.id == option && o.govukOwned)

  def optionTitle(id: String): String =
    demoOptions.find(_.id == id).map(_.title).getOrElse(id)

  def startNowTarget(option: String, exit: String): StartNowTarget =
    (option, exit) match
      case ("two-urls", "income-tax") => StartNowTarget.Ready
      case ("two-urls", "skip")       => StartNowTarget.Default
      case ("two-urls", _)            => StartNowTarget.WrongPlace
      case ("confirm", "income-tax")  => StartNowTarget.Confirm
      case ("confirm", "skip")        => StartNowTarget.Default
      case ("confirm", _)             => StartNowTarget.WrongPlace
      case ("query", "income-tax")    => StartNowTarget.Query("income-tax")
      case ("query", "mixed")         => StartNowTarget.Query("mixed")
      case ("query", _)               => StartNowTarget.Query("unknown")
      case ("path", "income-tax")     => StartNowTarget.Path("income-tax")
      case ("path", "mixed")          => StartNowTarget.Path("mixed")
      case ("path", _)                => StartNowTarget.Path("untriaged")
      case ("token", "income-tax")    => StartNowTarget.Token("income-tax")
      case ("token", "mixed")         => StartNowTarget.Token("mixed")
      case ("token", _)               => StartNowTarget.Token("untriaged")
      case _                          => StartNowTarget.Default

  def untriaged: DdsHandoff =
    DdsHandoff(
      kind        = HandoffKind.Untriaged,
      optionTitle = "Default entry",
      mechanism   = "No GOV.UK signal — PTA tile, bookmark, skipped triage, or typed URL",
      received    = "Nothing",
      regime      = None,
      skipRouting = false,
      showConfirm = false,
      trustNote   = "Safe default. Ask the classifier questions in DDS.",
      warning     = None
    )

  def fromReady: DdsHandoff =
    DdsHandoff(
      kind        = HandoffKind.ReadyHint,
      optionTitle = optionTitle("two-urls"),
      mechanism   = "Distinct URL /start/ready",
      received    = "Path hint: ready",
      regime      = Some("income-tax"),
      skipRouting = true,
      showConfirm = false,
      trustNote   = "Hint only. Anyone can type this URL.",
      warning     = Some("Bookmarking or sharing /start/ready skips GOV.UK. Do not treat the path as evidence.")
    )

  def fromConfirm: DdsHandoff =
    DdsHandoff(
      kind        = HandoffKind.ConfirmHint,
      optionTitle = optionTitle("confirm"),
      mechanism   = "Distinct URL /start/confirm",
      received    = "Path hint: confirm",
      regime      = Some("income-tax"),
      skipRouting = true,
      showConfirm = true,
      trustNote   = "Hint only. DDS records the answer from the confirmation, not from the URL.",
      warning     = Some("If the user says this is not right, drop them onto the default classifier questions.")
    )

  def fromPath(exit: String): DdsHandoff =
    exit match
      case "income-tax" =>
        DdsHandoff(
          kind        = HandoffKind.PathHint,
          optionTitle = optionTitle("path"),
          mechanism   = "Path segment /start/path/income-tax",
          received    = "Path exit: income-tax",
          regime      = Some("income-tax"),
          skipRouting = true,
          showConfirm = false,
          trustNote   = "Hint only. Same tamper risk as two URLs, with more exits.",
          warning     = Some("Stop encoding eligibility detail into the path. At that point triage belongs on MDTP.")
        )
      case "mixed" =>
        wrongPlace("path", "Path segment /start/path/mixed", "Path exit: mixed")
      case "untriaged" =>
        untriaged.copy(
          optionTitle = optionTitle("path"),
          mechanism   = "Path segment /start/path/untriaged",
          received    = "Path exit: untriaged"
        )
      case other =>
        untriaged.copy(
          optionTitle = optionTitle("path"),
          mechanism   = s"Path segment /start/path/$other",
          received    = s"Unknown path exit: $other",
          warning     = Some("Unknown exit. Falling back to full DDS classifier questions.")
        )

  def fromQuery(origin: Option[String], regimes: Option[String]): DdsHandoff =
    if origin.isEmpty && regimes.isEmpty then untriaged
    else
      regimes.getOrElse("") match
        case "income-tax" =>
          DdsHandoff(
            kind        = HandoffKind.QueryHint,
            optionTitle = optionTitle("query"),
            mechanism   = "Query string on /start",
            received    = s"origin=${origin.getOrElse("-")} regimes=income-tax",
            regime      = Some("income-tax"),
            skipRouting = true,
            showConfirm = false,
            trustNote   = "Hint only. Query params are editable, logged, and shareable.",
            warning     = Some("Do not put eligibility answers in the query string. Fine as an origin flag; poor as a form.")
          )
        case "mixed" =>
          wrongPlace(
            "query",
            "Query string on /start",
            s"origin=${origin.getOrElse("-")} regimes=mixed"
          )
        case other =>
          untriaged.copy(
            optionTitle = optionTitle("query"),
            mechanism   = "Query string on /start",
            received    = s"origin=${origin.getOrElse("-")} regimes=${if other.isEmpty then "-" else other}",
            warning     = Some("Unrecognised regimes param. Falling back to full DDS classifier questions.")
          )

  def fromSession(regime: Option[String]): DdsHandoff =
    regime match
      case Some("income-tax") =>
        DdsHandoff(
          kind        = HandoffKind.Session,
          optionTitle = optionTitle("preauth"),
          mechanism   = "Play session written by an unauthenticated MDTP page",
          received    = "session triagePoc.regime=income-tax",
          regime      = Some("income-tax"),
          skipRouting = true,
          showConfirm = false,
          trustNote   = "Trusted for routing. State never left tax.service.gov.uk.",
          warning     = None
        )
      case Some("mixed") =>
        wrongPlace("preauth", "Play session", "session triagePoc.regime=mixed")
      case Some(other) =>
        untriaged.copy(
          optionTitle = optionTitle("preauth"),
          mechanism   = "Play session",
          received    = s"session triagePoc.regime=$other",
          warning     = Some("Unexpected session value. Falling back to full DDS classifier questions.")
        )
      case None =>
        untriaged.copy(
          optionTitle = optionTitle("preauth"),
          mechanism   = "Play session",
          received    = "No session key",
          warning     = Some("Session empty — different browser, expired cookie, or the user skipped pre-auth.")
        )

  def fromToken(regime: Option[String], token: Option[String], secret: String): DdsHandoff =
    (regime, token) match
      case (Some(r), Some(t)) if verify(r, t, secret) =>
        r match
          case "income-tax" =>
            DdsHandoff(
              kind        = HandoffKind.TokenValid,
              optionTitle = optionTitle("token"),
              mechanism   = "HMAC-SHA256 token on the query string",
              received    = s"regime=$r token=valid",
              regime      = Some("income-tax"),
              skipRouting = true,
              showConfirm = false,
              trustNote   = "Harder to forge, but GOV.UK Whitehall cannot mint this token.",
              warning     = Some("If triage already runs on MDTP you have a session and do not need a token.")
            )
          case "mixed" =>
            wrongPlace("token", "HMAC-SHA256 token on the query string", s"regime=$r token=valid")
          case other =>
            untriaged.copy(
              optionTitle = optionTitle("token"),
              mechanism   = "HMAC-SHA256 token on the query string",
              received    = s"regime=$other token=valid",
              warning     = Some("Signed but unknown regime. Falling back to full DDS classifier questions.")
            )
      case (Some(r), Some(_)) =>
        DdsHandoff(
          kind        = HandoffKind.TokenInvalid,
          optionTitle = optionTitle("token"),
          mechanism   = "HMAC-SHA256 token on the query string",
          received    = s"regime=$r token=invalid",
          regime      = None,
          skipRouting = false,
          showConfirm = false,
          trustNote   = "Signature failed. Ignore the URL and ask the classifier questions.",
          warning     = Some("Tampered or copy-pasted token. This is what happens if GOV.UK cannot sign, or a user edits the URL.")
        )
      case _ =>
        untriaged.copy(
          optionTitle = optionTitle("token"),
          mechanism   = "HMAC-SHA256 token on the query string",
          received    = "Missing regime or token",
          warning     = Some("Incomplete token URL. Falling back to full DDS classifier questions.")
        )

  def sign(regime: String, secret: String): String =
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
    toHex(mac.doFinal(regime.getBytes(StandardCharsets.UTF_8)))

  def verify(regime: String, token: String, secret: String): Boolean =
    val expected = sign(regime, secret).getBytes(StandardCharsets.UTF_8)
    val actual   = token.getBytes(StandardCharsets.UTF_8)
    expected.length == actual.length && MessageDigest.isEqual(expected, actual)

  private def wrongPlace(optionId: String, mechanism: String, received: String): DdsHandoff =
    DdsHandoff(
      kind        = HandoffKind.WrongPlace,
      optionTitle = optionTitle(optionId),
      mechanism   = mechanism,
      received    = received,
      regime      = Some("mixed"),
      skipRouting = false,
      showConfirm = false,
      trustNote   = "Classifier sent this person to the other service (legacy stand-in).",
      warning     = Some("During dual running, mixed-regime users are not in-scope for the new service.")
    )

  private def toHex(bytes: Array[Byte]): String =
    bytes.map(b => f"$b%02x").mkString

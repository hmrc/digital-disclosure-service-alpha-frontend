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

import play.api.data.Form
import play.api.data.Forms.*
import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents, Request, Result}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.models.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.*
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}

final case class RegimeAnswer(regime: String)
final case class ConfirmAnswer(stillRight: String)

@Singleton
class TriagePocController @Inject()(
  mcc            : MessagesControllerComponents,
  appConfig      : AppConfig,
  demoPage       : TriagePocDemoPage,
  govukPage      : TriagePocGovukPage,
  govukDonePage  : TriagePocGovukDonePage,
  preauthPage    : TriagePocPreauthPage,
  ddsPage        : TriagePocDdsPage,
  wrongPlacePage : TriagePocWrongPlacePage,
  resultPage     : TriagePocResultPage
) extends FrontendController(mcc) with I18nSupport:

  private val govukForm: Form[RegimeAnswer] = Form(
    mapping(
      "regime" -> nonEmptyText.verifying(
        "triagePoc.error.regime",
        v => v == "income-tax" || v == "mixed" || v == "skip"
      )
    )(RegimeAnswer.apply)(a => Some(a.regime))
  )

  private val routingForm: Form[RegimeAnswer] = Form(
    mapping(
      "regime" -> nonEmptyText.verifying(
        "triagePoc.error.routing",
        v => v == "income-tax" || v == "mixed"
      )
    )(RegimeAnswer.apply)(a => Some(a.regime))
  )

  private val confirmForm: Form[ConfirmAnswer] = Form(
    mapping(
      "stillRight" -> nonEmptyText.verifying(
        "triagePoc.error.confirm",
        v => v == "yes" || v == "no"
      )
    )(ConfirmAnswer.apply)(a => Some(a.stillRight))
  )

  val demo: Action[AnyContent] =
    Action:
      implicit request =>
        Ok(demoPage()).removingFromSession(TriageHandoff.sessionKeys*)

  def govuk(option: String): Action[AnyContent] =
    Action:
      implicit request =>
        if TriageHandoff.isKnownGovukOption(option) then
          Ok(govukPage(option, TriageHandoff.optionTitle(option), govukForm))
        else
          Redirect(routes.TriagePocController.demo)

  def govukSubmit(option: String): Action[AnyContent] =
    Action:
      implicit request =>
        if !TriageHandoff.isKnownGovukOption(option) then
          Redirect(routes.TriagePocController.demo)
        else
          govukForm.bindFromRequest().fold(
            formWithErrors => BadRequest(govukPage(option, TriageHandoff.optionTitle(option), formWithErrors)),
            answer => Redirect(routes.TriagePocController.govukDone(option, answer.regime))
          )

  def govukDone(option: String, exit: String): Action[AnyContent] =
    Action:
      implicit request =>
        if !TriageHandoff.isKnownGovukOption(option) || !TriageHandoff.govukExits.contains(exit) then
          Redirect(routes.TriagePocController.demo)
        else
          Ok(govukDonePage(option, TriageHandoff.optionTitle(option), exit, startNowHref(option, exit)))

  val preauth: Action[AnyContent] =
    Action:
      implicit request =>
        Ok(preauthPage(govukForm))

  val preauthSubmit: Action[AnyContent] =
    Action:
      implicit request =>
        govukForm.bindFromRequest().fold(
          formWithErrors => BadRequest(preauthPage(formWithErrors)),
          answer =>
            Redirect(routes.TriagePocController.startSession)
              .addingToSession(TriageHandoff.SessionRegimeKey -> answer.regime)
        )

  val startDefault: Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(TriageHandoff.fromQuery(request.getQueryString("origin"), request.getQueryString("regimes")))

  val startReady: Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(TriageHandoff.fromReady)

  val startConfirm: Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(TriageHandoff.fromConfirm)

  def startPath(exit: String): Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(TriageHandoff.fromPath(exit))

  val startSession: Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(TriageHandoff.fromSession(request.session.get(TriageHandoff.SessionRegimeKey)))

  val startToken: Action[AnyContent] =
    Action:
      implicit request =>
        renderDds(
          TriageHandoff.fromToken(
            request.getQueryString("regime"),
            request.getQueryString("token"),
            appConfig.triagePocTokenSecret
          )
        )

  val wrongPlace: Action[AnyContent] =
    Action:
      implicit request =>
        Ok(wrongPlacePage(TriageHandoff.fromPath("mixed")))

  val submitRouting: Action[AnyContent] =
    Action:
      implicit request =>
        routingForm.bindFromRequest().fold(
          formWithErrors => BadRequest(ddsPage(TriageHandoff.untriaged, formWithErrors, confirmForm, None)),
          answer =>
            answer.regime match
              case "mixed" => Redirect(routes.TriagePocController.wrongPlace)
              case _       => recordDraft(answer.regime, "answered-in-dds", skipped = false)
        )

  val submitConfirm: Action[AnyContent] =
    Action:
      implicit request =>
        confirmForm.bindFromRequest().fold(
          formWithErrors => BadRequest(ddsPage(TriageHandoff.fromConfirm, routingForm, formWithErrors, None)),
          answer =>
            if answer.stillRight == "yes" then
              recordDraft("income-tax", "confirmation", skipped = true)
            else
              Redirect(routes.TriagePocController.startDefault)
        )

  val acceptHint: Action[AnyContent] =
    Action:
      implicit request =>
        routingForm.bindFromRequest().fold(
          _ => Redirect(routes.TriagePocController.startDefault),
          answer =>
            if answer.regime == "income-tax" then
              val source = request.body.asFormUrlEncoded
                .flatMap(_.get("source").flatMap(_.headOption))
                .getOrElse("url-hint")
              recordDraft("income-tax", source, skipped = true)
            else
              Redirect(routes.TriagePocController.startDefault)
        )

  val result: Action[AnyContent] =
    Action:
      implicit request =>
        request.session.get(TriageHandoff.DraftRegimeKey) match
          case Some(regime) =>
            val source  = request.session.get(TriageHandoff.DraftSourceKey).getOrElse("url-hint")
            val skipped = request.session.get(TriageHandoff.DraftSkippedKey).contains("true")
            Ok(resultPage(regime, source, skipped))
          case None =>
            Redirect(routes.TriagePocController.demo)

  private def renderDds(handoff: DdsHandoff)(using Request[_]): Result =
    if handoff.kind == HandoffKind.WrongPlace then
      Ok(wrongPlacePage(handoff))
    else
      Ok(ddsPage(handoff, routingForm, confirmForm, tamperHref(handoff)))

  private def recordDraft(regime: String, source: String, skipped: Boolean)(using Request[_]): Result =
    Redirect(routes.TriagePocController.result)
      .addingToSession(
        TriageHandoff.DraftRegimeKey  -> regime,
        TriageHandoff.DraftSourceKey  -> source,
        TriageHandoff.DraftSkippedKey -> skipped.toString
      )

  private def startNowHref(option: String, exit: String): String =
    TriageHandoff.startNowTarget(option, exit) match
      case StartNowTarget.Ready =>
        routes.TriagePocController.startReady.url
      case StartNowTarget.Confirm =>
        routes.TriagePocController.startConfirm.url
      case StartNowTarget.Default =>
        routes.TriagePocController.startDefault.url
      case StartNowTarget.WrongPlace =>
        routes.TriagePocController.wrongPlace.url
      case StartNowTarget.Path(pathExit) =>
        routes.TriagePocController.startPath(pathExit).url
      case StartNowTarget.Query(regimes) =>
        routes.TriagePocController.startDefault.url + s"?origin=govuk&regimes=$regimes"
      case StartNowTarget.Token(regime) =>
        val token = TriageHandoff.sign(regime, appConfig.triagePocTokenSecret)
        routes.TriagePocController.startToken.url + s"?regime=$regime&token=$token"

  private def tamperHref(handoff: DdsHandoff): Option[String] =
    handoff.kind match
      case HandoffKind.QueryHint =>
        Some(routes.TriagePocController.startDefault.url + "?origin=govuk&regimes=mixed")
      case HandoffKind.TokenValid =>
        Some(routes.TriagePocController.startToken.url + "?regime=income-tax&token=deadbeef")
      case HandoffKind.ReadyHint =>
        Some(routes.TriagePocController.startReady.url)
      case _ =>
        None

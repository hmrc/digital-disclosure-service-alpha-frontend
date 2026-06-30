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

import play.api.i18n.I18nSupport
import play.api.mvc.{Action, AnyContent, MessagesControllerComponents}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.config.AppConfig
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.controllers.actions.IndividualPocEnabledAction
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.views.html.IndividualPocHubPage
import uk.gov.hmrc.play.bootstrap.frontend.controller.FrontendController

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class IndividualPocHubController @Inject()(
  mcc              : MessagesControllerComponents,
  defaultAction    : play.api.mvc.DefaultActionBuilder,
  individualPocEnabled: IndividualPocEnabledAction,
  appConfig        : AppConfig,
  hubPage          : IndividualPocHubPage
)(using ec: ExecutionContext)
  extends FrontendController(mcc) with I18nSupport:

  private val hubAction = defaultAction andThen individualPocEnabled

  val hub: Action[AnyContent] = hubAction:
    implicit request =>
      Ok(hubPage(appConfig))

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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.session

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.SessionState

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Singleton

/** In-memory Alpha storage: sessions disappear when the app restarts and are not shared between instances. */
@Singleton
class SessionStore:

  private val store = new ConcurrentHashMap[String, SessionState]()

  def get(id: String): Option[SessionState] =
    Option(store.get(id))

  def save(state: SessionState): SessionState =
    store.put(state.id, state)
    state

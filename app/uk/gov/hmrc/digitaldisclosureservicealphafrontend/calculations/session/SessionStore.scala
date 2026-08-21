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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config.{
  ConfigValidator,
  ConfigViolation,
  DefaultConfigs
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ArchitectureOption, SessionState}

import play.api.libs.json.Json

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters.*

@Singleton
class SessionStore @Inject()():

  private val store = new ConcurrentHashMap[String, SessionState]()

  def create(option: ArchitectureOption): SessionState =
    val defaults = DefaultConfigs.defaultsFor(option)
    val state = SessionState(
      id = UUID.randomUUID().toString,
      option = option,
      rateJson = defaults.rateJson,
      questionJson = defaults.questionJson,
      calculationJson = defaults.calculationJson,
      rateCatalog = defaults.catalog,
      questionPack = defaults.questions,
      calculationSpec = defaults.calculation
    )
    store.put(state.id, state)
    state

  def get(id: String): Option[SessionState] =
    Option(store.get(id))

  def save(state: SessionState): Unit =
    store.put(state.id, state)

  def updateConfig(
    id             : String,
    rateJson       : String,
    questionJson   : String,
    calculationJson: String
  ): Either[Seq[ConfigViolation], SessionState] =
    get(id) match
      case None =>
        Left(Seq(ConfigViolation("", "Calculations session not found. Start again from the prototype home.")))
      case Some(existing) =>
        val effectiveQuestionJson =
          if existing.option.questionsEditable then questionJson
          else existing.questionJson

        ConfigValidator
          .validate(rateJson, effectiveQuestionJson, calculationJson)
          .map: validated =>
            val updated = existing.copy(
              rateJson = Json.prettyPrint(Json.toJson(validated.catalog)),
              questionJson = Json.prettyPrint(Json.toJson(validated.questions)),
              calculationJson = Json.prettyPrint(Json.toJson(validated.calculation)),
              rateCatalog = validated.catalog,
              questionPack = validated.questions,
              calculationSpec = validated.calculation,
              answers = Map.empty
            )
            store.put(id, updated)
            updated

  def putAnswer(id: String, questionId: String, value: String): Option[SessionState] =
    get(id).map: state =>
      val updated = state.copy(answers = state.answers + (questionId -> value))
      store.put(id, updated)
      updated

  def clearAnswers(id: String): Option[SessionState] =
    get(id).map: state =>
      val updated = state.copy(answers = Map.empty)
      store.put(id, updated)
      updated

  def allIds: Seq[String] = store.keys().asScala.toSeq

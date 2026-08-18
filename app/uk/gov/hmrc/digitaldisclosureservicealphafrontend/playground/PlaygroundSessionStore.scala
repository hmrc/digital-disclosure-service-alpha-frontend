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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.playground

import play.api.libs.json.Json

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.{Inject, Singleton}
import scala.jdk.CollectionConverters.*

@Singleton
class PlaygroundSessionStore @Inject()():

  private val store = new ConcurrentHashMap[String, PlaygroundState]()

  def create(option: PlaygroundOption): PlaygroundState =
    val (rateJson, questionJson, rates, questions) = DefaultConfigs.defaultsFor(option)
    val state = PlaygroundState(
      id = UUID.randomUUID().toString,
      option = option,
      rateJson = rateJson,
      questionJson = questionJson,
      ratePack = rates,
      questionPack = questions
    )
    store.put(state.id, state)
    state

  def get(id: String): Option[PlaygroundState] =
    Option(store.get(id))

  def save(state: PlaygroundState): Unit =
    store.put(state.id, state)

  def updateConfig(
    id          : String,
    rateJson    : String,
    questionJson: String
  ): Either[String, PlaygroundState] =
    get(id) match
      case None => Left("Playground session not found. Start again from the demo home.")
      case Some(existing) =>
        for
          rates     <- parseRates(rateJson)
          questions <-
            if existing.option == PlaygroundOption.RatesOnly then
              Right(DefaultConfigs.ratesOnlyQuestionPack)
            else parseQuestions(questionJson)
        yield
          val updated = existing.copy(
            rateJson = Json.prettyPrint(Json.toJson(rates)),
            questionJson =
              if existing.option == PlaygroundOption.RatesOnly then
                DefaultConfigs.prettyQuestionJson(DefaultConfigs.ratesOnlyQuestionPack)
              else Json.prettyPrint(Json.toJson(questions)),
            ratePack = rates,
            questionPack = questions,
            answers = Map.empty
          )
          store.put(id, updated)
          updated

  def putAnswer(id: String, questionId: String, value: String): Option[PlaygroundState] =
    get(id).map: state =>
      val updated = state.copy(answers = state.answers + (questionId -> value))
      store.put(id, updated)
      updated

  def clearAnswers(id: String): Option[PlaygroundState] =
    get(id).map: state =>
      val updated = state.copy(answers = Map.empty)
      store.put(id, updated)
      updated

  def allIds: Seq[String] = store.keys().asScala.toSeq

  private def parseRates(raw: String): Either[String, RatePack] =
    Json.parse(raw).validate[RatePack].asEither.left.map(errs => prettyErrors(errs))

  private def parseQuestions(raw: String): Either[String, QuestionPack] =
    Json.parse(raw).validate[QuestionPack].asEither.left.map(errs => prettyErrors(errs))

  private def prettyErrors(errs: scala.collection.Seq[(play.api.libs.json.JsPath, scala.collection.Seq[play.api.libs.json.JsonValidationError])]): String =
    errs
      .map { case (path, errors) =>
        s"${path.toJsonString}: ${errors.map(_.message).mkString(", ")}"
      }
      .mkString("; ")

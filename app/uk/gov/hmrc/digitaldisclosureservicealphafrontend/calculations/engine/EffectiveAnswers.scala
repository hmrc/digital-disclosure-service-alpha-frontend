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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.engine

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{QuestionPack, RateCatalog}

import scala.annotation.tailrec

/**
  * Works out which stored answers count. The session stores the latest answer to every question the user has
  * answered, and nothing else. An answer counts only while the config puts its question on the user's path: a closed
  * branch takes its follow-ups with it, however deep, and an answer whose question comes back counts again as it was.
  */
object EffectiveAnswers:

  def of(pack: QuestionPack, catalog: RateCatalog, stored: Map[String, String]): Map[String, String] =
    /** Drops answers to questions off the path until nothing changes, so a hidden parent hides its follow-ups too. */
    @tailrec def settle(answers: Map[String, String]): Map[String, String] =
      val onPath = QuestionEngine.visibleQuestions(pack, catalog, answers).map(_.id).toSet
      val kept = answers.filter((id, _) => onPath(id))
      if kept.size == answers.size then answers else settle(kept)
    settle(stored)

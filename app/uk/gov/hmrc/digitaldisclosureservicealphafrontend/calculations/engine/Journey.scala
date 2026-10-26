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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  LogEntry,
  OnReturn,
  QuestionPack,
  RateCatalog,
  YearAnswers
}

import scala.annotation.tailrec

/** What a log entry did when the log was replayed. */
enum EntryOutcome:
  /**
    * The entry counted.
    *
    * @param lapsed   answers it took off the user's path
    * @param restored earlier answers it brought back: confirmed by the user, or restored by an `onReturn` setting
    */
  case Counted(lapsed: Seq[String], restored: Seq[String])

  /** Its question was not on the user's path at the time, for example a page left open in another tab. */
  case Ignored

final case class ReplayedEntry(entry: LogEntry, outcome: EntryOutcome)

/**
  * Everything a page needs about the answers, worked out from the log. Never stored.
  *
  * @param effective the answers that count
  * @param lapsed    the last answer given to each question that has since left the user's path, by question id.
  *                  It pre-fills the question if it comes back.
  * @param returning lapsed answers whose question is back on the path, with the setting that says what happens next
  * @param replay    each log entry with what it did, for the answer log page
  */
final case class JourneyState(
  effective: Map[String, String],
  lapsed   : Map[String, String],
  returning: Map[String, OnReturn],
  replay   : Vector[ReplayedEntry]
)

/**
  * Replays a session's answer log. One rule decides which answers count: an answer counts from when it is given
  * until its question leaves the user's path. After that it has lapsed. It stays in the log but never counts again
  * unless the user answers or confirms it, or the question's `onReturn` setting is `restore`.
  */
object Journey:

  def project(pack: QuestionPack, catalog: RateCatalog, log: Seq[LogEntry]): JourneyState =
    val settings = pack.questions.map(q => q.id -> pack.taskList.onReturnFor(q, pack)).toMap
    def setting(id: String): OnReturn = settings.getOrElse(YearAnswers.parse(id)._1, OnReturn.askAgain)
    def visible(answers: Map[String, String]): Set[String] =
      QuestionEngine.visibleQuestions(pack, catalog, answers).map(_.id).toSet

    /** Drops answers to hidden questions until nothing changes, so a closed branch takes its follow-ups with it. */
    @tailrec def settle(answers: Map[String, String]): Map[String, String] =
      val on = visible(answers)
      val kept = answers.filter((id, _) => on(id))
      if kept.size == answers.size then answers else settle(kept)

    /** Settles the new answers, then brings back `restore` answers that are on the path again, until stable. */
    def apply(state: JourneyState, next: Map[String, String], entry: LogEntry, confirmed: Seq[String]): JourneyState =
      @tailrec def loop(answers: Map[String, String], lapsed: Map[String, String], restored: Seq[String]): JourneyState =
        val kept = settle(answers)
        val nowLapsed = (lapsed ++ (answers -- kept.keySet)) -- kept.keySet
        val on = visible(kept)
        val back = nowLapsed.filter((id, _) => on(id) && setting(id) == OnReturn.restore && !restored.contains(id))
        if back.nonEmpty then loop(kept ++ back, nowLapsed -- back.keySet, restored ++ back.keys.toSeq.sorted)
        else
          val outcome = EntryOutcome.Counted((state.effective.keySet -- kept.keySet).toSeq.sorted, confirmed ++ restored)
          state.copy(effective = kept, lapsed = nowLapsed, replay = state.replay :+ ReplayedEntry(entry, outcome))
      loop(next, state.lapsed, Nil)

    def ignore(state: JourneyState, entry: LogEntry): JourneyState =
      state.copy(replay = state.replay :+ ReplayedEntry(entry, EntryOutcome.Ignored))

    val replayed = log.foldLeft(JourneyState(Map.empty, Map.empty, Map.empty, Vector.empty)): (state, entry) =>
      entry match
        case e: LogEntry.AnswerGiven =>
          if visible(state.effective).contains(e.questionId) then apply(state, state.effective + (e.questionId -> e.value), e, Nil)
          else ignore(state, e)
        case e: LogEntry.AnswersConfirmed =>
          val on = visible(state.effective)
          val back = state.lapsed.filter((id, _) => e.questionIds.contains(id) && on(id))
          if back.isEmpty then ignore(state, e) else apply(state, state.effective ++ back, e, back.keys.toSeq.sorted)

    val on = visible(replayed.effective)
    replayed.copy(returning = replayed.lapsed.keySet.filter(on).map(id => id -> setting(id)).toMap)

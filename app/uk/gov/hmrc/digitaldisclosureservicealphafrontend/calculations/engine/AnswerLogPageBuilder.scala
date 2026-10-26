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
  Answers,
  LogEntry,
  OnReturn,
  QuestionType,
  SessionState,
  TaxYear,
  Via,
  YearAnswers
}

import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One log entry as the answer log page shows it. `counted` is false for an entry that was ignored. */
final case class AnswerLogRow(
  number  : Int,
  time    : String,
  question: String,
  answer  : String,
  via     : Via,
  counted : Boolean,
  lapsed  : Seq[String],
  restored: Seq[String]
)

/** An answer that has lapsed, and whether its question is back on the path. */
final case class LapsedAnswerRow(question: String, answer: String, returning: Option[OnReturn])

final case class AnswerLogPageModel(rows: Seq[AnswerLogRow], lapsed: Seq[LapsedAnswerRow]):
  def counted: Int = rows.count(_.counted)
  def ignored: Int = rows.count(!_.counted)

/** Sets out a session's answer log in plain words: which question, what answer, and what each entry did. */
object AnswerLogPageBuilder:

  private val time = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.of("Europe/London"))

  def build(state: SessionState, translate: String => String): AnswerLogPageModel =
    val byId = state.questionPack.questions.map(q => q.id -> q).toMap

    def title(id: String): String =
      val (base, year) = YearAnswers.parse(id)
      val name = byId.get(base).fold(id)(q => translate(q.title))
      year.fold(name)(y => s"$name ($y)")

    def answer(id: String, raw: String): String =
      byId.get(YearAnswers.parse(id)._1) match
        case None => raw
        case Some(q) =>
          q.questionType match
            case QuestionType.yesNo    => if raw == "yes" then translate("calculations.yes") else if raw == "no" then translate("calculations.no") else raw
            case QuestionType.currency => s"£$raw"
            case QuestionType.checkboxes | QuestionType.singleChoice =>
              val labels = q.options.getOrElse(Nil).map(o => o.value -> translate(o.label)).toMap
              Answers.splitMulti(raw).map(v => labels.getOrElse(v, if q.optionsFromRates then TaxYear.display(v) else v)).mkString(", ")
            case QuestionType.text => raw

    val rows = state.journey.replay.zipWithIndex.map: (replayed, index) =>
      val (question, value) = replayed.entry match
        case e: LogEntry.AnswerGiven      => (title(e.questionId), answer(e.questionId, e.value))
        case e: LogEntry.AnswersConfirmed => (e.questionIds.map(title).mkString(", "), translate("calculations.answerLog.confirmedValue"))
      val (counted, lapsed, restored) = replayed.outcome match
        case EntryOutcome.Counted(lapsed, restored) => (true, lapsed.map(title), restored.map(title))
        case EntryOutcome.Ignored                   => (false, Nil, Nil)
      AnswerLogRow(index + 1, time.format(replayed.entry.at), question, value, replayed.entry.via, counted, lapsed, restored)

    val lapsed = state.journey.lapsed.toSeq.sortBy(_._1).map: (id, raw) =>
      LapsedAnswerRow(title(id), answer(id, raw), state.journey.returning.get(id))

    AnswerLogPageModel(rows, lapsed)

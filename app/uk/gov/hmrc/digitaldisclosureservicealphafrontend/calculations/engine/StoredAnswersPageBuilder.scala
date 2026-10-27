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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{Answers, QuestionType, SessionState, TaxYear, YearAnswers}

/** A stored answer as the stored answers page shows it, and whether it counts. */
final case class StoredAnswerRow(question: String, answer: String, counts: Boolean)

/** Sets out every stored answer in plain words, and which of them the config says count. */
object StoredAnswersPageBuilder:

  def build(state: SessionState, translate: String => String): Seq[StoredAnswerRow] =
    val byId = state.questionPack.questions.map(q => q.id -> q).toMap
    val order = state.questionPack.questions.map(_.id).zipWithIndex.toMap

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

    state.storedAnswers.toSeq
      .sortBy((id, _) => (YearAnswers.parse(id)._2.getOrElse(""), order.getOrElse(YearAnswers.parse(id)._1, Int.MaxValue)))
      .map((id, raw) => StoredAnswerRow(title(id), answer(id, raw), state.answers.contains(id)))

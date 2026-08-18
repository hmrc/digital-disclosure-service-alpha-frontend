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

object QuestionEngine:

  def isVisible(question: ConfigQuestion, answers: Map[String, String]): Boolean =
    question.showIf match
      case None => true
      case Some(rule) =>
        val raw = answers.getOrElse(rule.field, "")
        val values = splitMulti(raw)
        rule.equals.exists(v => values.contains(v)) ||
          rule.contains.exists(v => values.contains(v)) ||
          (rule.equals.isEmpty && rule.contains.isEmpty)

  def visibleQuestions(pack: QuestionPack, answers: Map[String, String]): Seq[ConfigQuestion] =
    pack.questions.filter(q => isVisible(q, answers))

  def nextQuestion(
    pack   : QuestionPack,
    answers: Map[String, String],
    afterId: Option[String] = None
  ): Option[ConfigQuestion] =
    val visible = visibleQuestions(pack, answers)
    afterId match
      case None => visible.headOption
      case Some(id) =>
        val idx = visible.indexWhere(_.id == id)
        if idx < 0 then visible.headOption
        else visible.lift(idx + 1)

  def previousQuestionId(
    pack   : QuestionPack,
    answers: Map[String, String],
    currentId: String
  ): Option[String] =
    val visible = visibleQuestions(pack, answers)
    val idx = visible.indexWhere(_.id == currentId)
    if idx > 0 then Some(visible(idx - 1).id) else None

  def find(pack: QuestionPack, id: String): Option[ConfigQuestion] =
    pack.questions.find(_.id == id)

  def splitMulti(raw: String): Seq[String] =
    raw.split(',').toSeq.map(_.trim).filter(_.nonEmpty)

  def joinMulti(values: Seq[String]): String =
    values.map(_.trim).filter(_.nonEmpty).mkString(",")

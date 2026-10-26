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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model

import java.time.Instant

/** The page a log entry came from. */
enum Via:
  case QuestionPage, CheckAnswers, ConfirmPage

/**
  * One thing the user did. Each session keeps these in a log that is only ever added to: nothing in it is edited
  * or deleted. The answers that count, task status, the next page and the figures are all worked out from the log
  * by `Journey.project`.
  */
enum LogEntry:
  def at : Instant
  def via: Via

  /** An answer posted for a question. */
  case AnswerGiven(questionId: String, value: String, at: Instant, via: Via)

  /** Earlier answers the user confirmed together, for a task whose `onReturn` setting is `confirmAsGroup`. */
  case AnswersConfirmed(questionIds: Seq[String], at: Instant, via: Via)

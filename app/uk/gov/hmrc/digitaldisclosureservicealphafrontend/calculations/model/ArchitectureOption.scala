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

enum ArchitectureOption(
  val id               : String,
  val questionsEditable: Boolean,
  val landsOnTaskList  : Boolean
):
  case RatesOnly extends ArchitectureOption("rates-only", questionsEditable = false, landsOnTaskList = true)
  case RatesAndQuestions
      extends ArchitectureOption("rates-and-questions", questionsEditable = true, landsOnTaskList = false)
  case FullEngine extends ArchitectureOption("full-engine", questionsEditable = true, landsOnTaskList = false)

  def configBackToTaskList: Boolean = landsOnTaskList

object ArchitectureOption:
  def fromId(id: String): Option[ArchitectureOption] =
    values.find(_.id == id)

  val demoOptions: Seq[ArchitectureOption] =
    Seq(RatesOnly, RatesAndQuestions, FullEngine)

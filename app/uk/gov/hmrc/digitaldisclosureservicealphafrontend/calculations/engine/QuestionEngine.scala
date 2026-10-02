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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  ConfigQuestion,
  QuestionOption,
  QuestionPack,
  RateCatalog,
  ResolvedQuestion,
  TaxYear,
  YearAnswers
}

/** Turns question templates into the questions a user sees, given their answers so far. */
object QuestionEngine:

  def selectedTaxYears(answers: Map[String, String]): Seq[String] =
    Answers.values(answers, QuestionPack.TaxYearsQuestionId)

  /**
    * Questions whose `showIf` holds, in pack order: shared questions first, then each
    * `perTaxYear` template once per selected tax year.
    */
  def visibleQuestions(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    translate: String => String = identity
  ): Seq[ResolvedQuestion] =
    val (perYear, shared) = pack.questions.partition(_.perTaxYear)

    val sharedQuestions = shared
      .filter(isVisible(_, answers, taxYear = None))
      .map(template => resolve(template, template.id, None, catalog, translate))

    val yearQuestions = selectedTaxYears(answers).flatMap: year =>
      perYear
        .filter(isVisible(_, answers, Some(year)))
        .map(template => resolve(template, YearAnswers.key(template.id, year), Some(year), catalog, translate))

    sharedQuestions ++ yearQuestions

  def find(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    id       : String,
    translate: String => String = identity
  ): Option[ResolvedQuestion] =
    visibleQuestions(pack, catalog, answers, translate).find(_.id == id)

  private def resolve(
    template : ConfigQuestion,
    id       : String,
    taxYear  : Option[String],
    catalog  : RateCatalog,
    translate: String => String
  ): ResolvedQuestion =
    val title = CalculationsI18n.text(template.title, translate)
    ResolvedQuestion(
      template = template,
      id = id,
      title = taxYear.fold(title)(year => s"$title ($year)"),
      hint = CalculationsI18n.text(template.hint, translate),
      taxYear = taxYear,
      options = options(template, catalog, translate)
    )

  /** A per-year question's rule reads the same year's answer when there is one. */
  private def isVisible(question: ConfigQuestion, answers: Map[String, String], taxYear: Option[String]): Boolean =
    question.showIf.forall: rule =>
      val raw = taxYear
        .flatMap(year => answers.get(YearAnswers.key(rule.field, year)))
        .orElse(answers.get(rule.field))
        .getOrElse("")
      rule.matches(Answers.splitMulti(raw))

  private def options(template: ConfigQuestion, catalog: RateCatalog, translate: String => String): Seq[QuestionOption] =
    val raw =
      if template.optionsFromRates then catalog.taxYears.map(year => QuestionOption(year, TaxYear.display(year)))
      else template.options.getOrElse(Nil)
    raw.map(CalculationsI18n.optionLabel(_, translate))

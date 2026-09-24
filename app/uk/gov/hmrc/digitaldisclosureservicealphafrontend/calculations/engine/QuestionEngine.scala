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
  ConfigQuestion,
  QuestionOption,
  QuestionPack,
  RateCatalog,
  ResolvedQuestion,
  ShowIf,
  YearAnswers
}

object QuestionEngine:

  val TaxYearsQuestionId = "taxYears"

  def selectedTaxYears(answers: Map[String, String]): Seq[String] =
    splitMulti(answers.getOrElse(TaxYearsQuestionId, ""))

  def resolve(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    translate: String => String = identity
  ): Seq[ResolvedQuestion] =
    val years = selectedTaxYears(answers)
    val shared = pack.questions.filterNot(_.perTaxYear).flatMap: template =>
      if !isTemplateVisible(template, answers) then Nil
      else
        Seq(
          ResolvedQuestion(
            template = template,
            id = template.id,
            title = CalculationsI18n.text(template.title, translate),
            hint = CalculationsI18n.text(template.hint, translate),
            taxYear = None,
            options = resolvedOptions(template, catalog, translate)
          )
        )

    val perYearTemplates = pack.questions.filter(_.perTaxYear)
    val byYear = years.flatMap: year =>
      perYearTemplates.flatMap: template =>
        if !isTemplateVisible(template, answers, Some(year)) then Nil
        else
          Seq(
            ResolvedQuestion(
              template = template,
              id = YearAnswers.key(template.id, year),
              title = s"${CalculationsI18n.text(template.title, translate)} ($year)",
              hint = CalculationsI18n.text(template.hint, translate),
              taxYear = Some(year),
              options = resolvedOptions(template, catalog, translate)
            )
          )

    shared ++ byYear

  def visibleQuestions(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    translate: String => String = identity
  ): Seq[ResolvedQuestion] =
    resolve(pack, catalog, answers, translate)

  def nextQuestion(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    afterId  : Option[String] = None,
    translate: String => String = identity
  ): Option[ResolvedQuestion] =
    val visible = visibleQuestions(pack, catalog, answers, translate)
    afterId match
      case None => visible.headOption
      case Some(id) =>
        val idx = visible.indexWhere(_.id == id)
        if idx < 0 then visible.headOption
        else visible.lift(idx + 1)

  def previousQuestionId(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    currentId: String,
    translate: String => String = identity
  ): Option[String] =
    val visible = visibleQuestions(pack, catalog, answers, translate)
    val idx = visible.indexWhere(_.id == currentId)
    if idx > 0 then Some(visible(idx - 1).id) else None

  def find(
    pack     : QuestionPack,
    catalog  : RateCatalog,
    answers  : Map[String, String],
    id       : String,
    translate: String => String = identity
  ): Option[ResolvedQuestion] =
    visibleQuestions(pack, catalog, answers, translate).find(_.id == id)

  def packTitle(pack: QuestionPack, translate: String => String = identity): String =
    CalculationsI18n.text(pack.title, translate)

  def splitMulti(raw: String): Seq[String] =
    raw.split(',').toSeq.map(_.trim).filter(_.nonEmpty)

  def joinMulti(values: Seq[String]): String =
    values.map(_.trim).filter(_.nonEmpty).mkString(",")

  private def isTemplateVisible(
    question: ConfigQuestion,
    answers : Map[String, String],
    taxYear : Option[String] = None
  ): Boolean =
    question.showIf match
      case None => true
      case Some(rule) =>
        val raw = answerForShowIf(answers, rule.field, taxYear)
        val values = splitMulti(raw)
        matchesShowIf(rule, values)

  private def matchesShowIf(rule: ShowIf, values: Seq[String]): Boolean =
    val hasPositive = rule.equals.isDefined || rule.contains.isDefined
    val positiveOk =
      if !hasPositive then true
      else
        rule.equals.exists(values.contains) || rule.contains.exists(values.contains)
    val negativeOk = rule.notEquals.forall(v => !values.contains(v))
    val hasAny = hasPositive || rule.notEquals.isDefined
    if !hasAny then true
    else if values.isEmpty then false
    else positiveOk && negativeOk

  /** Prefer a year-scoped answer when the dependent question is per tax year. */
  private def answerForShowIf(
    answers: Map[String, String],
    field  : String,
    taxYear: Option[String]
  ): String =
    taxYear
      .map(YearAnswers.key(field, _))
      .flatMap(answers.get)
      .orElse(answers.get(field))
      .getOrElse("")

  private def resolvedOptions(
    template : ConfigQuestion,
    catalog  : RateCatalog,
    translate: String => String
  ): Seq[QuestionOption] =
    val raw =
      if template.optionsFromRates then
        catalog.years.map: pack =>
          QuestionOption(value = pack.taxYear, label = displayTaxYear(pack.taxYear))
      else template.options.getOrElse(Nil)
    raw.map(CalculationsI18n.optionLabel(_, translate))

  private def displayTaxYear(taxYear: String): String =
    taxYear.split('-').toList match
      case start :: end :: Nil if start.length == 4 && end.length == 2 =>
        s"$start to 20$end"
      case _ => taxYear

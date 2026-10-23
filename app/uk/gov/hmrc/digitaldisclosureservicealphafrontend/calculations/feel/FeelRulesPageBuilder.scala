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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.feel

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{CalculationsConfig, TaxYear, WorkedExampleScenario}

/** One rule as stakeholders read it: its name, the rule in words, the FEEL behind it, and its value each year. */
final case class FeelRuleRow(id: String, name: String, words: String, feel: String, values: Seq[String])

final case class FeelRulesSection(titleKey: String, rows: Seq[FeelRuleRow])

/** An answer the rules read, with the question that collects it. */
final case class FeelAnswerRow(name: String, question: String)

/** A rate the rules read, with its value each year. */
final case class FeelRateRow(name: String, values: Seq[String])

final case class FeelRulesPage(
  rulesId        : String,
  version        : String,
  description    : Option[String],
  exampleTitleKey: Option[String],
  years          : Seq[String],
  sections       : Seq[FeelRulesSection],
  answers        : Seq[FeelAnswerRow],
  rates          : Seq[FeelRateRow],
  exampleProblem : Option[String]
)

/**
  * Lays the FEEL rules out as a tax computation for people who do not read code: every step in words, with the
  * figures for the worked example that covers the most tax years, and the answers and rates the rules read.
  */
object FeelRulesPageBuilder:

  def build(
    config   : CalculationsConfig,
    feel     : FeelRulesConfig,
    examples : Seq[WorkedExampleScenario],
    translate: String => String = identity
  ): FeelRulesPage =
    val rules = feel.compiled
    val vocabulary = FeelVocabulary(rules, config.questionPack, config.rateCatalog, translate)

    val example = examples.maxByOption(_.taxYearCount)
    val years = example.toSeq.flatMap(e => config.rateCatalog.years.take(e.taxYearCount))
    val answers = example.fold(Map.empty[String, String])(_.answersFor(years.map(_.taxYear)))
    val computed = years.map(rates => FeelCalculator.year(rates, config.rateCatalog, config.questionPack, rules, answers))
    val figures = computed.collect { case Right(year) => year }
    val problem = computed.collectFirst { case Left(f) => s"${vocabulary.stepName(f.step.id)} (${f.taxYear}): ${f.message}" }

    def rows(part: FeelPart): Seq[FeelRuleRow] =
      rules.steps.filter(_.part == part).map: s =>
        FeelRuleRow(
          id = s.id,
          name = vocabulary.stepName(s.id),
          words = FeelRuleRenderer.rule(s, rules.expression(s), vocabulary),
          feel = s.feel,
          values = figures.map(year => year.valueOf(s.id).fold("")(FeelRuleRenderer.formatValue(s.format, _)))
        )

    val referenced = rules.steps.flatMap(s => FeelReferences.of(rules.expression(s))).distinct
    val answerRows = referenced.collect { case Reference("answers", Some(q)) => q }.distinct.map: q =>
      FeelAnswerRow(vocabulary.answerName(q), vocabulary.question(q).fold(q)(question => translate(question.title)))
    val rateRows = referenced.collect { case Reference("rates", Some(k)) => k }.distinct.map: k =>
      FeelRateRow(
        name = vocabulary.rateName(k),
        values = years.map(rates => config.rateCatalog.definition(k).fold(rates.value(k).toString)(d => Formats.rateValue(d.kind, rates.value(k))))
      )

    FeelRulesPage(
      rulesId = rules.rules.id,
      version = rules.rules.version,
      description = rules.rules.description,
      exampleTitleKey = example.map(_.title),
      years = years.map(rates => TaxYear.display(rates.taxYear)),
      sections = Seq(
        FeelRulesSection("calculations.feel.section.tax", rows(FeelPart.tax)),
        FeelRulesSection("calculations.feel.section.penalty", rows(FeelPart.penalty))
      ).filter(_.rows.nonEmpty),
      answers = answerRows,
      rates = rateRows,
      exampleProblem = problem
    )

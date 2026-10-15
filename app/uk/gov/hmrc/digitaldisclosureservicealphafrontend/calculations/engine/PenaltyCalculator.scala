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

import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats.{gbp, percentPoints}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  Answers,
  CalcExplanation,
  DisclosureSlice,
  PenaltyRules,
  PenaltyScore,
  TaxRules,
  TellingPart,
  TellingReduction,
  YearAnswers
}

/** One tax year's penalty, with the steps shown on the result page. */
final case class PenaltyOutcome(amount: BigDecimal, steps: Seq[CalcExplanation])

/**
  * Quality of disclosure for a failure to notify, from the calculation spec.
  *
  * Telling, helping and giving are shares of a 100% quality reduction. Telling is the
  * average of its parts. The penalty rate then narrows the statutory range:
  *   1. range = upper − lower
  *   2. reduction = range × quality of disclosure
  *   3. penalty rate = upper − reduction
  *
  * The 3 year rule is not applied. A reasonable excuse is no penalty.
  */
object PenaltyCalculator:

  /** None when the behaviour question has not been answered yet. */
  def calculate(
    rules  : PenaltyRules,
    answers: Map[String, String],
    taxYear: String,
    taxDue : BigDecimal,
    tax    : TaxRules
  ): Option[PenaltyOutcome] =
    choice(answers, rules.excuseQuestion, taxYear).map: behaviour =>
      if behaviour == rules.excuseValue then reasonableExcuse(tax)
      else disclose(rules, answers, taxYear, taxDue, tax)

  private def disclose(
    rules  : PenaltyRules,
    answers: Map[String, String],
    taxYear: String,
    taxDue : BigDecimal,
    tax    : TaxRules
  ): PenaltyOutcome =
    val telling = tellingPoints(rules.telling, answers, taxYear)
    val helping = slicePoints(rules.helping, answers, taxYear)
    val giving = slicePoints(rules.giving, answers, taxYear)
    val quality = (telling.points + helping.points + giving.points).max(0).min(100)
    val range = (rules.upperRange - rules.lowerRange).max(0)
    val reduction = range * quality / 100
    val rate = (rules.upperRange - reduction).max(0)
    val amount = tax.round(taxDue * rate / 100)

    PenaltyOutcome(
      amount = amount,
      steps = Seq(
        tellingStep(rules.telling, telling),
        sliceStep(rules.helping, helping, "helping"),
        sliceStep(rules.giving, giving, "giving"),
        CalcExplanation(
          label = "Quality_of_disclosure",
          operation = s"Add telling, helping and giving: ${percentPoints(telling.points)} + ${percentPoints(helping.points)} + ${percentPoints(giving.points)}.",
          result = percentPoints(quality),
          working = Some(s"${percentPoints(telling.points)} + ${percentPoints(helping.points)} + ${percentPoints(giving.points)}")
        ),
        CalcExplanation(
          label = "Penalty_range",
          operation =
            s"Step 1. Upper range ${percentPoints(rules.upperRange)} minus lower range ${percentPoints(rules.lowerRange)}.",
          result = percentPoints(range),
          working = Some(s"${percentPoints(rules.upperRange)} − ${percentPoints(rules.lowerRange)}")
        ),
        CalcExplanation(
          label = "Percentage_reduction",
          operation =
            s"Step 2. Multiply the range, as a whole number, by the quality of disclosure: ${number(range)} × ${percentPoints(quality)}.",
          result = percentPoints(reduction),
          working = Some(s"${number(range)} × ${percentPoints(quality)}")
        ),
        CalcExplanation(
          label = "Penalty_percentage_rate",
          operation =
            s"Step 3. Upper range ${percentPoints(rules.upperRange)} minus the percentage reduction ${percentPoints(reduction)}.",
          result = percentPoints(rate),
          working = Some(s"${percentPoints(rules.upperRange)} − ${percentPoints(reduction)}")
        ),
        CalcExplanation(
          label = "Penalty",
          operation = s"Multiply the tax due of ${gbp(taxDue)} by the penalty rate of ${percentPoints(rate)}.",
          result = gbp(amount),
          working = Some(s"${gbp(taxDue)} × ${percentPoints(rate)}")
        )
      )
    )

  private def reasonableExcuse(tax: TaxRules): PenaltyOutcome =
    PenaltyOutcome(
      amount = tax.round(0),
      steps = Seq(
        CalcExplanation(
          label = "Penalty",
          operation =
            "There is a reasonable excuse, so the penalty is £0. For a failure to notify, the 3 year rule does not apply.",
          result = gbp(tax.round(0))
        )
      )
    )

  private final case class Award(fraction: BigDecimal, points: BigDecimal, parts: Seq[(TellingPart, BigDecimal)] = Nil)

  private def tellingPoints(telling: TellingReduction, answers: Map[String, String], taxYear: String): Award =
    val parts = telling.parts.map(part => part -> scoreFor(part.scores, choice(answers, part.question, taxYear)))
    val size = BigDecimal(parts.size.max(1))
    val total = parts.map(_._2).sum
    Award(fraction = total / size, points = total * telling.maximum / size, parts = parts)

  private def slicePoints(slice: DisclosureSlice, answers: Map[String, String], taxYear: String): Award =
    val share = slice.question match
      case Some(question) => scoreFor(slice.scores, choice(answers, question, taxYear))
      case None           => slice.score
    Award(fraction = share, points = share * slice.maximum)

  private def tellingStep(telling: TellingReduction, award: Award): CalcExplanation =
    val scores = award.parts.map((part, score) => s"${words(part.label)} scores ${number(score)}").mkString(", ")
    val total = award.parts.map(_._2).sum
    CalcExplanation(
      label = "Telling",
      operation =
        s"$scores. That is ${number(total)} of ${award.parts.size}. " +
          s"${number(total)}/${award.parts.size} of the ${percentPoints(telling.maximum)} telling maximum is ${percentPoints(award.points)}.",
      result = percentPoints(award.points),
      working = Some(s"${number(total)}/${award.parts.size} × ${percentPoints(telling.maximum)}")
    )

  private def sliceStep(slice: DisclosureSlice, award: Award, name: String): CalcExplanation =
    val operation =
      if slice.question.isEmpty && award.fraction >= BigDecimal(1) then
        s"${words(slice.label)} is configured as a full reduction: ${percentPoints(award.points)} of the ${percentPoints(slice.maximum)} maximum."
      else
        val kind =
          if award.fraction >= BigDecimal(1) then "a full reduction"
          else if award.fraction <= BigDecimal(0) then "no reduction"
          else "a partial reduction"
        s"This $name answer is $kind, scoring ${number(award.fraction)}. " +
          s"${number(award.fraction)} × the ${percentPoints(slice.maximum)} maximum is ${percentPoints(award.points)}."
    CalcExplanation(
      label = slice.label,
      operation = operation,
      result = percentPoints(award.points),
      working = Some(s"${number(award.fraction)} × ${percentPoints(slice.maximum)}")
    )

  private def scoreFor(scores: Seq[PenaltyScore], answer: Option[String]): BigDecimal =
    answer.flatMap(value => scores.find(_.value == value).map(_.score)).getOrElse(BigDecimal(0))

  private def choice(answers: Map[String, String], question: String, taxYear: String): Option[String] =
    answers
      .get(YearAnswers.key(question, taxYear))
      .orElse(answers.get(question))
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(Answers.splitMulti(_).headOption.getOrElse(""))
      .filter(_.nonEmpty)

  private def number(amount: BigDecimal): String =
    amount.bigDecimal.stripTrailingZeros.toPlainString

  private def words(key: String): String =
    key.replace('_', ' ')

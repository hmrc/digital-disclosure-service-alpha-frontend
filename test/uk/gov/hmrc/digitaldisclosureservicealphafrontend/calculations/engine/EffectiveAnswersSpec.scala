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

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.CalculationsFixtures.*
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ConfigQuestion, QuestionType, YearAnswers}

import scala.util.Random

class EffectiveAnswersSpec extends AnyWordSpec with Matchers:

  private val Y = "2016-17"
  private def k(id: String) = YearAnswers.key(id, Y)
  private def effective(stored: Map[String, String]) = EffectiveAnswers.of(fullPack, catalog, stored)
  private def tax(answers: Map[String, String]) = LiabilityCalculator.calculate(catalog, spec, answers).years.head.taxDue

  /** A self-employed user who has finished the journey for one year, claiming the trading allowance. */
  private val selfEmployed = aboutYou ++ Map(
    "taxYears" -> Y, "incomeTypes" -> "selfEmployment",
    k("alreadyDeclaredIncome") -> "0", k("taxAlreadyPaid") -> "0",
    k("selfEmploymentBusinessName") -> "Shop", k("selfEmploymentDescription") -> "Shop", k("selfEmploymentStillTrading") -> "yes",
    k("selfEmploymentUseTradingAllowance") -> "yes", k("selfEmploymentTradingAllowance") -> "1000", k("selfEmploymentTurnover") -> "30000",
    k("claimAnyReliefs") -> "no", k("penaltyBehaviour") -> "reasonableExcuse"
  )

  private val useAllowance = k("selfEmploymentUseTradingAllowance")
  private val allowance = k("selfEmploymentTradingAllowance")
  private val expenses = k("selfEmploymentExpenses")

  "EffectiveAnswers.of" should:
    "count an answer only while its question is on the path" in:
      val switched = effective(selfEmployed ++ Map(useAllowance -> "no", expenses -> "5000"))
      switched should contain(expenses -> "5000")
      switched should not contain key(allowance)
      // (30,000 turnover − 5,000 expenses − 11,000 personal allowance) × 20%: the £1,000 allowance off the path is not deducted
      tax(switched) shouldBe BigDecimal("2800.00")

    "take a closed branch's follow-ups with it, however deep" in:
      val unticked = effective(selfEmployed ++ Map(useAllowance -> "no", expenses -> "5000", "incomeTypes" -> "dividends", k("dividends") -> "1000"))
      unticked.keys.filter(_.startsWith("selfEmployment")) shouldBe empty
      tax(unticked) shouldBe BigDecimal("0.00")

    "count an answer again, as it was, when its question comes back" in:
      val back = effective(selfEmployed ++ Map(useAllowance -> "no", expenses -> "5000") + (useAllowance -> "yes"))
      back.get(allowance) shouldBe Some("1000")
      back should not contain key(expenses)

    "bring back a re-added tax year's answers" in:
      val removed = effective(selfEmployed + ("taxYears" -> "2017-18"))
      removed.keys.filter(_.endsWith(Y)) shouldBe empty
      effective(selfEmployed + ("taxYears" -> s"$Y,2017-18")).get(k("selfEmploymentTurnover")) shouldBe Some("30000")

  "EffectiveAnswers.of, over random stored answers" should:
    "keep its invariants" in:
      val rnd = new Random(20261027)
      val years = catalog.taxYears.take(3)
      def pick[A](xs: Seq[A]): A = xs(rnd.nextInt(xs.size))
      def subset(xs: Seq[String]): String =
        val chosen = xs.filter(_ => rnd.nextDouble() < 0.4)
        (if chosen.isEmpty then Seq(pick(xs)) else chosen).mkString(",")
      def valueFor(q: ConfigQuestion): String =
        q.questionType match
          case QuestionType.checkboxes if q.id == "taxYears" => subset(years)
          case QuestionType.checkboxes                       => subset(q.options.getOrElse(Nil).map(_.value))
          case QuestionType.singleChoice                     => pick(q.options.getOrElse(Nil).map(_.value))
          case QuestionType.yesNo                            => pick(Seq("yes", "no"))
          case QuestionType.currency                         => rnd.nextInt(60000).toString
          case QuestionType.text                             => "x"
      def anyId(q: ConfigQuestion): String = if q.perTaxYear then YearAnswers.key(q.id, pick(years)) else q.id
      def onPath(answers: Map[String, String]) = QuestionEngine.visibleQuestions(fullPack, catalog, answers).map(_.id).toSet

      (1 to 300).foreach: run =>
        // a user saving answers to questions on their path, in any order, changing earlier answers as they go
        val saves = (1 to 1 + rnd.nextInt(60)).foldLeft(Vector.empty[(String, String)]): (acc, _) =>
          val counting = effective(acc.toMap)
          val q = pick(QuestionEngine.visibleQuestions(fullPack, catalog, counting))
          acc :+ (q.id -> valueFor(q.template))
        val stored = saves.toMap
        val counting = effective(stored)
        withClue(s"run $run: only questions on the path have answers that count. ")(counting.keySet.subsetOf(onPath(counting)) shouldBe true)
        withClue(s"run $run: what counts is a subset of what is stored. ")(counting.toSet.subsetOf(stored.toSet) shouldBe true)
        withClue(s"run $run: working it out twice changes nothing. ")(effective(counting) shouldBe counting)
        fullPack.questions.map(anyId).find(id => !onPath(counting)(id)).foreach: hidden =>
          withClue(s"run $run: an answer to a question off the path changes nothing. ")(
            effective(stored + (hidden -> "99999")) shouldBe counting
          )

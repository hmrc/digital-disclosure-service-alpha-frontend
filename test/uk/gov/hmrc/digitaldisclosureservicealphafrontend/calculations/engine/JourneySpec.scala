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
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{
  ConfigQuestion,
  LogEntry,
  OnReturn,
  QuestionPack,
  QuestionType,
  Via,
  YearAnswers
}

import java.time.Instant
import scala.util.Random

class JourneySpec extends AnyWordSpec with Matchers:

  private val Y = "2016-17"
  private def k(id: String) = YearAnswers.key(id, Y)
  private def log(pairs: (String, String)*): Vector[LogEntry] = pairs.map(answerGiven).toVector
  private def project(entries: Seq[LogEntry], pack: QuestionPack = fullPack) = Journey.project(pack, catalog, entries)
  private def tax(state: JourneyState) = LiabilityCalculator.calculate(catalog, spec, state.effective).years.head.taxDue

  private def withYearSetting(setting: OnReturn): QuestionPack =
    fullPack.copy(taskList = fullPack.taskList.copy(eachYear = fullPack.taskList.eachYear.map(_.copy(onReturn = Some(setting)))))

  /** A self-employed user who has finished the journey for one year. */
  private val selfEmployed = log(
    "taxYears" -> Y, "incomeTypes" -> "selfEmployment",
    "ageBand" -> "under65", "marriedOrCivilPartnership" -> "no", "blindPersonEligible" -> "no",
    k("alreadyDeclaredIncome") -> "0", k("taxAlreadyPaid") -> "0",
    k("selfEmploymentBusinessName") -> "Shop", k("selfEmploymentDescription") -> "Shop", k("selfEmploymentStillTrading") -> "yes",
    k("selfEmploymentUseTradingAllowance") -> "yes", k("selfEmploymentTradingAllowance") -> "1000", k("selfEmploymentTurnover") -> "30000",
    k("claimAnyReliefs") -> "no", k("penaltyBehaviour") -> "reasonableExcuse"
  )

  private val useAllowance = k("selfEmploymentUseTradingAllowance")
  private val allowance = k("selfEmploymentTradingAllowance")
  private val expenses = k("selfEmploymentExpenses")

  "Journey.project" should:
    "count an answer only while its question is on the path" in:
      val switched = project(selfEmployed ++ log(useAllowance -> "no", expenses -> "5000"))
      switched.effective should contain(expenses -> "5000")
      switched.effective should not contain key(allowance)
      switched.lapsed shouldBe Map(allowance -> "1000")
      // (30,000 turnover − 5,000 expenses − 11,000 personal allowance) × 20%; the lapsed £1,000 allowance is not deducted
      tax(switched) shouldBe BigDecimal("2800.00")

    "take a closed branch's follow-ups with it, however deep" in:
      val unticked = project(selfEmployed ++ log(useAllowance -> "no", expenses -> "5000", "incomeTypes" -> "dividends", k("dividends") -> "1000"))
      unticked.effective.keys.filter(_.startsWith("selfEmployment")) shouldBe empty
      unticked.lapsed.keySet should contain allOf (expenses, k("selfEmploymentTurnover"), useAllowance)
      tax(unticked) shouldBe BigDecimal("0.00")

    "record an answer to a question that was not on the path, but not count it" in:
      val stale = project(selfEmployed ++ log("incomeTypes" -> "dividends", expenses -> "7000"))
      stale.effective should not contain key(expenses)
      stale.replay.last.outcome shouldBe EntryOutcome.Ignored

    "say which answers each entry took off the path" in:
      val switched = project(selfEmployed :+ answerGiven(useAllowance, "no"))
      switched.replay.last.outcome shouldBe EntryOutcome.Counted(lapsed = Seq(allowance), restored = Nil)

    "not bring back a re-added tax year's answers unseen" in:
      val readded = project(selfEmployed ++ log("taxYears" -> "2017-18", "taxYears" -> s"$Y,2017-18"))
      readded.effective.keys.filter(_.endsWith(Y)) shouldBe empty
      readded.returning.keySet should contain allOf (k("selfEmploymentTurnover"), k("penaltyBehaviour"))

  "the onReturn setting" should:
    "ask again, with the old answer to pre-fill, under askAgain" in:
      val back = project(selfEmployed ++ log(useAllowance -> "no", useAllowance -> "yes"), withYearSetting(OnReturn.askAgain))
      back.effective should not contain key(allowance)
      back.returning shouldBe Map(allowance -> OnReturn.askAgain)
      back.lapsed.get(allowance) shouldBe Some("1000")

    "wait for the user to confirm under confirmAsGroup, then count the confirmed answers" in:
      val back = selfEmployed ++ log(useAllowance -> "no", useAllowance -> "yes")
      project(back).returning shouldBe Map(allowance -> OnReturn.confirmAsGroup)

      val confirmed = project(back :+ LogEntry.AnswersConfirmed(Seq(allowance), Instant.EPOCH, Via.ConfirmPage))
      confirmed.effective.get(allowance) shouldBe Some("1000")
      confirmed.returning shouldBe empty
      confirmed.replay.last.outcome shouldBe EntryOutcome.Counted(lapsed = Nil, restored = Seq(allowance))

    "count the old answer again straight away under restore" in:
      val back = project(selfEmployed ++ log(useAllowance -> "no", expenses -> "5000", useAllowance -> "yes"), withYearSetting(OnReturn.restore))
      back.effective.get(allowance) shouldBe Some("1000")
      back.effective should not contain key(expenses)
      back.replay.last.outcome shouldBe EntryOutcome.Counted(lapsed = Seq(expenses), restored = Seq(allowance))

    "come from the section, else eachYear, else the journey" in:
      val byId = fullPack.questions.map(q => q.id -> q).toMap
      fullPack.taskList.onReturnFor(byId("selfEmploymentTurnover"), fullPack) shouldBe OnReturn.confirmAsGroup
      fullPack.taskList.onReturnFor(byId("ageBand"), fullPack) shouldBe OnReturn.askAgain

  "Journey.project, over random logs" should:
    "keep its invariants" in:
      val rnd = new Random(20261026)
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
      def visible(state: JourneyState) = QuestionEngine.visibleQuestions(fullPack, catalog, state.effective).map(_.id).toSet

      /** Mostly answers a question on the path; sometimes a stale post, or a confirmation of lapsed answers. */
      def entry(state: JourneyState): LogEntry =
        val onPath = QuestionEngine.visibleQuestions(fullPack, catalog, state.effective)
        val roll = rnd.nextDouble()
        if roll < 0.75 && onPath.nonEmpty then
          val q = pick(onPath)
          answerGiven(q.id, valueFor(q.template))
        else if roll < 0.85 && state.lapsed.nonEmpty then
          LogEntry.AnswersConfirmed(state.lapsed.keys.filter(_ => rnd.nextBoolean()).toSeq, Instant.EPOCH, Via.ConfirmPage)
        else
          val q = pick(fullPack.questions)
          answerGiven(anyId(q), valueFor(q))

      (1 to 300).foreach: run =>
        val entries = (1 to 1 + rnd.nextInt(60)).foldLeft(Vector.empty[LogEntry])((acc, _) => acc :+ entry(project(acc)))
        val state = project(entries)
        val onPath = visible(state)
        withClue(s"run $run: only questions on the path have answers that count. ")(state.effective.keySet.subsetOf(onPath) shouldBe true)
        withClue(s"run $run: a lapsed answer never also counts. ")(state.lapsed.keySet.intersect(state.effective.keySet) shouldBe empty)
        withClue(s"run $run: the same log gives the same state. ")(project(entries) shouldBe state)
        withClue(s"run $run: submitting the last entry twice changes nothing. ")(project(entries :+ entries.last).effective shouldBe state.effective)
        fullPack.questions.map(anyId).find(id => !onPath(id)).foreach: hidden =>
          withClue(s"run $run: an answer to a question off the path changes nothing. ")(
            project(entries :+ answerGiven(hidden, "99999")).effective shouldBe state.effective
          )

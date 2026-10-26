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

import org.camunda.feel.syntaxtree.{
  Addition,
  ArithmeticNegation,
  Conjunction,
  ConstBool,
  ConstList,
  ConstNull,
  ConstNumber,
  ConstString,
  Disjunction,
  Division,
  Equal,
  Exp,
  FunctionInvocation,
  GreaterOrEqual,
  GreaterThan,
  If,
  LessOrEqual,
  LessThan,
  Multiplication,
  NamedFunctionParameters,
  Not,
  PathExpression,
  PositionalFunctionParameters,
  Ref,
  Subtraction
}
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.format.Formats
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.i18n.CalculationsI18n
import uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.model.{ConfigQuestion, QuestionPack, QuestionType, RateCatalog}

import scala.math.BigDecimal.RoundingMode

/** The words for everything a rule can read: step labels, rate names, answers and their options. */
final case class FeelVocabulary(
  rules    : CompiledFeelRules,
  pack     : QuestionPack,
  catalog  : RateCatalog,
  translate: String => String
):
  private val steps = rules.steps.map(s => s.id -> s).toMap

  def step(stepId: String): Option[FeelStep] = steps.get(stepId)

  def stepName(stepId: String): String = steps.get(stepId).fold(stepId)(s => CalculationsI18n.text(s.label, translate))

  def answerName(questionId: String): String = FeelVocabulary.humanise(questionId)

  def rateName(key: String): String = catalog.definition(key).fold(FeelVocabulary.humanise(key))(d => translate(d.label))

  def groupName(group: String): String = s"the ${FeelVocabulary.humanise(group)} lines"

  def question(questionId: String): Option[ConfigQuestion] = pack.questions.find(_.id == questionId)

  /** An answer as the user saw it: the option's wording, without the examples in brackets, rather than its code. */
  def optionName(questionId: String, value: String): String =
    question(questionId) match
      case Some(q) if q.questionType == QuestionType.yesNo => translate(s"calculations.$value")
      case Some(q) =>
        q.options.flatMap(_.find(_.value == value)).fold(value)(o => translate(o.label).split(" \\(", 2).head)
      case None => value

object FeelVocabulary:

  /** `selfEmploymentTurnover` → "self employment turnover". */
  def humanise(id: String): String =
    id.split("(?<=[a-z0-9])(?=[A-Z])").toSeq
      .map(_.toLowerCase)
      .map:
        case "uk" => "UK"
        case word => word
      .mkString(" ")

/**
  * Writes a FEEL rule for people who do not read FEEL. [[words]] names what the rule reads ("Total income −
  * the taper threshold, not below zero"); [[working]] puts one year's figures in their place ("£16,500.00 −
  * £100,000, not below zero"). Both walk the expression's syntax tree, so they always match the rule that runs.
  */
object FeelRuleRenderer:

  def words(exp: Exp, vocabulary: FeelVocabulary): String = Render(vocabulary, None).phrase(exp)

  /** A step's rule in words. A step that is only a number reads as that value in the step's format: "Fixed at 30%". */
  def rule(step: FeelStep, exp: Exp, vocabulary: FeelVocabulary): String =
    exp match
      case n: ConstNumber => s"Fixed at ${formatValue(step.format, n.value)}"
      case other          => words(other, vocabulary)

  def working(exp: Exp, vocabulary: FeelVocabulary, year: FeelYear): String = Render(vocabulary, Some(year)).phrase(exp)

  /** A step's value in the format its rule declares: £1,100.00, 21%, 0.5, Yes. */
  def formatValue(format: FeelValueFormat, value: Any): String =
    (format, value) match
      case (_, flag: Boolean)                          => if flag then "Yes" else "No"
      case (FeelValueFormat.money, n: BigDecimal)      => Formats.gbp(n)
      case (FeelValueFormat.percentage, n: BigDecimal) => Formats.percent(n.setScale(6, RoundingMode.HALF_UP))
      case (_, n: BigDecimal)                          => plain(n)
      case (_, other)                                  => String.valueOf(other)

  private def plain(n: BigDecimal): String = n.bigDecimal.stripTrailingZeros.toPlainString

  private def quote(text: String): String = s"‘$text’"

  /** "a", "a and b", "a, b and c". */
  private def joined(items: Seq[String], last: String): String =
    items match
      case Seq()       => ""
      case Seq(single) => single
      case _           => s"${items.init.mkString(", ")} $last ${items.last}"

  private final class Render(vocabulary: FeelVocabulary, year: Option[FeelYear]):

    /**
      * How tightly a phrase binds. A phrase that binds less tightly than the place it sits in is bracketed,
      * so "(Taxable income − the basic rate band, not below zero) × the higher rate" stays unambiguous.
      */
    private def strength(exp: Exp): Int =
      exp match
        case _: Addition | _: Subtraction                => 2
        case _: Multiplication | _: Division             => 3
        case _: ArithmeticNegation                       => 4
        case f: FunctionInvocation if inWords(f.function) => 1
        case _: Ref | _: PathExpression | _: ConstNumber | _: ConstString | _: ConstBool | _: FunctionInvocation => 5
        case _                                           => 0

    private val inWords = Set("max", "min", "sum", "mean", "floor", "round half up", "round down", "list contains")

    private def slot(exp: Exp, needs: Int): String =
      val text = phrase(exp)
      if strength(exp) < needs then s"($text)" else text

    def phrase(exp: Exp): String =
      exp match
        case n: ConstNumber        => plain(n.value)
        case s: ConstString        => quote(s.value)
        case b: ConstBool          => if b.value then "yes" else "no"
        case ConstNull             => "no answer"
        case r: Ref                => FeelReferences.of(r).headOption.fold(r.toString)(reference)
        case p: PathExpression     => FeelReferences.of(p).headOption.fold(p.toString)(reference)
        case a: Addition           => s"${slot(a.x, 2)} + ${slot(a.y, 2)}"
        case s: Subtraction        => s"${slot(s.x, 2)} − ${slot(s.y, 3)}"
        case m: Multiplication     => s"${slot(m.x, 3)} × ${slot(m.y, 3)}"
        case d: Division           => s"${slot(d.x, 3)} ÷ ${slot(d.y, 4)}"
        case n: ArithmeticNegation => s"−${slot(n.x, 4)}"
        case e: Equal              => equal(e.x, e.y)
        case n: Not                => negation(n.x)
        case c: GreaterThan        => s"${slot(c.x, 2)} is more than ${slot(c.y, 2)}"
        case c: GreaterOrEqual     => s"${slot(c.x, 2)} is at least ${slot(c.y, 2)}"
        case c: LessThan           => s"${slot(c.x, 2)} is less than ${slot(c.y, 2)}"
        case c: LessOrEqual        => s"${slot(c.x, 2)} is at most ${slot(c.y, 2)}"
        case c: Conjunction        => s"${slot(c.x, 1)} and ${slot(c.y, 1)}"
        case d: Disjunction        => s"${slot(d.x, 1)} or ${slot(d.y, 1)}"
        case i: If                 => conditional(i)
        case f: FunctionInvocation => function(f.function, arguments(f))
        case l: ConstList          => joined(l.items.map(phrase), "or")
        case other                 => other.toString

    private def reference(ref: Reference): String =
      (ref, year) match
        case (Reference("answers", Some(q)), None)    => vocabulary.answerName(q)
        case (Reference("answers", Some(q)), Some(y)) => answerValue(q, y.inputs.answers.getOrElse(q, null))
        case (Reference("rates", Some(k)), None)      => s"the ${vocabulary.rateName(k)}"
        case (Reference("rates", Some(k)), Some(y))   => rateValue(k, y.inputs.rates.get(k))
        case (Reference("groups", Some(g)), _)        => vocabulary.groupName(g)
        case (Reference(stepId, None), Some(y)) if y.valueOf(stepId).isDefined =>
          formatValue(vocabulary.step(stepId).fold(FeelValueFormat.money)(_.format), y.valueOf(stepId).get)
        case (Reference(stepId, None), _)             => vocabulary.stepName(stepId)
        case (Reference(root, key), _)                => (root +: key.toSeq).mkString(".")

    private def answerValue(questionId: String, value: Any): String =
      value match
        case null          => "no answer"
        case n: BigDecimal => Formats.gbp(n)
        case s: String     => quote(vocabulary.optionName(questionId, s))
        case xs: Seq[?]    => joined(xs.map(v => quote(vocabulary.optionName(questionId, v.toString))), "and")
        case other         => other.toString

    private def rateValue(key: String, value: Option[BigDecimal]): String =
      (vocabulary.catalog.definition(key), value) match
        case (Some(definition), Some(v)) => Formats.rateValue(definition.kind, v)
        case (_, Some(v))                => plain(v)
        case _                           => s"the ${vocabulary.rateName(key)}"

    private def answerQuestion(exp: Exp): Option[String] =
      exp match
        case r: Ref =>
          FeelReferences.of(r).headOption.collect { case Reference("answers", Some(q)) => q }
        case _ => None

    /** An answer compared with an option code reads as the option's wording: helping speed is ‘Right away’. */
    private def optionWords(question: Option[String], option: Exp): String =
      (question, option) match
        case (Some(q), s: ConstString) => quote(vocabulary.optionName(q, s.value))
        case _                         => phrase(option)

    private def equal(x: Exp, y: Exp): String =
      y match
        case ConstNull => s"${phrase(x)} has no answer"
        case _         => s"${slot(x, 2)} is ${optionWords(answerQuestion(x), y)}"

    private def negation(exp: Exp): String =
      exp match
        case e: Equal if e.y == ConstNull => s"${phrase(e.x)} has an answer"
        case other                        => s"not (${phrase(other)})"

    /** if A, x; if B, y; otherwise z. */
    private def conditional(exp: If): String =
      def branches(e: Exp): List[(Option[Exp], Exp)] =
        e match
          case next: If => (Some(next.condition), next.statement) :: branches(next.elseStatement)
          case last     => List(None -> last)
      branches(exp)
        .map:
          case (Some(condition), result) => s"if ${phrase(condition)}, ${phrase(result)}"
          case (None, result)            => s"otherwise ${phrase(result)}"
        .mkString("; ")

    private def arguments(f: FunctionInvocation): List[Exp] =
      f.params match
        case positional: PositionalFunctionParameters => positional.params
        case named: NamedFunctionParameters           => named.params.values.toList

    private def isZero(exp: Exp): Boolean =
      exp match
        case n: ConstNumber => n.value == 0
        case _              => false

    private def function(name: String, args: List[Exp]): String =
      (name, args) match
        case ("max", List(zero, x)) if isZero(zero) => s"${slot(x, 2)}, not below zero"
        case ("max", List(x, zero)) if isZero(zero) => s"${slot(x, 2)}, not below zero"
        case ("max", xs)                            => s"the larger of ${joined(xs.map(slot(_, 2)), "and")}"
        case ("min", xs)                            => s"the smaller of ${joined(xs.map(slot(_, 2)), "and")}"
        case ("sum", List(single))                  => total(single)
        case ("sum", xs)                            => xs.map(slot(_, 2)).mkString(" + ")
        case ("mean", xs)                           => s"the average of ${joined(xs.map(slot(_, 2)), "and")}"
        case ("floor", List(x))                     => s"${slot(x, 2)}, rounded down to a whole number"
        case ("round half up", List(x, scale))      => s"${slot(x, 2)}, ${rounding("rounded", scale)}"
        case ("round down", List(x, scale))         => s"${slot(x, 2)}, ${rounding("rounded down", scale)}"
        case ("list contains", List(items: ConstList, x)) =>
          val question = answerQuestion(x)
          val options = items.items.map(optionWords(question, _))
          if options.size == 1 then s"${phrase(x)} is ${options.head}"
          else s"${phrase(x)} is one of ${joined(options, "or")}"
        case (other, xs) => s"$other(${xs.map(phrase).mkString(", ")})"

    private def rounding(verb: String, scale: Exp): String =
      scale match
        case n: ConstNumber if n.value == 2 => s"$verb to the nearest penny"
        case n: ConstNumber if n.value == 0 => s"$verb to the nearest pound"
        case other                          => s"$verb to ${phrase(other)} decimal places"

    /** sum(groups.income): "the total of the income lines", or that year's non-zero lines added up. */
    private def total(exp: Exp): String =
      (FeelReferences.of(exp), year) match
        case (Seq(Reference("groups", Some(g))), Some(y)) =>
          val amounts = y.values.filter(_.step.group.contains(g)).collect { case FeelStepValue(_, n: BigDecimal) if n != 0 => Formats.gbp(n) }
          if amounts.isEmpty then Formats.gbp(0) else amounts.mkString(" + ")
        case (Seq(Reference("groups", Some(g))), None) => s"the total of ${vocabulary.groupName(g)}"
        case _                                         => s"the total of ${phrase(exp)}"

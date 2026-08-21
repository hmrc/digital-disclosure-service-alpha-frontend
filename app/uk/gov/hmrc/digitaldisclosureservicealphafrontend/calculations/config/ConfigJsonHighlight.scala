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

package uk.gov.hmrc.digitaldisclosureservicealphafrontend.calculations.config

final case class JsonIssueLine(
  number: Int,
  text  : String,
  issues: Seq[String]
):
  def hasError: Boolean = issues.nonEmpty

final case class JsonFieldIssues(
  fieldId   : String,
  source    : String,
  violations: Seq[ConfigViolation],
  lines     : Seq[JsonIssueLine]
):
  def summary: String =
    violations.map(_.toString).mkString("; ")

/**
  * Maps config violations onto source JSON lines for UI highlighting.
  */
object ConfigJsonHighlight:

  def forField(fieldId: String, source: String, violations: Seq[ConfigViolation]): Option[JsonFieldIssues] =
    if violations.isEmpty then None
    else
      val lineIssues = violations
        .flatMap: v =>
          locateLine(source, v.path).map(_ -> v.toString)
        .groupMap(_._1)(_._2)

      val lines =
        if source.isEmpty then
          Seq(JsonIssueLine(1, "", violations.map(_.toString)))
        else
          source.split("\n", -1).toSeq.zipWithIndex.map: (text, idx) =>
            val lineNo = idx + 1
            JsonIssueLine(lineNo, text, lineIssues.getOrElse(lineNo, Nil))

      // Ensure violations we could not place still appear on line 1
      val unplaced = violations.filter(v => locateLine(source, v.path).isEmpty).map(_.toString)
      val adjusted =
        if unplaced.isEmpty then lines
        else
          lines match
            case head +: tail => head.copy(issues = head.issues ++ unplaced) +: tail
            case Nil          => Seq(JsonIssueLine(1, "", unplaced))

      Some(JsonFieldIssues(fieldId, source, violations, adjusted))

  def build(
    rateJson       : String,
    questionJson   : String,
    calculationJson: String,
    violations     : Seq[ConfigViolation]
  ): Seq[JsonFieldIssues] =
    val grouped = ConfigValidator.groupByField(violations)
    Seq(
      forField("rateJson", rateJson, grouped.getOrElse("rateJson", Nil)),
      forField("questionJson", questionJson, grouped.getOrElse("questionJson", Nil)),
      forField("calculationJson", calculationJson, grouped.getOrElse("calculationJson", Nil))
    ).flatten

  /** Best-effort 1-based line for a dotted/indexed JSON path. */
  def locateLine(source: String, path: String): Option[Int] =
    val segments = pathSegments(path)
    if segments.isEmpty then Some(1)
    else
      val lines = source.split("\n", -1).toSeq
      findSegment(lines, segments, from = 0).map(_ + 1)

  private sealed trait PathSeg
  private case class Prop(name: String) extends PathSeg
  private case class Idx(i: Int) extends PathSeg

  private def pathSegments(path: String): Seq[PathSeg] =
    // rateCatalogue.years[0].taxYear / questionPack.questions[1].title
    val token = """([A-Za-z_][A-Za-z0-9_]*)|\[(\d+)\]""".r
    token
      .findAllMatchIn(path)
      .flatMap: m =>
        Option(m.group(1)).map(Prop.apply).orElse(Option(m.group(2)).map(s => Idx(s.toInt)))
      .toSeq
      .dropWhile:
        case Prop("rateCatalogue") | Prop("questionPack") | Prop("calculationSpec") => true
        case _                                                                     => false

  private def findSegment(lines: Seq[String], segments: Seq[PathSeg], from: Int): Option[Int] =
    segments match
      case Nil => Some(from.max(0).min(lines.length - 1))
      case Prop(name) +: rest =>
        val needle = s""""$name""""
        lines.zipWithIndex.drop(from).collectFirst {
          case (line, idx) if line.contains(needle) => idx
        }.flatMap: idx =>
          if rest.isEmpty then Some(idx) else findSegment(lines, rest, idx)
      case Idx(i) +: rest =>
        // Advance to the i-th object opener "{" after `from`
        val openers = lines.zipWithIndex.drop(from).collect {
          case (line, idx) if line.contains("{") => idx
        }
        openers.lift(i).flatMap: idx =>
          if rest.isEmpty then Some(idx) else findSegment(lines, rest, idx)

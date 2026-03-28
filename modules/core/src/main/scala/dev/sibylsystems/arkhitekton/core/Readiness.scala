package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

/** Pre-formalization gate: assess whether a spec has enough substance to formalize. */
object Readiness:

  def assess(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(ReadinessVerdict, Session)] =
    for
      raw <- backend.complete(
               model = model,
               maxTokens = 1024,
               system = Some(Prompts.readinessSystem),
               messages = List(ClaudeMessage("user", Prompts.readinessUser(session.originalSpec))),
             )
      verdict = parseVerdict(raw)
      s1      = session.withReadiness(verdict)
    yield (verdict, s1)

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private val KindPattern     = """(?i)kind:\s*(.+)""".r
  private val IntentPattern   = """(?i)intent:\s*(.+)""".r
  private val VerdictPattern  = """(?i)verdict:\s*(ready|not-ready|not ready)""".r
  private val QuestionPattern = """^\s*-\s+(.+)""".r

  private def parseVerdict(raw: String): ReadinessVerdict =
    val lines = raw.linesIterator.toList

    val kind = lines.collectFirst { case KindPattern(k) => parseKind(k.trim) }
      .getOrElse(SpecKind.Other("unclassified"))

    val intent = lines.collectFirst { case IntentPattern(i) => i.trim }
      .getOrElse("(no intent captured)")

    val isReady = lines.collectFirst { case VerdictPattern(v) => v.trim.toLowerCase }
      .exists(_ == "ready")

    if isReady then ReadinessVerdict.Ready(intent, kind)
    else
      // Collect questions: lines starting with "- " after the "questions:" line
      val questionsStart = lines.indexWhere(_.trim.toLowerCase.startsWith("questions:"))
      val questions =
        if questionsStart < 0 then Nil
        else
          lines.drop(questionsStart + 1).collect { case QuestionPattern(q) => q.trim }
      ReadinessVerdict.NotReady(questions, intent, kind)

  private def parseKind(s: String): SpecKind =
    s.toLowerCase match
      case "checker"        => SpecKind.Checker
      case "pipeline"       => SpecKind.Pipeline
      case "domain-model"   => SpecKind.DomainModel
      case "data-transform" => SpecKind.DataTransform
      case other =>
        val desc = if other.startsWith("other:") then other.stripPrefix("other:").trim else other
        SpecKind.Other(desc)

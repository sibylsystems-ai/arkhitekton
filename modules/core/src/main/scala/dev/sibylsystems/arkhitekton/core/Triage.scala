package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

/** Pre-processing step: split a markdown spec into sections and classify them. */
object Triage:

  /** Minimum non-blank lines for triage to activate. Below this, skip triage. */
  val ActivationThreshold: Int = 50

  // ---------------------------------------------------------------------------
  // Structural parse — split markdown on ## headers (pure, no LLM)
  // ---------------------------------------------------------------------------

  private val HeaderPattern = """^##\s+(.+)""".r

  final case class RawSection(heading: String, content: String, lineRange: (Int, Int))

  def splitSections(spec: String): List[RawSection] =
    val lines      = spec.linesIterator.toVector
    val headerIdxs = lines.zipWithIndex.collect { case (HeaderPattern(_), i) => i }
    if headerIdxs.isEmpty then
      // No ## headers — treat entire spec as one section
      List(RawSection("(entire spec)", spec, (1, lines.size)))
    else
      val boundaries = headerIdxs :+ lines.size
      boundaries.sliding(2).toList.collect {
        case Vector(start, end) =>
          val heading   = lines(start).trim
          val body      = lines.slice(start + 1, end).mkString("\n").trim
          val lineStart = start + 1 // 1-indexed
          val lineEnd   = end       // 1-indexed
          RawSection(heading, body, (lineStart, lineEnd))
      }

  // ---------------------------------------------------------------------------
  // Classification — LLM assigns each section a category
  // ---------------------------------------------------------------------------

  def classify(
    sections: List[RawSection],
    backend: LlmBackend,
    model: String,
  ): IO[TriageResult] =
    // Build a summary for the LLM: heading + first ~3 lines of each section
    val summaries = sections.map { s =>
      val preview = s.content.linesIterator.take(3).mkString(" ").take(200)
      s"### ${s.heading}\n$preview"
    }.mkString("\n\n")

    for
      raw <- backend.complete(
               model = model,
               maxTokens = 2048,
               system = Some(Prompts.triageSystem),
               messages = List(ClaudeMessage("user", Prompts.triageUser(summaries))),
             )
      classifications = parseClassifications(raw)
      classified      = applyCategorizations(sections, classifications)
    yield TriageResult(classified)

  // ---------------------------------------------------------------------------
  // Full triage: split + classify (or skip if spec is small)
  // ---------------------------------------------------------------------------

  def run(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(TriageResult, Session)] =
    val nonBlankLines = session.originalSpec.linesIterator.count(_.trim.nonEmpty)
    if nonBlankLines < ActivationThreshold then
      // Small spec — treat everything as formalizable, skip LLM call
      val result = TriageResult(List(
        SpecSection(
          heading = "(entire spec)",
          content = session.originalSpec,
          category = SectionCategory.Formalizable,
          lineRange = (1, session.originalSpec.linesIterator.size),
        ),
      ))
      IO.pure((result, session.withTriage(result)))
    else
      val sections = splitSections(session.originalSpec)
      classify(sections, backend, model).map(result => (result, session.withTriage(result)))

  // ---------------------------------------------------------------------------
  // Parsing helpers
  // ---------------------------------------------------------------------------

  private val CategoryPattern = """(.+?)\s*\|\s*(formalizable|intent|infrastructure)\s*""".r

  private def parseClassifications(raw: String): Map[String, SectionCategory] =
    raw.linesIterator.collect {
      case CategoryPattern(heading, cat) =>
        val normalized = heading.trim.replaceAll("^#+\\s*", "")
        val category = cat.trim.toLowerCase match
          case "formalizable"   => SectionCategory.Formalizable
          case "intent"         => SectionCategory.Intent
          case "infrastructure" => SectionCategory.Infrastructure
          case _                => SectionCategory.Formalizable
        normalized -> category
    }.toMap

  private def applyCategorizations(
    sections: List[RawSection],
    classifications: Map[String, SectionCategory],
  ): List[SpecSection] =
    sections.map { raw =>
      // Try to match the heading — strip ## prefix for lookup
      val stripped = raw.heading.replaceAll("^#+\\s*", "")
      val category = classifications.getOrElse(
        stripped,
        // Fuzzy fallback: find a key that contains the heading or vice versa
        classifications.find {
          case (k, _) =>
            k.contains(stripped) || stripped.contains(k)
        }.map(_._2).getOrElse(SectionCategory.Formalizable),
      )
      SpecSection(
        heading = raw.heading,
        content = raw.content,
        category = category,
        lineRange = raw.lineRange,
      )
    }

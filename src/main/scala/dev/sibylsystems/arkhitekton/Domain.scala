package dev.sibylsystems.arkhitekton

/** Core domain types for the arkhitekton pipeline. */

// ---------------------------------------------------------------------------
// Typed holes reported by the Idris 2 compiler
// ---------------------------------------------------------------------------

final case class HoleInfo(
  name: String,
  goalType: String,
  context: List[String], // variables in scope, e.g. "f : Factor"
):

  def render: String =
    val ctx = context.map(c => s"   $c").mkString("\n")
    val sep = "-" * 30
    s"$ctx\n$sep\n$name : $goalType"

// ---------------------------------------------------------------------------
// Compiler result — one of three states
// ---------------------------------------------------------------------------

enum CompilerOutcome:
  case Clean
  case Holes(holes: List[HoleInfo])
  case Errors(errors: List[String])

final case class CompilerResult(
  outcome: CompilerOutcome,
  rawOutput: String,
  sourcePath: Option[os.Path],
):

  def summary: String = outcome match
    case CompilerOutcome.Clean =>
      "Compiles clean — no holes, no errors."
    case CompilerOutcome.Holes(holes) =>
      val n = holes.size
      s"Accepted with $n typed hole${if n != 1 then "s" else ""}."
    case CompilerOutcome.Errors(errors) =>
      val n = errors.size
      s"Rejected with $n error${if n != 1 then "s" else ""}."

// ---------------------------------------------------------------------------
// Pipeline iteration state
// ---------------------------------------------------------------------------

final case class Session(
  originalSpec: String,
  iteration: Int = 0,
  answers: List[String] = Nil,
  lastIdris: Option[String] = None,
  lastResult: Option[CompilerResult] = None,
  workDir: os.Path,
  metrics: List[StepTiming] = Nil,
  triageResult: Option[TriageResult] = None,
  intentContext: Option[String] = None,
  infraContext: Option[String] = None,
  modules: List[ModuleResult] = Nil,
  readiness: Option[ReadinessVerdict] = None,
  manifest: Option[DefinitionManifest] = None,
):
  def withIteration(n: Int): Session         = copy(iteration = n)
  def withIdris(code: String): Session       = copy(lastIdris = Some(code))
  def withResult(r: CompilerResult): Session = copy(lastResult = Some(r))
  def withAnswers(a: List[String]): Session  = copy(answers = answers ++ a)
  def withTiming(t: StepTiming): Session     = copy(metrics = metrics :+ t)
  def isMultiModule: Boolean                 = modules.nonEmpty

  def withReadiness(r: ReadinessVerdict): Session  = copy(readiness = Some(r))
  def withManifest(m: DefinitionManifest): Session = copy(manifest = Some(m))
  def intentSummary: Option[String]                = readiness.map(_.intentSummary)
  def specKind: Option[SpecKind]                   = readiness.map(_.kind)

  def withModules(ms: List[ModuleResult]): Session =
    copy(
      modules = ms,
      lastIdris = Some(ms.map(_.idrisSource).mkString("\n\n")),
    )

  def withTriage(t: TriageResult): Session =
    copy(
      triageResult = Some(t),
      intentContext = Option.when(t.intent.nonEmpty)(t.intentText),
      infraContext = Option.when(t.infrastructure.nonEmpty)(t.infrastructureText),
    )

  def specLineCount: Int  = originalSpec.linesIterator.size
  def idrisLineCount: Int = lastIdris.fold(0)(_.linesIterator.size)

  def renderMetrics: String =
    if metrics.isEmpty then ""
    else
      val sep       = "\u2500" * 32
      val rows      = metrics.map(_.render).mkString("\n")
      val totalSecs = metrics.map(_.durationMs).sum / 1000.0
      val outcomeStr = lastResult.map(_.outcome) match
        case Some(CompilerOutcome.Clean)     => "0 errors"
        case Some(CompilerOutcome.Holes(h))  => s"${h.size} holes"
        case Some(CompilerOutcome.Errors(e)) => s"${e.size} errors"
        case None                            => ""
      s"""$sep
         |$rows
         |$sep
         |${f"Total${" " * 19}$totalSecs%6.1fs"}
         |
         |Spec: $specLineCount lines | Idris: $idrisLineCount lines | $outcomeStr""".stripMargin

// ---------------------------------------------------------------------------
// Spec readiness assessment — gate before formalization
// ---------------------------------------------------------------------------

enum SpecKind:
  case Checker, Pipeline, DomainModel, DataTransform
  case Other(desc: String)

  def label: String = this match
    case Checker       => "checker"
    case Pipeline      => "pipeline"
    case DomainModel   => "domain-model"
    case DataTransform => "data-transform"
    case Other(d)      => s"other: $d"

enum ReadinessVerdict:
  case Ready(intent: String, specKind: SpecKind)
  case NotReady(questions: List[String], intent: String, specKind: SpecKind)

  def intentSummary: String = this match
    case Ready(s, _)       => s
    case NotReady(_, s, _) => s

  def kind: SpecKind = this match
    case Ready(_, k)       => k
    case NotReady(_, _, k) => k

  def isReady: Boolean = this match
    case Ready(_, _)       => true
    case NotReady(_, _, _) => false

// ---------------------------------------------------------------------------
// Definition manifest — ordered list of definitions extracted from spec
// ---------------------------------------------------------------------------

enum DefinitionKind:
  case Enum, Record, Function, TypeAlias

  def label: String = this match
    case Enum      => "enum"
    case Record    => "record"
    case Function  => "function"
    case TypeAlias => "type-alias"

final case class DefinitionEntry(
  name: String,
  kind: DefinitionKind,
  dependsOn: List[String],
  description: String,
)

final case class DefinitionManifest(
  entries: List[DefinitionEntry],
):
  def render: String =
    entries.zipWithIndex.map { case (e, i) =>
      val deps = if e.dependsOn.isEmpty then "(none)" else e.dependsOn.mkString(", ")
      s"  ${i + 1}. ${e.name} [${e.kind.label}] depends: $deps\n     ${e.description}"
    }.mkString("\n")

  /** Format as instructions for the formalize prompt. */
  def asOrderDirective: String =
    val lines = entries.zipWithIndex.map { case (e, i) =>
      s"${i + 1}. ${e.name} [${e.kind.label}] — ${e.description}"
    }
    s"""## Definition Order (mandatory)
       |
       |Produce definitions in EXACTLY this order. Each name must be defined before
       |any name below it can reference it. Do not reorder, skip, or add definitions.
       |
       |${lines.mkString("\n")}""".stripMargin

// ---------------------------------------------------------------------------
// Spec triage — classify sections before formalization
// ---------------------------------------------------------------------------

enum SectionCategory:
  case Formalizable
  case Intent
  case Infrastructure

final case class SpecSection(
  heading: String,
  content: String,
  category: SectionCategory,
  lineRange: (Int, Int),
)

final case class TriageResult(
  sections: List[SpecSection],
):
  def formalizable: List[SpecSection]   = sections.filter(_.category == SectionCategory.Formalizable)
  def intent: List[SpecSection]         = sections.filter(_.category == SectionCategory.Intent)
  def infrastructure: List[SpecSection] = sections.filter(_.category == SectionCategory.Infrastructure)

  def formalizableText: String   = formalizable.map(s => s"${s.heading}\n\n${s.content}").mkString("\n\n---\n\n")
  def intentText: String         = intent.map(s => s"${s.heading}\n\n${s.content}").mkString("\n\n")
  def infrastructureText: String = infrastructure.map(s => s"${s.heading}\n\n${s.content}").mkString("\n\n")

  def renderSummary: String =
    sections.map { s =>
      val tag = s.category match
        case SectionCategory.Formalizable   => "FORMAL"
        case SectionCategory.Intent         => "INTENT"
        case SectionCategory.Infrastructure => "INFRA "
      s"  [$tag] ${s.heading} (lines ${s.lineRange._1}-${s.lineRange._2})"
    }.mkString("\n")

// ---------------------------------------------------------------------------
// Multi-module planning
// ---------------------------------------------------------------------------

final case class ModulePlan(
  moduleName: String,
  sections: List[SpecSection],
  dependsOn: List[String],
)

final case class ModuleResult(
  moduleName: String,
  idrisSource: String,
)

// ---------------------------------------------------------------------------
// Metrics
// ---------------------------------------------------------------------------

final case class StepTiming(stepName: String, durationMs: Long):

  def render: String =
    val secs = durationMs / 1000.0
    f"$stepName%-24s $secs%6.1fs"

// ---------------------------------------------------------------------------
// Transpile pipeline results
// ---------------------------------------------------------------------------

final case class TranspileResult(
  jsPath: os.Path,
  dtsPath: os.Path,
  adapterPath: os.Path,
  exportedFunctions: List[String],
)

final case class TestResult(
  testPath: os.Path,
  passed: Int,
  failed: Int,
  rawOutput: String,
):

  def summary: String =
    if failed == 0 then s"All $passed tests passed."
    else s"$failed failed, $passed passed."

// ---------------------------------------------------------------------------
// Claude API types
// ---------------------------------------------------------------------------

final case class ClaudeMessage(role: String, content: String)

final case class ClaudeRequest(
  model: String,
  maxTokens: Int,
  system: Option[String],
  messages: List[ClaudeMessage],
)

final case class ContentBlock(`type`: String, text: String)
final case class ClaudeResponse(content: List[ContentBlock])

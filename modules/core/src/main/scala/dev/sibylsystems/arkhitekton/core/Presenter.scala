package dev.sibylsystems.arkhitekton.core

/** Decouples pipeline orchestration from UI rendering.
  *
  * CLI implements this with IO.println + StdIn; web implements it with WebSocket events + Deferred.
  */
trait Presenter[F[_]]:

  /** Pipeline step started. */
  def stepStarted(step: String, label: String): F[Unit]

  /** Inline progress message (e.g., "Translating spec..."). */
  def progress(step: String, message: String): F[Unit]

  /** Step completed with timing. */
  def stepCompleted(step: String, durationMs: Long): F[Unit]

  /** Step failed. */
  def stepFailed(step: String, error: String): F[Unit]

  /** An artifact was produced (Idris source, compiler output, etc.). */
  def artifact(kind: ArtifactKind, name: String, content: String): F[Unit]

  /** Pipeline produced questions (typed holes -> English). Collect answers. */
  def askQuestions(questions: List[String]): F[Option[List[String]]]

  /** Pipeline produced a final result (back-translation, error explanation). */
  def result(title: String, outcome: CompilerOutcome, explanation: String): F[Unit]

  /** Informational message (next steps, summaries). */
  def info(message: String): F[Unit]

  /** Display readiness assessment result. */
  def readinessResult(verdict: ReadinessVerdict): F[Unit]

  /** Display metrics summary. */
  def metrics(session: Session): F[Unit]

  /** Display next steps guidance based on outcome. */
  def nextSteps(outcome: CompilerOutcome, workDir: os.Path): F[Unit]

enum ArtifactKind:
  case IdrisSource, CompilerOutput, RefinedSpec, JavaScript,
    TypeDefinitions, Adapter, TestFile, TestOutput, Manifest, Triage,
    ModulePlan, AutoFix, Explanation

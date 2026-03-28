package dev.sibylsystems.arkhitekton.server

import cats.effect.IO
import cats.effect.std.Queue

import dev.sibylsystems.arkhitekton.core.*
import dev.sibylsystems.arkhitekton.protocol.*

/** Presenter that pushes PipelineEvent values into a Queue for SSE streaming. */
class StreamPresenter(queue: Queue[IO, Option[PipelineEvent]]) extends Presenter[IO]:

  private def emit(event: PipelineEvent): IO[Unit] =
    queue.offer(Some(event))

  def stepStarted(step: String, label: String): IO[Unit] =
    emit(PipelineEvent.StepStarted(step, label))

  def progress(step: String, message: String): IO[Unit] =
    emit(PipelineEvent.StepProgress(step, message))

  def stepCompleted(step: String, durationMs: Long): IO[Unit] =
    emit(PipelineEvent.StepCompleted(step, durationMs))

  def stepFailed(step: String, error: String): IO[Unit] =
    emit(PipelineEvent.StepFailed(step, error))

  def artifact(kind: ArtifactKind, name: String, content: String): IO[Unit] =
    emit(PipelineEvent.ArtifactReady(kind.toString, name, content))

  def askQuestions(questions: List[String]): IO[Option[List[String]]] =
    // In the Streamable HTTP model, questions end the current stream.
    // The client will POST answers as a new request.
    emit(PipelineEvent.QuestionsReady(questions)) *>
      emit(PipelineEvent.StreamEnd(true)) *>
      IO.pure(None) // signals "stream done, wait for next POST"

  def result(title: String, outcome: CompilerOutcome, explanation: String): IO[Unit] =
    val outcomeStr = outcome match
      case CompilerOutcome.Clean     => "clean"
      case CompilerOutcome.Holes(_)  => "holes"
      case CompilerOutcome.Errors(_) => "errors"
    emit(PipelineEvent.ResultReady(outcomeStr, title, explanation))

  def info(message: String): IO[Unit] =
    emit(PipelineEvent.Info(message))

  def readinessResult(verdict: ReadinessVerdict): IO[Unit] =
    val (ready, questions) = verdict match
      case ReadinessVerdict.Ready(_, _)          => (true, Nil)
      case ReadinessVerdict.NotReady(qs, _, _)   => (false, qs)
    emit(PipelineEvent.ReadinessResult(
      ready = ready,
      kind = verdict.kind.label,
      intent = verdict.intentSummary,
      questions = questions,
    ))

  def metrics(session: Session): IO[Unit] =
    val entries = session.metrics.map(t => TimingEntry(t.stepName, t.durationMs))
    IO.whenA(entries.nonEmpty)(emit(PipelineEvent.SessionMetrics(entries)))

  def nextSteps(outcome: CompilerOutcome, workDir: os.Path): IO[Unit] =
    IO.unit // web client handles next-steps UI based on outcome

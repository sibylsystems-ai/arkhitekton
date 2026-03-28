package dev.sibylsystems.arkhitekton.server

import cats.effect.{IO, Ref}
import cats.effect.std.Queue

import fs2.Stream

import dev.sibylsystems.arkhitekton.core.*
import dev.sibylsystems.arkhitekton.protocol.*

/** Manages pipeline sessions and converts ClientCommands into PipelineEvent streams. */
class PipelineHandler(
  backend: LlmBackend,
  model: String,
  idris2: String,
  sessionRef: Ref[IO, Option[Session]],
):

  /** Execute a command and return a stream of events.
    * The stream terminates when the pipeline step completes or pauses for input.
    */
  def handle(command: ClientCommand): Stream[IO, PipelineEvent] =
    Stream.eval(Queue.unbounded[IO, Option[PipelineEvent]]).flatMap { queue =>
      val presenter = StreamPresenter(queue)
      val events    = Stream.fromQueueNoneTerminated(queue)

      val work = command match
        case ClientCommand.StartAnalysis(spec, mode) =>
          startAnalysis(spec, mode, presenter, queue)
        case ClientCommand.SubmitAnswers(answers) =>
          submitAnswers(answers, presenter, queue)
        case ClientCommand.RunTranspile =>
          runTranspile(presenter, queue)
        case ClientCommand.RunSingleStep(step) =>
          runSingleStep(step, presenter, queue)
        case ClientCommand.Cancel =>
          queue.offer(Some(PipelineEvent.Info("Cancelled."))) *>
            queue.offer(Some(PipelineEvent.StreamEnd(false))) *>
            queue.offer(None)

      // Run the pipeline work concurrently; the event stream drains as events are produced.
      events.concurrently(Stream.eval(work))
    }

  private def startAnalysis(
    spec: String,
    mode: AnalysisMode,
    presenter: StreamPresenter,
    queue: Queue[IO, Option[PipelineEvent]],
  ): IO[Unit] =
    for
      workDir <- IO.blocking(os.Path(os.temp.dir(prefix = "arkhitekton-").toIO.getCanonicalPath))
      session  = Session(originalSpec = spec, workDir = workDir)
      _       <- StateIO.saveSpec(workDir, spec)
      _       <- sessionRef.set(Some(session))
      _       <- runPipeline(session, mode, presenter, queue)
    yield ()

  private def submitAnswers(
    answers: List[String],
    presenter: StreamPresenter,
    queue: Queue[IO, Option[PipelineEvent]],
  ): IO[Unit] =
    for
      sessionOpt <- sessionRef.get
      session    <- IO.fromOption(sessionOpt)(new RuntimeException("No active session"))
      updated     = session.withAnswers(answers)
      _          <- sessionRef.set(Some(updated))
      _          <- runPipeline(updated, AnalysisMode.Once, presenter, queue)
    yield ()

  private def runTranspile(
    presenter: StreamPresenter,
    queue: Queue[IO, Option[PipelineEvent]],
  ): IO[Unit] =
    for
      sessionOpt <- sessionRef.get
      session    <- IO.fromOption(sessionOpt)(new RuntimeException("No active session"))
      result     <- Orchestrator.transpile(session, backend, model, idris2, presenter)
                      .handleErrorWith { e =>
                        queue.offer(Some(PipelineEvent.Error(e.getMessage))).as((session, -1))
                      }
      (updated, failed) = result
      _ <- sessionRef.set(Some(updated))
      _ <- queue.offer(Some(PipelineEvent.StreamEnd(failed == 0)))
      _ <- queue.offer(None)
    yield ()

  private def runSingleStep(
    step: String,
    presenter: StreamPresenter,
    queue: Queue[IO, Option[PipelineEvent]],
  ): IO[Unit] =
    for
      sessionOpt <- sessionRef.get
      session    <- IO.fromOption(sessionOpt)(new RuntimeException("No active session"))
      exitCode   <- Orchestrator.singleStep(step, session, backend, model, idris2, presenter)
                      .handleErrorWith { e =>
                        queue.offer(Some(PipelineEvent.Error(e.getMessage)))
                          .as(cats.effect.ExitCode.Error)
                      }
      _ <- queue.offer(Some(PipelineEvent.StreamEnd(exitCode.code == 0)))
      _ <- queue.offer(None)
    yield ()

  private def runPipeline(
    session: Session,
    mode: AnalysisMode,
    presenter: StreamPresenter,
    queue: Queue[IO, Option[PipelineEvent]],
  ): IO[Unit] =
    val pipeline = mode match
      case AnalysisMode.Once | AnalysisMode.Interactive =>
        Orchestrator.oneIteration(session, backend, model, idris2, presenter)
          .flatMap(s => sessionRef.set(Some(s)).as(s))
      case AnalysisMode.Transpile =>
        Orchestrator.transpile(session, backend, model, idris2, presenter)
          .flatMap { case (s, _) => sessionRef.set(Some(s)).as(s) }

    pipeline
      .flatMap(_ => queue.offer(Some(PipelineEvent.StreamEnd(true))))
      .handleErrorWith(e => queue.offer(Some(PipelineEvent.Error(e.getMessage))) *>
        queue.offer(Some(PipelineEvent.StreamEnd(false))))
      .flatMap(_ => queue.offer(None))

package dev.sibylsystems.arkhitekton.cli

import cats.effect.{ExitCode, IO}

import dev.sibylsystems.arkhitekton.core.*

/** Execution modes for the arkhitekton CLI. Delegates core logic to Orchestrator. */
object Runner:

  private val presenter = CliPresenter()

  def once(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    Orchestrator.oneIteration(session, backend, model, idris2, presenter).as(ExitCode.Success)

  def transpile(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    Orchestrator.transpile(session, backend, model, idris2, presenter).map {
      case (_, failed) if failed == 0 => ExitCode.Success
      case _                          => ExitCode.Error
    }

  def interactive(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    def loop(s: Session): IO[ExitCode] =
      for
        updated <- Orchestrator.oneIteration(s, backend, model, idris2, presenter)
        isDone   = updated.lastResult.exists(_.outcome == CompilerOutcome.Clean)
        exitCode <- if isDone then IO.pure(ExitCode.Success)
                    else
                      menu(updated).flatMap {
                        case Some(next) => loop(next)
                        case None       => IO.pure(ExitCode.Success)
                      }
      yield exitCode
    loop(session)

  def singleStep(
    step: String,
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[ExitCode] =
    Orchestrator.singleStep(step, session, backend, model, idris2, presenter)

  // ---------------------------------------------------------------------------
  // Interactive menu (CLI-specific)
  // ---------------------------------------------------------------------------

  private val menuText: String =
    """
      |What would you like to do?
      |  [a] Type your responses to the questions above, then re-run
      |  [v] Show raw compiler output
      |  [q] Quit
      |""".stripMargin

  private def menu(session: Session): IO[Option[Session]] =
    def loop: IO[Option[Session]] =
      for
        _     <- IO.println(s"  \ud83d\udca1 Full formalization saved at: ${session.workDir / "Spec.idr"}")
        _     <- IO.println(menuText)
        _     <- IO.print("> ")
        input <- IO.blocking(Option(scala.io.StdIn.readLine()).getOrElse("q").trim.toLowerCase)
        result <- input match
                    case "a" => collectAnswers(session).map(Some(_))
                    case "v" =>
                      IO.println(Orchestrator.header("Raw Compiler Output")) *>
                        IO.println(session.lastResult.map(_.rawOutput).getOrElse("(none)")) *> loop
                    case "q" => IO.pure(None)
                    case _   => IO.println("  Unknown choice. Try again.") *> loop
      yield result
    loop

  private def collectAnswers(session: Session): IO[Session] =
    IO.println("\nType your responses below — one per line, blank line when done:") *>
      readLines.flatMap { lines =>
        if lines.nonEmpty then
          IO.println(s"  Got ${lines.size} response(s). Re-running...").as(session.withAnswers(lines))
        else IO.pure(session)
      }

  private def readLines: IO[List[String]] =
    def loop(acc: List[String]): IO[List[String]] =
      IO.print("  ") *>
        IO.blocking(scala.io.StdIn.readLine()).flatMap { line =>
          if Option(line).forall(_.trim.isEmpty) then IO.pure(acc.reverse)
          else loop(line :: acc)
        }
    loop(Nil)

package dev.sibylsystems.arkhitekton.cli

import cats.effect.IO
import cats.syntax.all.*

import dev.sibylsystems.arkhitekton.core.*

/** CLI implementation of the Presenter trait — uses IO.println/IO.print for terminal output. */
class CliPresenter extends Presenter[IO]:

  def stepStarted(step: String, label: String): IO[Unit] =
    IO.print(s"  $label...")

  def progress(step: String, message: String): IO[Unit] =
    IO.print(s"  $message")

  def stepCompleted(step: String, durationMs: Long): IO[Unit] =
    IO.println(" done \u2713")

  def stepFailed(step: String, error: String): IO[Unit] =
    IO.println(s" failed: $error")

  def artifact(kind: ArtifactKind, name: String, content: String): IO[Unit] =
    IO.unit // CLI doesn't show artifacts inline — they're logged to workdir

  def askQuestions(questions: List[String]): IO[Option[List[String]]] =
    IO.println("\nType your responses below — one per line, blank line when done:") *>
      readLines.flatMap { lines =>
        if lines.nonEmpty then
          IO.println(s"  Got ${lines.size} response(s). Re-running...").as(Some(lines))
        else IO.pure(Some(Nil))
      }

  def result(title: String, outcome: CompilerOutcome, explanation: String): IO[Unit] =
    IO.println(Orchestrator.header(title)) *> IO.println(explanation)

  def info(message: String): IO[Unit] =
    IO.println(message)

  def readinessResult(verdict: ReadinessVerdict): IO[Unit] =
    val kindStr   = verdict.kind.label
    val intentStr = verdict.intentSummary
    verdict match
      case ReadinessVerdict.Ready(_, _) =>
        IO.println(Terminal.box("Spec Assessment", List(
          Terminal.label("Kind", kindStr),
          Terminal.label("Intent", intentStr),
          "",
          s"  ${Terminal.green("READY")} — spec has enough detail to formalize.",
        )))
      case ReadinessVerdict.NotReady(questions, _, _) =>
        IO.println(Terminal.box("Spec Assessment", List(
          Terminal.label("Kind", kindStr),
          Terminal.label("Intent", intentStr),
          "",
          s"  ${Terminal.yellow("NOT READY")}",
        ))) *>
          IO.println("\n  The spec needs more concrete detail before it can be formalized.") *>
          IO.println("  Please address these questions and update your spec:\n") *>
          questions.zipWithIndex.traverse_ { case (q, i) =>
            IO.println(Terminal.numberedItem(i + 1, q))
          } *>
          IO.println(s"\n  After updating, re-run:") *>
          IO.println(s"    arkhitekton <updated-spec-file>")

  def metrics(session: Session): IO[Unit] =
    val rendered = session.renderMetrics
    IO.whenA(rendered.nonEmpty)(IO.println(rendered))

  def nextSteps(outcome: CompilerOutcome, workDir: os.Path): IO[Unit] =
    val wd = workDir.toString
    outcome match
      case CompilerOutcome.Clean =>
        IO.println(s"\n  To run the full transpile pipeline:") *>
          IO.println(s"    arkhitekton <spec-file> --transpile --workdir $wd")
      case CompilerOutcome.Holes(_) =>
        IO.println(s"\n  Next steps:") *>
          IO.println(s"    Answer the questions above, then re-run:") *>
          IO.println(s"      arkhitekton <spec-file> --workdir $wd") *>
          IO.println(s"    Or run interactively:") *>
          IO.println(s"      arkhitekton <spec-file>")
      case CompilerOutcome.Errors(_) =>
        IO.println(s"\n  Next steps:") *>
          IO.println(s"    Option A — Update the spec and re-run from the beginning:") *>
          IO.println(s"      arkhitekton <spec-file> --once") *>
          IO.println(s"    Option B — Edit the Idris files directly and recompile:") *>
          IO.println(s"      ${"$EDITOR"} $wd/Spec/*.idr") *>
          IO.println(s"      arkhitekton <spec-file> --step compile --workdir $wd") *>
          IO.println(s"    Option C — Re-run formalize (fresh LLM attempt) keeping triage + plan:") *>
          IO.println(s"      arkhitekton <spec-file> --step formalize --workdir $wd")

  // ---------------------------------------------------------------------------
  // Private
  // ---------------------------------------------------------------------------

  private def readLines: IO[List[String]] =
    def loop(acc: List[String]): IO[List[String]] =
      IO.print("  ") *>
        IO.blocking(scala.io.StdIn.readLine()).flatMap { line =>
          if Option(line).forall(_.trim.isEmpty) then IO.pure(acc.reverse)
          else loop(line :: acc)
        }
    loop(Nil)

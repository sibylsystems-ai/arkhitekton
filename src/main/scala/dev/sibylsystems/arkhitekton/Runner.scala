package dev.sibylsystems.arkhitekton

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*

/** Execution modes for the arkhitekton pipeline. */
object Runner:

  def once(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    oneIteration(session, backend, model, idris2).as(ExitCode.Success)

  def transpile(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    for
      s1     <- oneIteration(session, backend, model, idris2)
      outcome = s1.lastResult.map(_.outcome).getOrElse(CompilerOutcome.Clean)
      exitCode <- outcome match
                    case CompilerOutcome.Clean =>
                      for
                        _      <- IO.println(header("Transpile Pipeline"))
                        _      <- IO.println("[Step 4/7] Filling typed holes...")
                        result <- Pipeline.runTranspile(s1, backend, model, idris2)
                        _      <- IO.println("[Step 7/7] Running tests...")
                        _      <- IO.println(divider)
                        _      <- IO.println(result.testResult.rawOutput)
                        _      <- IO.println(divider)
                        _      <- IO.println(s"  ${result.testResult.summary}")
                      yield if result.testResult.failed == 0 then ExitCode.Success else ExitCode.Error
                    case _ =>
                      IO.println("Spec did not compile clean — cannot transpile. Fix the spec issues first.")
                        .as(ExitCode.Error)
    yield exitCode

  def interactive(session: Session, backend: LlmBackend, model: String, idris2: String): IO[ExitCode] =
    def loop(s: Session): IO[ExitCode] =
      for
        updated <- oneIteration(s, backend, model, idris2)
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
    step match
      case "readiness" =>
        for
          _               <- StateIO.saveSpec(session.workDir, session.originalSpec)
          (verdict, _)    <- Readiness.assess(session, backend, Models.fast)
          _               <- StateIO.saveReadiness(session.workDir, verdict)
          _               <- printReadinessResult(verdict)
        yield if verdict.isReady then ExitCode.Success else ExitCode.Error

      case "triage" =>
        for
          _       <- StateIO.saveSpec(session.workDir, session.originalSpec)
          (tr, _) <- Triage.run(session, backend, Models.fast)
          _       <- StateIO.saveTriage(session.workDir, tr)
          _       <- IO.println(tr.renderSummary)
          _ <- IO.println(
                 s"\n${tr.formalizable.size} formalizable, ${tr.intent.size} intent, ${tr.infrastructure.size} infra",
               )
        yield ExitCode.Success

      case "plan" =>
        for
          s0 <- ensurePriorStep(session, "triage")
          triage <- s0.triageResult.fold(IO.raiseError[TriageResult](
                      new RuntimeException("No triage result. Run --step triage first."),
                    ))(IO.pure)
          plans <- ModulePlanner.plan(triage, backend, Models.fast)
          _     <- StateIO.saveModulePlan(session.workDir, plans)
          _ <- if plans.isEmpty then IO.println("Single module sufficient — no splitting needed.")
               else
                 IO.println(s"${plans.size} modules planned:") *>
                   plans.traverse_(p => IO.println(s"  ${p.moduleName} depends on [${p.dependsOn.mkString(", ")}]"))
        yield ExitCode.Success

      case "decompose" =>
        for
          s0 <- ensurePriorStep(session, "triage")
          specText = s0.triageResult.map(_.formalizableText).getOrElse(session.originalSpec)
          manifest <- Decompose.run(specText, backend, Models.fast)
          _        <- IO.println(s"${manifest.entries.size} definitions extracted:\n")
          _        <- IO.println(manifest.render)
        yield ExitCode.Success

      case "formalize" =>
        for
          s0 <- ensurePriorStep(session, "plan")
          plans <- s0.triageResult.fold(IO.pure(List.empty[ModulePlan]))(tr =>
                     StateIO.loadModulePlan(session.workDir, tr).map(_.getOrElse(Nil)),
                   )
          (s1, t) <- Metrics.timed(if plans.nonEmpty then s"Formalize (${plans.size} modules)" else "Formalize") {
                       if plans.nonEmpty then
                         Pipeline.formalizeMultiModule(s0, plans, backend, model).map(_._2)
                       else
                         Pipeline.formalize(s0, backend, model).map(_._2)
                     }
          // Write .idr files to the work directory so compile can find them
          _ <- s1.modules match
                 case Nil =>
                   val code = s1.lastIdris.getOrElse("")
                   log(session.workDir, "03-formalize.idr", code) *>
                     IO.blocking(os.write.over(session.workDir / "Spec.idr", code))
                 case ms =>
                   val pairs = ms.map(m => (m.moduleName, m.idrisSource))
                   Compiler.writeModules(pairs, session.workDir) *>
                     Compiler.writeIpkg(ms.map(_.moduleName), session.workDir) *>
                     ms.zipWithIndex.traverse_ {
                       case (m, i) =>
                         log(session.workDir, f"03-formalize-${i + 1}%02d-${m.moduleName}.idr", m.idrisSource)
                     } *> StateIO.saveModules(session.workDir, ms)
          _ <- IO.println(s"Formalize done (${t.render})")
          _ <- IO.println(s"Idris: ${s1.idrisLineCount} lines")
        yield ExitCode.Success

      case "compile" | "check" =>
        for
          s0          <- ensurePriorStep(session, "formalize")
          (result, _) <- Pipeline.compile(s0, idris2)
          _           <- StateIO.saveCompileResult(session.workDir, result)
          _           <- log(session.workDir, "04-compile.txt", s"outcome: ${result.summary}\n\n${result.rawOutput}")
          _           <- IO.println(result.summary)
          _ <- result.outcome match
                 case CompilerOutcome.Errors(errs) => errs.traverse_(e => IO.println(s"\n$e"))
                 case CompilerOutcome.Holes(holes) => holes.traverse_(h => IO.println(s"\n${h.render}"))
                 case CompilerOutcome.Clean        => IO.unit
        yield if result.outcome == CompilerOutcome.Clean then ExitCode.Success else ExitCode.Error

      case "autofix" =>
        for
          s0 <- ensurePriorStep(session, "compile")
          originalErrors = s0.lastResult match
                             case Some(CompilerResult(CompilerOutcome.Errors(e), _, _)) => e.size
                             case _                                                     => 0
          _ <- IO.whenA(originalErrors == 0)(
                 IO.raiseError(new RuntimeException("No compilation errors to fix. Run --step compile first.")),
               )
          (result, _) <- Pipeline.autoFix(s0, backend, model, idris2)
          newErrors = result.outcome match
                        case CompilerOutcome.Errors(e) => e.size
                        case _                         => 0
          improved = newErrors < originalErrors
          _ <- IO.whenA(improved)(
                 StateIO.saveCompileResult(session.workDir, result) *>
                   IO.println(s"After auto-fix: ${result.summary} (was $originalErrors errors)"),
               )
          _ <- IO.whenA(!improved) {
                 val restore =
                   if s0.isMultiModule then
                     val pairs = s0.modules.map(m => (m.moduleName, m.idrisSource))
                     Compiler.writeModules(pairs, session.workDir).void
                   else s0.lastIdris.traverse_(code => IO.blocking(os.write.over(session.workDir / "Spec.idr", code)))
                 restore *> IO.println(
                   s"Auto-fix did not help (still $originalErrors errors). Original code preserved.",
                 )
               }
        yield if result.outcome == CompilerOutcome.Clean then ExitCode.Success else ExitCode.Error

      case "explain" =>
        for
          s0    <- ensurePriorStep(session, "compile")
          result = s0.lastResult.getOrElse(CompilerResult(CompilerOutcome.Clean, "", None))
          explanation <- result.outcome match
                           case CompilerOutcome.Clean     => Pipeline.backTranslate(s0, backend, Models.fast)
                           case CompilerOutcome.Holes(_)  => Pipeline.explainHoles(s0, backend, Models.fast)
                           case CompilerOutcome.Errors(_) => Pipeline.explainErrors(s0, backend, Models.fast)
          _ <- IO.println(explanation)
        yield ExitCode.Success

      case "back-translate" =>
        for
          s0          <- ensurePriorStep(session, "compile")
          explanation <- Pipeline.backTranslate(s0, backend, Models.fast)
          _           <- IO.println(explanation)
        yield ExitCode.Success

      case other =>
        IO.println(
          s"""Unknown step: $other
             |Available steps: readiness, triage, plan, decompose, formalize, compile, autofix, explain, back-translate""".stripMargin,
        ).as(ExitCode.Error)

  /** Load prior state from workdir, or use the session as-is if it already has the needed data. */
  private def ensurePriorStep(session: Session, priorStep: String): IO[Session] =
    priorStep match
      case "readiness" =>
        if session.readiness.isDefined then IO.pure(session)
        else
          StateIO.loadReadiness(session.workDir).flatMap {
            case Some(r) => IO.pure(session.withReadiness(r))
            case None    => IO.pure(session) // no readiness = old workdir, fine
          }
      case "triage" =>
        for
          s0 <- ensurePriorStep(session, "readiness")
          s1 <- if s0.triageResult.isDefined then IO.pure(s0)
                else
                  StateIO.loadTriage(s0.workDir).flatMap {
                    case Some(tr) => IO.pure(s0.withTriage(tr))
                    case None     => IO.pure(s0) // no triage = small spec, fine
                  }
        yield s1
      case "plan" =>
        ensurePriorStep(session, "triage") // plan depends on triage
      case "formalize" =>
        for
          s0 <- ensurePriorStep(session, "plan")
          s1 <- if s0.lastIdris.isDefined then IO.pure(s0)
                else
                  StateIO.loadModules(session.workDir).map {
                    case Some(ms) if ms.nonEmpty => s0.withModules(ms)
                    case _ =>
                      val p = session.workDir / "Spec.idr"
                      if os.exists(p) then s0.withIdris(os.read(p)) else s0
                  }
        yield s1
      case "compile" =>
        for
          s0 <- ensurePriorStep(session, "formalize")
          // Recompile to get the result if we don't have it
          s1 <- if s0.lastResult.isDefined then IO.pure(s0)
                else if s0.lastIdris.isDefined || s0.isMultiModule then
                  Pipeline.compile(s0, "idris2").map(_._2)
                else
                  IO.raiseError(new RuntimeException("No Idris source found. Run --step formalize first."))
        yield s1
      case _ => IO.pure(session)

  // ---------------------------------------------------------------------------
  // Readiness helper
  // ---------------------------------------------------------------------------

  private def runReadiness(
    session: Session,
    backend: LlmBackend,
  ): IO[(Session, StepTiming)] =
    if session.readiness.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Readiness", 0)))
    else
      for
        _                <- IO.print(s"  Assessing spec readiness...")
        ((verdict, s), t) <- Metrics.timed("Readiness")(Readiness.assess(session, backend, Models.fast))
        _                <- StateIO.saveReadiness(session.workDir, verdict)
        _ <- verdict match
               case ReadinessVerdict.Ready(_, kind) =>
                 IO.println(Terminal.ok(s" ready") + Terminal.dim(s" (${kind.label})"))
               case ReadinessVerdict.NotReady(_, _, kind) =>
                 IO.println(Terminal.warn(s" not ready") + Terminal.dim(s" (${kind.label})"))
      yield (s, t)

  private def printReadinessResult(verdict: ReadinessVerdict): IO[Unit] =
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

  // ---------------------------------------------------------------------------
  // Decompose helper
  // ---------------------------------------------------------------------------

  private def runDecompose(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(Session, StepTiming)] =
    if session.manifest.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Decompose", 0)))
    else
      val specText = session.triageResult.map(_.formalizableText).getOrElse(session.originalSpec)
      for
        _              <- IO.print("  Extracting definition structure...")
        (manifest, t)  <- Metrics.timed("Decompose")(Decompose.run(specText, backend, model))
        s1              = session.withManifest(manifest)
        _              <- IO.println(Terminal.ok(s" done") + Terminal.dim(s" (${manifest.entries.size} definitions)"))
      yield (s1, t)

  // ---------------------------------------------------------------------------
  // Triage helper
  // ---------------------------------------------------------------------------

  private def runTriage(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(Session, StepTiming)] =
    if session.triageResult.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Triage", 0)))
    else
      for
        _           <- IO.print("  Analyzing spec structure...")
        ((_, s), t) <- Metrics.timed("Triage")(Triage.run(session, backend, model))
        _           <- s.triageResult.traverse_(tr => StateIO.saveTriage(session.workDir, tr))
        _ <- s.triageResult match
               case Some(tr) if tr.sections.size > 1 =>
                 IO.println(s" done ✓ (${tr.formalizable.size} formalizable, " +
                   s"${tr.intent.size} intent, ${tr.infrastructure.size} infra)")
               case _ =>
                 IO.println(" skipped (small spec)")
      yield (s, t)

  // ---------------------------------------------------------------------------
  // Module planning helper
  // ---------------------------------------------------------------------------

  private def planModules(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(List[ModulePlan], StepTiming)] =
    session.triageResult match
      case Some(tr) if tr.formalizable.size >= ModulePlanner.MinSectionsForSplit =>
        for
          _          <- IO.print("  Planning module structure...")
          (plans, t) <- Metrics.timed("Plan modules")(ModulePlanner.plan(tr, backend, model))
          msg = if plans.nonEmpty then s" done ✓ (${plans.size} modules)"
                else " single module sufficient"
          _ <- IO.println(msg)
        yield (plans, t)
      case _ =>
        IO.pure((Nil, StepTiming("Plan modules", 0)))

  // ---------------------------------------------------------------------------
  // Shared iteration
  // ---------------------------------------------------------------------------

  private def oneIteration(session: Session, backend: LlmBackend, model: String, idris2: String): IO[Session] =
    val iter = session.iteration + 1
    val s0   = session.withIteration(iter)
    for
      _ <- IO.println(header(s"Pass $iter"))
      // Readiness gate (only on first iteration)
      (s0r, tReady) <- runReadiness(s0, backend)
      result <- s0r.readiness match
                  case Some(v) if !v.isReady =>
                    val s1 = s0r.withTiming(tReady)
                    printReadinessResult(v) *>
                      IO.println(s1.renderMetrics) *>
                      IO.pure(s1)
                  case _ =>
                    continueIteration(s0r, tReady, backend, model, idris2)
    yield result

  private def continueIteration(
    s0: Session,
    tReady: StepTiming,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[Session] =
    val fast = Models.fast
    for
      // Triage: classify sections (only on first iteration, only for large specs)
      (s0t, tTriage) <- runTriage(s0, backend, fast)
      _              <- s0t.triageResult.fold(IO.unit)(tr => log(s0.workDir, "01-triage.txt", tr.renderSummary))
      // Plan modules (only if triage found enough formalizable sections)
      (plans, tPlan) <- planModules(s0t, backend, fast)
      _ <- log(
             s0.workDir,
             "02-module-plan.txt",
             if plans.isEmpty then "(single module)"
             else
               plans.map(p =>
                 s"${p.moduleName} | deps: ${p.dependsOn.mkString(", ")} | sections: ${p.sections.map(_.heading).mkString(", ")}",
               ).mkString("\n"),
           )
      // Decompose: extract definition manifest and topologically sort
      (s0d, tDecomp) <- runDecompose(s0t, backend, fast)
      _ <- s0d.manifest.traverse_(m => log(s0.workDir, "02b-manifest.txt", m.render))
      // Formalize: single-module or multi-module parallel
      _ <- IO.print(
             if plans.nonEmpty then
               s"  Translating spec to ${plans.size} formal modules (parallel)..."
             else
               "  Translating your spec to a formal model (this may take a moment)...",
           )
      (s1, tForm) <- Metrics.timed(if plans.nonEmpty then s"Formalize (${plans.size} modules)" else "Formalize") {
                       if plans.nonEmpty then
                         Pipeline.formalizeMultiModule(s0d, plans, backend, model).map(_._2)
                       else
                         Pipeline.formalize(s0d, backend, model).map(_._2)
                     }
      _ <- IO.println(" done ✓")
      _ <- s1.modules match
             case Nil => log(s0.workDir, "03-formalize.idr", s1.lastIdris.getOrElse(""))
             case ms => ms.zipWithIndex.traverse_ {
                 case (m, i) =>
                   log(s0.workDir, f"03-formalize-${i + 1}%02d-${m.moduleName}.idr", m.idrisSource)
               }
      _                     <- IO.print("  Checking for consistency and gaps...")
      ((result, s2), tComp) <- Metrics.timed("Compile")(Pipeline.compile(s1, idris2))
      _                     <- IO.println(" done ✓")
      _                     <- log(s0.workDir, "04-compile.txt", s"outcome: ${result.summary}\n\n${result.rawOutput}")
      // If compilation failed, let the LLM fix its own structural mistakes first
      ((finalResult, s2a), tFix) <- result.outcome match
                                      case CompilerOutcome.Errors(_) =>
                                        IO.print("  Fixing structural issues...") *>
                                          Metrics.timed("Auto-fix")(Pipeline.autoFix(s2, backend, model, idris2))
                                            .flatTap(_ => IO.println(" done ✓"))
                                      case _ =>
                                        IO.pure(((result, s2), StepTiming("Auto-fix", 0)))
      _ <-
        IO.whenA(tFix.durationMs > 0)(
          log(
            s0.workDir,
            "05-autofix.txt",
            s"outcome: ${finalResult.summary}\n\n${finalResult.rawOutput}\n\n--- fixed source ---\n${s2a.lastIdris.getOrElse("")}",
          ),
        )
      _ <- IO.print("  Preparing feedback...")
      feedbackLabel = finalResult.outcome match
                        case CompilerOutcome.Clean     => "Back-translate"
                        case CompilerOutcome.Holes(_)  => "Explain holes"
                        case CompilerOutcome.Errors(_) => "Explain errors"
      (explanation, tFeedback) <- Metrics.timed(feedbackLabel) {
                                    finalResult.outcome match
                                      case CompilerOutcome.Clean     => Pipeline.backTranslate(s2a, backend, fast)
                                      case CompilerOutcome.Holes(_)  => Pipeline.explainHoles(s2a, backend, fast)
                                      case CompilerOutcome.Errors(_) => Pipeline.explainErrors(s2a, backend, fast)
                                  }
      _ <- IO.println(" done ✓\n")
      _ <- log(s0.workDir, "06-explanation.txt", s"[$feedbackLabel]\n\n$explanation")
      timings = List(tReady, tTriage, tPlan, tDecomp, tForm, tComp, tFix, tFeedback)
                  .filter(_.durationMs > 0)
      s3 = timings.foldLeft(s2a)((s, t) => s.withTiming(t))
      _ <- IO.println(s3.renderMetrics)
      _ <- finalResult.outcome match
             case CompilerOutcome.Clean =>
               IO.println(header("Refined Specification")) *> IO.println(explanation)
             case CompilerOutcome.Holes(_) =>
               IO.println(header("Questions")) *> IO.println(explanation)
             case CompilerOutcome.Errors(_) =>
               IO.println(header("Issues Found")) *> IO.println(explanation)
      _ <- printNextSteps(finalResult.outcome, s0.workDir)
    yield s3

  // ---------------------------------------------------------------------------
  // Actionable next steps
  // ---------------------------------------------------------------------------

  private def printNextSteps(outcome: CompilerOutcome, workDir: os.Path): IO[Unit] =
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
  // Diagnostic logging — write intermediate outputs to work dir
  // ---------------------------------------------------------------------------

  private def log(workDir: os.Path, name: String, content: String): IO[Unit] =
    IO.blocking {
      val logDir = workDir / "logs"
      os.makeDir.all(logDir)
      os.write.over(logDir / name, content)
    }

  // ---------------------------------------------------------------------------
  // Interactive menu
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
        _     <- IO.println(s"  💡 Full formalization saved at: ${session.workDir / "Spec.idr"}")
        _     <- IO.println(menuText)
        _     <- IO.print("> ")
        input <- IO.blocking(Option(scala.io.StdIn.readLine()).getOrElse("q").trim.toLowerCase)
        result <- input match
                    case "a" => collectAnswers(session).map(Some(_))
                    case "v" =>
                      IO.println(header("Raw Compiler Output")) *>
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

  // ---------------------------------------------------------------------------
  // Formatting (shared with Main via package-level access)
  // ---------------------------------------------------------------------------

  def header(text: String): String =
    val width = math.max(text.length + 4, 50)
    val sep   = "=" * width
    s"\n$sep\n  $text\n$sep\n"

  val divider: String = "-" * 50

package dev.sibylsystems.arkhitekton.core

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*

/** Core orchestration logic, decoupled from presentation via the Presenter trait. */
object Orchestrator:

  // ---------------------------------------------------------------------------
  // Single iteration
  // ---------------------------------------------------------------------------

  def oneIteration(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
    presenter: Presenter[IO],
  ): IO[Session] =
    val iter = session.iteration + 1
    val s0   = session.withIteration(iter)
    for
      _ <- presenter.info(header(s"Pass $iter"))
      // Readiness gate (only on first iteration)
      (s0r, tReady) <- runReadiness(s0, backend, presenter)
      result <- s0r.readiness match
                  case Some(v) if !v.isReady =>
                    val s1 = s0r.withTiming(tReady)
                    presenter.readinessResult(v) *>
                      presenter.metrics(s1) *>
                      IO.pure(s1)
                  case _ =>
                    continueIteration(s0r, tReady, backend, model, idris2, presenter)
    yield result

  // ---------------------------------------------------------------------------
  // Single step
  // ---------------------------------------------------------------------------

  def singleStep(
    step: String,
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
    presenter: Presenter[IO],
  ): IO[ExitCode] =
    step match
      case "readiness" =>
        for
          _            <- StateIO.saveSpec(session.workDir, session.originalSpec)
          (verdict, _) <- Readiness.assess(session, backend, Models.fast)
          _            <- StateIO.saveReadiness(session.workDir, verdict)
          _            <- presenter.readinessResult(verdict)
        yield if verdict.isReady then ExitCode.Success else ExitCode.Error

      case "triage" =>
        for
          _       <- StateIO.saveSpec(session.workDir, session.originalSpec)
          (tr, _) <- Triage.run(session, backend, Models.fast)
          _       <- StateIO.saveTriage(session.workDir, tr)
          _       <- presenter.info(tr.renderSummary)
          _ <- presenter.info(
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
          _ <- if plans.isEmpty then presenter.info("Single module sufficient — no splitting needed.")
               else
                 presenter.info(s"${plans.size} modules planned:") *>
                   plans.traverse_(p =>
                     presenter.info(s"  ${p.moduleName} depends on [${p.dependsOn.mkString(", ")}]"),
                   )
        yield ExitCode.Success

      case "decompose" =>
        for
          s0 <- ensurePriorStep(session, "triage")
          specText = s0.triageResult.map(_.formalizableText).getOrElse(session.originalSpec)
          manifest <- Decompose.run(specText, backend, Models.fast)
          _        <- presenter.info(s"${manifest.entries.size} definitions extracted:\n")
          _        <- presenter.info(manifest.render)
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
          _ <- presenter.info(s"Formalize done (${t.render})")
          _ <- presenter.info(s"Idris: ${s1.idrisLineCount} lines")
        yield ExitCode.Success

      case "compile" | "check" =>
        for
          s0          <- ensurePriorStep(session, "formalize")
          (result, _) <- Pipeline.compile(s0, idris2)
          _           <- StateIO.saveCompileResult(session.workDir, result)
          _           <- log(session.workDir, "04-compile.txt", s"outcome: ${result.summary}\n\n${result.rawOutput}")
          _           <- presenter.info(result.summary)
          _ <- result.outcome match
                 case CompilerOutcome.Errors(errs) => errs.traverse_(e => presenter.info(s"\n$e"))
                 case CompilerOutcome.Holes(holes) => holes.traverse_(h => presenter.info(s"\n${h.render}"))
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
                   presenter.info(s"After auto-fix: ${result.summary} (was $originalErrors errors)"),
               )
          _ <- IO.whenA(!improved) {
                 val restore =
                   if s0.isMultiModule then
                     val pairs = s0.modules.map(m => (m.moduleName, m.idrisSource))
                     Compiler.writeModules(pairs, session.workDir).void
                   else s0.lastIdris.traverse_(code => IO.blocking(os.write.over(session.workDir / "Spec.idr", code)))
                 restore *> presenter.info(
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
          _ <- presenter.info(explanation)
        yield ExitCode.Success

      case "back-translate" =>
        for
          s0          <- ensurePriorStep(session, "compile")
          explanation <- Pipeline.backTranslate(s0, backend, Models.fast)
          _           <- presenter.info(explanation)
        yield ExitCode.Success

      case other =>
        presenter.info(
          s"""Unknown step: $other
             |Available steps: readiness, triage, plan, decompose, formalize, compile, autofix, explain, back-translate""".stripMargin,
        ).as(ExitCode.Error)

  // ---------------------------------------------------------------------------
  // Transpile pipeline
  // ---------------------------------------------------------------------------

  def transpile(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
    presenter: Presenter[IO],
  ): IO[(Session, Int)] =
    for
      s1     <- oneIteration(session, backend, model, idris2, presenter)
      outcome = s1.lastResult.map(_.outcome).getOrElse(CompilerOutcome.Clean)
      result <- outcome match
                  case CompilerOutcome.Clean =>
                    for
                      _      <- presenter.info(header("Transpile Pipeline"))
                      _      <- presenter.progress("transpile", "[Step 4/7] Filling typed holes...")
                      result <- Pipeline.runTranspile(s1, backend, model, idris2)
                      _      <- presenter.progress("transpile", "[Step 7/7] Running tests...")
                      _      <- presenter.info(divider)
                      _      <- presenter.info(result.testResult.rawOutput)
                      _      <- presenter.info(divider)
                      _      <- presenter.info(s"  ${result.testResult.summary}")
                    yield (s1, result.testResult.failed)
                  case _ =>
                    presenter.info("Spec did not compile clean — cannot transpile. Fix the spec issues first.")
                      .as((s1, -1))
    yield result

  // ---------------------------------------------------------------------------
  // Load prior state from workdir
  // ---------------------------------------------------------------------------

  def ensurePriorStep(session: Session, priorStep: String): IO[Session] =
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
  // Diagnostic logging — write intermediate outputs to work dir
  // ---------------------------------------------------------------------------

  def log(workDir: os.Path, name: String, content: String): IO[Unit] =
    IO.blocking {
      val logDir = workDir / "logs"
      os.makeDir.all(logDir)
      os.write.over(logDir / name, content)
    }

  // ---------------------------------------------------------------------------
  // Formatting helpers (shared)
  // ---------------------------------------------------------------------------

  def header(text: String): String =
    val width = math.max(text.length + 4, 50)
    val sep   = "=" * width
    s"\n$sep\n  $text\n$sep\n"

  val divider: String = "-" * 50

  // ---------------------------------------------------------------------------
  // Private helpers
  // ---------------------------------------------------------------------------

  private def runReadiness(
    session: Session,
    backend: LlmBackend,
    presenter: Presenter[IO],
  ): IO[(Session, StepTiming)] =
    if session.readiness.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Readiness", 0)))
    else
      for
        _                 <- presenter.progress("readiness", "Assessing spec readiness...")
        ((verdict, s), t) <- Metrics.timed("Readiness")(Readiness.assess(session, backend, Models.fast))
        _                 <- StateIO.saveReadiness(session.workDir, verdict)
        _ <- verdict match
               case ReadinessVerdict.Ready(_, kind) =>
                 presenter.stepCompleted("readiness", t.durationMs) *>
                   presenter.info(s"  ready (${kind.label})")
               case ReadinessVerdict.NotReady(_, _, kind) =>
                 presenter.stepCompleted("readiness", t.durationMs) *>
                   presenter.info(s"  not ready (${kind.label})")
      yield (s, t)

  private def runTriage(
    session: Session,
    backend: LlmBackend,
    model: String,
    presenter: Presenter[IO],
  ): IO[(Session, StepTiming)] =
    if session.triageResult.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Triage", 0)))
    else
      for
        _           <- presenter.progress("triage", "Analyzing spec structure...")
        ((_, s), t) <- Metrics.timed("Triage")(Triage.run(session, backend, model))
        _           <- s.triageResult.traverse_(tr => StateIO.saveTriage(session.workDir, tr))
        _ <- s.triageResult match
               case Some(tr) if tr.sections.size > 1 =>
                 presenter.stepCompleted("triage", t.durationMs) *>
                   presenter.info(s" done (${tr.formalizable.size} formalizable, " +
                     s"${tr.intent.size} intent, ${tr.infrastructure.size} infra)")
               case _ =>
                 presenter.info(" skipped (small spec)")
      yield (s, t)

  private def planModules(
    session: Session,
    backend: LlmBackend,
    model: String,
    presenter: Presenter[IO],
  ): IO[(List[ModulePlan], StepTiming)] =
    session.triageResult match
      case Some(tr) if tr.formalizable.size >= ModulePlanner.MinSectionsForSplit =>
        for
          _          <- presenter.progress("plan", "Planning module structure...")
          (plans, t) <- Metrics.timed("Plan modules")(ModulePlanner.plan(tr, backend, model))
          msg = if plans.nonEmpty then s" done (${plans.size} modules)"
                else " single module sufficient"
          _ <- presenter.stepCompleted("plan", t.durationMs) *> presenter.info(msg)
        yield (plans, t)
      case _ =>
        IO.pure((Nil, StepTiming("Plan modules", 0)))

  private def runDecompose(
    session: Session,
    backend: LlmBackend,
    model: String,
    presenter: Presenter[IO],
  ): IO[(Session, StepTiming)] =
    if session.manifest.isDefined || session.iteration != 1 then
      IO.pure((session, StepTiming("Decompose", 0)))
    else
      val specText = session.triageResult.map(_.formalizableText).getOrElse(session.originalSpec)
      for
        _             <- presenter.progress("decompose", "Extracting definition structure...")
        (manifest, t) <- Metrics.timed("Decompose")(Decompose.run(specText, backend, model))
        s1             = session.withManifest(manifest)
        _             <- presenter.stepCompleted("decompose", t.durationMs)
        _             <- presenter.info(s" done (${manifest.entries.size} definitions)")
      yield (s1, t)

  private def continueIteration(
    s0: Session,
    tReady: StepTiming,
    backend: LlmBackend,
    model: String,
    idris2: String,
    presenter: Presenter[IO],
  ): IO[Session] =
    val fast = Models.fast
    for
      // Triage: classify sections (only on first iteration, only for large specs)
      (s0t, tTriage) <- runTriage(s0, backend, fast, presenter)
      _              <- s0t.triageResult.fold(IO.unit)(tr => log(s0.workDir, "01-triage.txt", tr.renderSummary))
      // Plan modules (only if triage found enough formalizable sections)
      (plans, tPlan) <- planModules(s0t, backend, fast, presenter)
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
      (s0d, tDecomp) <- runDecompose(s0t, backend, fast, presenter)
      _ <- s0d.manifest.traverse_(m => log(s0.workDir, "02b-manifest.txt", m.render))
      // Formalize: single-module or multi-module parallel
      _ <- presenter.progress(
             "formalize",
             if plans.nonEmpty then
               s"Translating spec to ${plans.size} formal modules (parallel)..."
             else
               "Translating your spec to a formal model (this may take a moment)...",
           )
      (s1, tForm) <- Metrics.timed(if plans.nonEmpty then s"Formalize (${plans.size} modules)" else "Formalize") {
                       if plans.nonEmpty then
                         Pipeline.formalizeMultiModule(s0d, plans, backend, model).map(_._2)
                       else
                         Pipeline.formalize(s0d, backend, model).map(_._2)
                     }
      _ <- presenter.stepCompleted("formalize", tForm.durationMs)
      _ <- s1.modules match
             case Nil => log(s0.workDir, "03-formalize.idr", s1.lastIdris.getOrElse(""))
             case ms => ms.zipWithIndex.traverse_ {
                 case (m, i) =>
                   log(s0.workDir, f"03-formalize-${i + 1}%02d-${m.moduleName}.idr", m.idrisSource)
               }
      _ <- presenter.progress("compile", "Checking for consistency and gaps...")
      ((result, s2), tComp) <- Metrics.timed("Compile")(Pipeline.compile(s1, idris2))
      _ <- presenter.stepCompleted("compile", tComp.durationMs)
      _ <- log(s0.workDir, "04-compile.txt", s"outcome: ${result.summary}\n\n${result.rawOutput}")
      // If compilation failed, let the LLM fix its own structural mistakes first
      ((finalResult, s2a), tFix) <- result.outcome match
                                      case CompilerOutcome.Errors(_) =>
                                        presenter.progress("autofix", "Fixing structural issues...") *>
                                          Metrics.timed("Auto-fix")(Pipeline.autoFix(s2, backend, model, idris2))
                                            .flatTap { case (_, t) => presenter.stepCompleted("autofix", t.durationMs) }
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
      _ <- presenter.progress("feedback", "Preparing feedback...")
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
      _ <- presenter.stepCompleted("feedback", tFeedback.durationMs)
      _ <- log(s0.workDir, "06-explanation.txt", s"[$feedbackLabel]\n\n$explanation")
      timings = List(tReady, tTriage, tPlan, tDecomp, tForm, tComp, tFix, tFeedback)
                  .filter(_.durationMs > 0)
      s3 = timings.foldLeft(s2a)((s, t) => s.withTiming(t))
      _ <- presenter.metrics(s3)
      _ <- presenter.result(
             finalResult.outcome match
               case CompilerOutcome.Clean     => "Refined Specification"
               case CompilerOutcome.Holes(_)  => "Questions"
               case CompilerOutcome.Errors(_) => "Issues Found",
             finalResult.outcome,
             explanation,
           )
      _ <- presenter.nextSteps(finalResult.outcome, s0.workDir)
    yield s3

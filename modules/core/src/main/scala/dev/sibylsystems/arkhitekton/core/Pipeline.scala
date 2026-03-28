package dev.sibylsystems.arkhitekton.core

import cats.effect.IO
import cats.syntax.all.*

/** Orchestrates the pipeline steps: formalize → compile → explain/back-translate. */
object Pipeline:

  private val CodeFencePattern = """(?s)```(?:idris2?|idr|typescript|ts|javascript|js)?\s*\n(.*?)```""".r

  /** Extract a fenced code block from LLM output, or return as-is. */
  private def extractCodeBlock(text: String): String =
    CodeFencePattern.findFirstMatchIn(text) match
      case Some(m) => m.group(1).trim
      case None    => text.trim

  // ---------------------------------------------------------------------------
  // Step 1: Formalize (English → Idris 2)
  // ---------------------------------------------------------------------------

  private enum ModuleOutcome:
    case Ok(code: String)
    case Retry
    case Failed

  private def formatOutcome(desc: String, outcome: ModuleOutcome): String =
    outcome match
      case ModuleOutcome.Ok(_)  => s"    ${Terminal.green("✓")} $desc"
      case ModuleOutcome.Retry  => s"    ${Terminal.yellow("↻")} $desc ${Terminal.dim("(retrying...)")}"
      case ModuleOutcome.Failed => s"    ${Terminal.red("✗")} $desc ${Terminal.dim("(failed)")}"

  /** Derive a human-readable description of what a module covers from its section headings. */
  private def moduleDescription(plan: ModulePlan): String =
    val headings = plan.sections.map(_.heading.replaceAll("^#+\\s*", "").trim)
    if headings.isEmpty then plan.moduleName
    else
      val summary = headings.mkString(", ")
      val short   = if summary.length > 60 then summary.take(57) + "..." else summary
      short

  /** Build the intent block to append to spec text for formalize prompts. */
  private def intentBlock(session: Session): String =
    (session.specKind, session.intentSummary) match
      case (Some(kind), Some(intent)) =>
        s"\n\n## System Intent\n\nThis spec describes a ${kind.label}. $intent\n" +
          "Use this to guide your formalization — model the system's machinery, " +
          "not domain knowledge it would consume at runtime."
      case _ => ""

  /** Build the manifest directive block if a manifest is available. */
  private def manifestBlock(session: Session): String =
    session.manifest.map(m => "\n\n" + m.asOrderDirective).getOrElse("")

  def formalize(session: Session, backend: LlmBackend, model: String): IO[(String, Session)] =
    // If triage ran, formalize only the formalizable sections
    val specText = session.triageResult.map(_.formalizableText).getOrElse(session.originalSpec)
    val enrichedSpec = specText + intentBlock(session) + manifestBlock(session)
    val (systemPrompt, userPrompt) =
      if session.answers.nonEmpty || session.lastResult.isDefined then
        val feedback = session.lastResult.map(_.rawOutput).getOrElse("(none)")
        (Prompts.formalizeSystem, Prompts.formalizeFollowup(enrichedSpec, session.answers, feedback))
      else
        (Prompts.formalizeSystem, Prompts.formalizeUser(enrichedSpec))

    for
      raw <- backend.complete(
               model = model,
               maxTokens = 8192,
               system = Some(systemPrompt),
               messages = List(ClaudeMessage("user", userPrompt)),
               tools = List(References.toolDefinition),
             )
      code = extractCodeBlock(raw)
    yield (code, session.withIdris(code))

  // ---------------------------------------------------------------------------
  // Step 1b: Multi-module formalize (parallel)
  // ---------------------------------------------------------------------------

  def formalizeMultiModule(
    session: Session,
    plans: List[ModulePlan],
    backend: LlmBackend,
    model: String,
  ): IO[(List[ModuleResult], Session)] =
    val batches = ModulePlanner.topologicalBatches(plans)

    def processBatches(
      remaining: List[List[ModulePlan]],
      completed: Map[String, ModuleResult],
    ): IO[List[ModuleResult]] =
      remaining match
        case Nil => IO.pure(completed.values.toList)
        case batch :: rest =>
          for
            results     <- batch.parTraverse(plan => formalizeOneModule(plan, completed, session, backend, model))
            newCompleted = completed ++ results.map(r => r.moduleName -> r)
            all         <- processBatches(rest, newCompleted)
          yield all

    for
      results <- processBatches(batches, Map.empty)
      s1       = session.withModules(results)
    yield (results, s1)

  private def formalizeOneModule(
    plan: ModulePlan,
    completed: Map[String, ModuleResult],
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[ModuleResult] =
    val depsText = if plan.dependsOn.isEmpty then "(none)"
    else plan.dependsOn.mkString(", ")
    val sigsText = plan.dependsOn.flatMap(completed.get).map { dep =>
      // Extract type signatures (lines with : that aren't comments)
      val sigs = dep.idrisSource.linesIterator.filter { line =>
        val trimmed = line.trim
        trimmed.contains(" : ") && !trimmed.startsWith("--") && !trimmed.startsWith("{")
      }.mkString("\n")
      s"-- From ${dep.moduleName}:\n$sigs"
    }.mkString("\n\n")
    val sectionsText = plan.sections.map(s => s"${s.heading}\n\n${s.content}").mkString("\n\n---\n\n") +
      intentBlock(session) + manifestBlock(session)

    val desc           = moduleDescription(plan)
    val MinModuleLines = 3

    def callLlm(): IO[String] =
      backend.complete(
        model = model,
        maxTokens = 8192,
        system = Some(Prompts.formalizeModuleSystem),
        messages = List(ClaudeMessage(
          "user",
          Prompts.formalizeModuleUser(plan.moduleName, depsText, sigsText, sectionsText),
        )),
        tools = List(References.toolDefinition),
      )

    def classify(raw: String, retriesLeft: Int): ModuleOutcome =
      val nonBlank = extractCodeBlock(raw).linesIterator.count(_.trim.nonEmpty)
      (nonBlank >= MinModuleLines, retriesLeft) match
        case (true, _)        => ModuleOutcome.Ok(extractCodeBlock(raw))
        case (_, n) if n > 0  => ModuleOutcome.Retry
        case _                => ModuleOutcome.Failed

    def resolve(outcome: ModuleOutcome, retriesLeft: Int): IO[ModuleResult] =
      IO.println(formatOutcome(desc, outcome)) *> {
        outcome match
          case ModuleOutcome.Ok(code) => IO.pure(ModuleResult(plan.moduleName, code))
          case ModuleOutcome.Retry    => attempt(retriesLeft - 1)
          case ModuleOutcome.Failed   => IO.pure(ModuleResult(plan.moduleName, s"module ${plan.moduleName}\n\n-- TODO: formalize failed\n"))
      }

    def attempt(retries: Int): IO[ModuleResult] =
      callLlm().flatMap(raw => resolve(classify(raw, retries), retries))

    attempt(2)

  // ---------------------------------------------------------------------------
  // Step 2: Compile
  // ---------------------------------------------------------------------------

  def compile(session: Session, idris2: String): IO[(CompilerResult, Session)] =
    if session.isMultiModule then compileMultiModule(session, idris2)
    else
      session.lastIdris match
        case None => IO.raiseError(new RuntimeException("No Idris code to compile"))
        case Some(code) =>
          Compiler.check(code, session.workDir, idris2).map(result => (result, session.withResult(result)))

  private def compileMultiModule(session: Session, idris2: String): IO[(CompilerResult, Session)] =
    val modulePairs = session.modules.map(m => (m.moduleName, m.idrisSource))
    for
      _      <- Compiler.writeModules(modulePairs, session.workDir)
      _      <- Compiler.writeIpkg(session.modules.map(_.moduleName), session.workDir)
      result <- Compiler.checkPackage(session.workDir, idris2)
    yield (result, session.withResult(result))

  // ---------------------------------------------------------------------------
  // Step 3a: Explain holes
  // ---------------------------------------------------------------------------

  /** Build a context suffix for explain prompts so questions/explanations target the right abstraction level. */
  private def intentContext(session: Session): String =
    session.intentSummary.map { intent =>
      s"\n\n## System Context\n\n$intent\n" +
        "Frame questions and explanations at the level of this system's design, " +
        "not at the level of domain knowledge it would evaluate."
    }.getOrElse("")

  def explainHoles(session: Session, backend: LlmBackend, model: String): IO[String] =
    val holesText = session.lastResult match
      case Some(CompilerResult(CompilerOutcome.Holes(holes), _, _)) =>
        holes.map(_.render).mkString("\n\n")
      case _ => "(no hole details available)"

    val idrisSource = session.lastIdris.getOrElse("(no code)")

    backend.complete(
      model = model,
      maxTokens = 4096,
      system = Some(Prompts.explainHolesSystem),
      messages = List(ClaudeMessage("user", Prompts.explainHolesUser(idrisSource, holesText) + intentContext(session))),
    )

  // ---------------------------------------------------------------------------
  // Step 3b: Explain errors
  // ---------------------------------------------------------------------------

  def explainErrors(session: Session, backend: LlmBackend, model: String): IO[String] =
    val errorsText = session.lastResult match
      case Some(CompilerResult(CompilerOutcome.Errors(errors), _, _)) =>
        errors.mkString("\n\n")
      case _ => "(no errors)"

    val idrisSource = session.lastIdris.getOrElse("(no code)")

    backend.complete(
      model = model,
      maxTokens = 4096,
      system = Some(Prompts.explainErrorsSystem),
      messages = List(ClaudeMessage("user", Prompts.explainErrorsUser(idrisSource, errorsText) + intentContext(session))),
    )

  // ---------------------------------------------------------------------------
  // Step 3c: Back-translate
  // ---------------------------------------------------------------------------

  def backTranslate(session: Session, backend: LlmBackend, model: String): IO[String] =
    val idrisSource = session.lastIdris.getOrElse("(no code)")

    backend.complete(
      model = model,
      maxTokens = 8192,
      system = Some(Prompts.backTranslateSystem),
      messages = List(ClaudeMessage("user", Prompts.backTranslateUser(idrisSource, session.originalSpec))),
    )

  // ---------------------------------------------------------------------------
  // Auto-fix: let the LLM fix its own structural mistakes before bothering SME
  // ---------------------------------------------------------------------------

  private val MaxAutoFixAttempts = 2

  def autoFix(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[(CompilerResult, Session)] =
    if session.isMultiModule then autoFixMultiModule(session, backend, model, idris2)
    else autoFixSingle(session, backend, model, idris2)

  private def autoFixSingle(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[(CompilerResult, Session)] =
    val originalResult = session.lastResult.getOrElse(
      CompilerResult(CompilerOutcome.Errors(Nil), "", None),
    )
    val originalSource = session.lastIdris.getOrElse("(no code)")

    def attempt(source: String, errors: List[String], retries: Int): IO[(CompilerResult, Session)] =
      val errorsText = errors.mkString("\n\n")
      for
        raw <- backend.complete(
                 model = model,
                 maxTokens = 8192,
                 system = Some(Prompts.autoFixSystem),
                 messages = List(ClaudeMessage("user", Prompts.autoFixUser(source, errorsText))),
                 tools = List(References.toolDefinition),
               )
        candidate = extractCodeBlock(raw)
        fixed =
          if candidate.linesIterator.count(_.trim.nonEmpty) > 2 then candidate
          else source
        s1            = session.withIdris(fixed)
        (result, s2) <- compile(s1, idris2)
        final_ <- result.outcome match
                    case CompilerOutcome.Errors(errs) if retries > 0 =>
                      attempt(fixed, errs, retries - 1)
                    case _ => IO.pure((result, s2))
      yield final_

    val errors = session.lastResult match
      case Some(CompilerResult(CompilerOutcome.Errors(errs), _, _)) => errs
      case _                                                        => Nil
    val originalErrorCount = errors.size
    attempt(originalSource, errors, MaxAutoFixAttempts - 1).map {
      case (result, s) =>
        val newErrorCount = result.outcome match
          case CompilerOutcome.Errors(e) => e.size
          case _                         => 0
        // Revert if auto-fix didn't strictly reduce errors
        if newErrorCount >= originalErrorCount then (originalResult, session)
        else (result, s)
    }.handleErrorWith(_ => IO.pure((originalResult, session)))

  /** Multi-module auto-fix: identify which modules have errors, fix only those. */
  private def autoFixMultiModule(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[(CompilerResult, Session)] =
    val originalResult = session.lastResult.getOrElse(
      CompilerResult(CompilerOutcome.Errors(Nil), "", None),
    )
    val errors = session.lastResult match
      case Some(CompilerResult(CompilerOutcome.Errors(errs), _, _)) => errs
      case _                                                        => Nil

    // Map errors to module names by looking for file paths like Spec/Types.idr
    val errorsByModule = groupErrorsByModule(errors, session.modules)

    def fixModules(
      remaining: List[(String, List[String])],
      currentModules: List[ModuleResult],
      retries: Int,
    ): IO[(CompilerResult, Session)] =
      remaining match
        case Nil =>
          // All targeted modules fixed — recompile the whole package
          val s1 = session.withModules(currentModules)
          compileMultiModule(s1, idris2).map {
            case (result, s2) =>
              result.outcome match
                case CompilerOutcome.Errors(_) if retries > 0 =>
                  // Still errors after fixing — will need another round
                  (result, s2)
                case _ => (result, s2)
          }
        case (moduleName, moduleErrors) :: rest =>
          val moduleOpt = currentModules.find(_.moduleName == moduleName)
          moduleOpt match
            case None => fixModules(rest, currentModules, retries)
            case Some(mod) =>
              // Build cross-module context so the LLM knows what's defined elsewhere
              val otherModules = currentModules.filter(_.moduleName != moduleName).map { other =>
                val sigs = other.idrisSource.linesIterator.filter { line =>
                  val t = line.trim
                  (t.contains(" : ") || t.startsWith("data ") || t.startsWith("record ")) &&
                    !t.startsWith("--") && !t.startsWith("{")
                }.mkString("\n")
                s"-- ${other.moduleName}:\n$sigs"
              }.mkString("\n\n")
              val crossModuleHint =
                if otherModules.trim.isEmpty then ""
                else
                  "\n\n## Other modules in this package (do NOT redefine types already defined here):\n\n" +
                    otherModules
              val errorsText = moduleErrors.mkString("\n\n")
              for
                raw <- backend.complete(
                         model = model,
                         maxTokens = 8192,
                         system = Some(Prompts.autoFixSystem),
                         messages = List(ClaudeMessage(
                           "user",
                           Prompts.autoFixUser(mod.idrisSource, errorsText) + crossModuleHint,
                         )),
                         tools = List(References.toolDefinition),
                       )
                candidate = extractCodeBlock(raw)
                fixed =
                  if candidate.linesIterator.count(_.trim.nonEmpty) > 2 then candidate
                  else mod.idrisSource
                updatedModules = currentModules.map(m =>
                                   if m.moduleName == moduleName then ModuleResult(moduleName, fixed) else m,
                                 )
                result <- fixModules(rest, updatedModules, retries)
              yield result

    val originalErrorCount = errors.size
    val modulesToFix       = errorsByModule.toList
    if modulesToFix.isEmpty then
      // Errors don't map to specific modules — fall back to fixing concatenated source
      autoFixSingle(session, backend, model, idris2)
    else
      val fixed =
        for
          (result, s1) <- fixModules(modulesToFix, session.modules.toList, MaxAutoFixAttempts - 1)
          // If still errors after first round, try one more pass
          (finalResult, s2) <- result.outcome match
                                 case CompilerOutcome.Errors(errs) =>
                                   val newErrors = groupErrorsByModule(errs, s1.modules)
                                   if newErrors.nonEmpty then
                                     fixModules(newErrors.toList, s1.modules.toList, 0).flatMap {
                                       case (_, s) =>
                                         compileMultiModule(s, idris2)
                                     }
                                   else IO.pure((result, s1))
                                 case _ => IO.pure((result, s1))
          // Guard: if auto-fix made things worse, revert to original
          newErrorCount = finalResult.outcome match
                            case CompilerOutcome.Errors(e) => e.size
                            case _                         => 0
          // Restore original files on disk when reverting
          _ <- IO.whenA(newErrorCount >= originalErrorCount) {
                 val pairs = session.modules.map(m => (m.moduleName, m.idrisSource))
                 Compiler.writeModules(pairs, session.workDir).void
               }
        yield
          if newErrorCount >= originalErrorCount then (originalResult, session)
          else (finalResult, s2)
      fixed.handleErrorWith { _ =>
        // Restore original files on error too
        val pairs = session.modules.map(m => (m.moduleName, m.idrisSource))
        Compiler.writeModules(pairs, session.workDir).as((originalResult, session))
      }

  /**
   * Group compiler errors by module name based on references in the error text.
   *
   * Idris 2 uses two formats:
   *   - `Spec/Types.idr:15:10` (file path)
   *   - `Spec.Types:15:10` (module-qualified)
   *   - `Spec.Types.someName` (qualified name in "already defined" errors)
   */
  private def groupErrorsByModule(
    errors: List[String],
    modules: List[ModuleResult],
  ): Map[String, List[String]] =
    val patterns = modules.map { m =>
      val filePath   = m.moduleName.replace('.', '/') + ".idr"
      val moduleRef  = m.moduleName + ":"
      val qualPrefix = m.moduleName + "."
      m.moduleName -> List(filePath, moduleRef, qualPrefix)
    }
    errors.foldLeft(Map.empty[String, List[String]]) { (acc, error) =>
      val matchingModule = patterns.find {
        case (_, pats) =>
          pats.exists(error.contains)
      }.map(_._1)
      matchingModule match
        case Some(name) => acc.updated(name, acc.getOrElse(name, Nil) :+ error)
        case None       => acc
    }

  // ---------------------------------------------------------------------------
  // Full iteration: formalize → compile → (auto-fix) → explain
  // ---------------------------------------------------------------------------

  final case class IterationResult(
    session: Session,
    explanation: String,
    outcome: CompilerOutcome,
  )

  def runIteration(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[IterationResult] =
    for
      (_, s1)      <- formalize(session.withIteration(session.iteration + 1), backend, model)
      (result, s2) <- compile(s1, idris2)
      explanation <- result.outcome match
                       case CompilerOutcome.Clean     => backTranslate(s2, backend, model)
                       case CompilerOutcome.Holes(_)  => explainHoles(s2, backend, model)
                       case CompilerOutcome.Errors(_) => explainErrors(s2, backend, model)
    yield IterationResult(s2, explanation, result.outcome)

  // ---------------------------------------------------------------------------
  // Step 4: Fill holes (spec → implementation)
  // ---------------------------------------------------------------------------

  private val MaxFillRetries = 3

  def fillHoles(session: Session, backend: LlmBackend, model: String, idris2: String): IO[(String, Session)] =
    def attempt(source: String, retries: Int): IO[(String, Session)] =
      for
        raw <- backend.complete(
                 model = model,
                 maxTokens = 8192,
                 system = Some(Prompts.fillHolesSystem),
                 messages = List(ClaudeMessage("user", Prompts.fillHolesUser(source, session.originalSpec))),
                 tools = List(References.toolDefinition),
               )
        filled        = extractCodeBlock(raw)
        s1            = session.withIdris(filled)
        (result, s2) <- compile(s1, idris2)
        final_ <- result.outcome match
                    case CompilerOutcome.Clean => IO.pure((filled, s2))
                    case CompilerOutcome.Holes(holes) if retries > 0 =>
                      IO.println(s"  Hole-filling left ${holes.size} holes, retrying... (${retries - 1} left)") *>
                        attempt(filled, retries - 1)
                    case CompilerOutcome.Errors(_) if retries > 0 =>
                      IO.println(s"  Hole-filling caused errors, retrying... (${retries - 1} left)") *>
                        attempt(filled, retries - 1)
                    case CompilerOutcome.Holes(holes) =>
                      IO.raiseError(new RuntimeException(
                        s"Hole-filling failed: ${holes.size} holes remain after $MaxFillRetries attempts",
                      ))
                    case CompilerOutcome.Errors(errs) =>
                      IO.raiseError(new RuntimeException(s"Hole-filling failed: ${errs.head.take(200)}"))
      yield final_

    attempt(session.lastIdris.getOrElse("(no code)"), MaxFillRetries)

  // ---------------------------------------------------------------------------
  // Step 5: Transpile to JS
  // ---------------------------------------------------------------------------

  def transpileToJS(session: Session, idris2: String): IO[(os.Path, List[String], Session)] =
    for
      jsPath                   <- Compiler.compileToJS(session.lastIdris.getOrElse("(no code)"), session.workDir, idris2)
      (processedPath, exports) <- PostProcess.process(jsPath, session.workDir)
    yield (processedPath, exports, session)

  // ---------------------------------------------------------------------------
  // Step 6: Generate .d.ts + adapter
  // ---------------------------------------------------------------------------

  private val TsFencePattern = """(?s)```(?:typescript|ts)?\s*\n(.*?)```""".r

  private def extractTsBlocks(text: String): List[String] =
    TsFencePattern.findAllMatchIn(text).map(_.group(1).trim).toList

  def generateTypesAndAdapter(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[(os.Path, os.Path)] =
    val idrisSource = session.lastIdris.getOrElse("(no code)")
    for
      raw <- backend.complete(
               model = model,
               maxTokens = 8192,
               system = Some(Prompts.generateTypesSystem),
               messages = List(ClaudeMessage("user", Prompts.generateTypesUser(idrisSource))),
               tools = List(References.toolDefinition),
             )
      blocks         = extractTsBlocks(raw)
      dtsContent     = blocks.headOption.getOrElse("// No types generated")
      adapterContent = blocks.lift(1).getOrElse("// No adapter generated")
      dtsPath        = session.workDir / "spec.d.ts"
      adapterPath    = session.workDir / "spec-adapter.ts"
      _ <- IO.blocking {
             os.write.over(dtsPath, dtsContent)
             os.write.over(adapterPath, adapterContent)
           }
    yield (dtsPath, adapterPath)

  // ---------------------------------------------------------------------------
  // Step 7: Generate tests
  // ---------------------------------------------------------------------------

  def generateTests(
    session: Session,
    backend: LlmBackend,
    model: String,
  ): IO[os.Path] =
    val idrisSource    = session.lastIdris.getOrElse("(no code)")
    val adapterContent = scala.util.Try(os.read(session.workDir / "spec-adapter.ts")).getOrElse("// no adapter")
    for
      raw <- backend.complete(
               model = model,
               maxTokens = 8192,
               system = Some(Prompts.generateTestsSystem),
               messages = List(
                 ClaudeMessage("user", Prompts.generateTestsUser(idrisSource, adapterContent, session.originalSpec)),
               ),
               tools = List(References.toolDefinition),
             )
      testContent = extractCodeBlock(raw)
      testPath    = session.workDir / "spec.test.ts"
      _          <- IO.blocking(os.write.over(testPath, testContent))
    yield testPath

  // ---------------------------------------------------------------------------
  // Full transpile pipeline: fill → compile JS → types → tests → run
  // ---------------------------------------------------------------------------

  final case class TranspilePipelineResult(
    session: Session,
    jsPath: os.Path,
    exports: List[String],
    dtsPath: os.Path,
    adapterPath: os.Path,
    testPath: os.Path,
    testResult: TestResult,
  )

  def runTranspile(
    session: Session,
    backend: LlmBackend,
    model: String,
    idris2: String,
  ): IO[TranspilePipelineResult] =
    for
      (_, s1)                <- fillHoles(session, backend, model, idris2)
      _                      <- IO.println(s"  Holes filled, compiles clean.")
      (jsPath, exports, s2)  <- transpileToJS(s1, idris2)
      _                      <- IO.println(s"  JS: $jsPath (${exports.size} exports: ${exports.mkString(", ")})")
      (dtsPath, adapterPath) <- generateTypesAndAdapter(s2, backend, model)
      _                      <- IO.println(s"  Types: $dtsPath")
      _                      <- IO.println(s"  Adapter: $adapterPath")
      testPath               <- generateTests(s2, backend, model)
      _                      <- IO.println(s"  Tests: $testPath")
      testResult             <- TestRunner.run(s2.workDir)
    yield TranspilePipelineResult(s2, jsPath, exports, dtsPath, adapterPath, testPath, testResult)

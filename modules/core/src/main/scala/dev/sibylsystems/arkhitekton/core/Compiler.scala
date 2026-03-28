package dev.sibylsystems.arkhitekton.core

import scala.util.matching.Regex

import cats.effect.IO

/** Wraps `idris2 --check` and parses the output into a CompilerResult. */
object Compiler:

  private val DefaultPath: String = "idris2"
  private val Timeout: Long       = 30_000 // ms

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Check that the idris2 compiler is reachable. Returns version string. */
  def verify(idris2: String = DefaultPath): IO[Option[String]] = IO.blocking {
    val result = os.proc(idris2, "--version").call(check = false, timeout = 10_000)
    Option.when(result.exitCode == 0)(result.out.trim())
  }.handleError(_ => None)

  /** Compile Idris 2 source to JavaScript via --cg node. Returns path to the JS file. */
  def compileToJS(source: String, workDir: os.Path, idris2: String = DefaultPath): IO[os.Path] =
    for
      prepared <- IO(ensureModuleDecl(source))
      path     <- IO(writeSource(prepared, workDir))
      _ <- IO.blocking {
             val result = os.proc(idris2, "--cg", "node", "-o", "Spec.js", path.toString)
               .call(cwd = workDir, check = false, timeout = 60_000)
             if result.exitCode != 0 then
               sys.error(s"JS codegen failed:\n${result.err.text()}")
           }
      // idris2 writes output to build/exec/ under the working directory
      jsPath = workDir / "build" / "exec" / "Spec.js"
      _ <- IO.whenA(!os.exists(jsPath))(
             IO.raiseError(new RuntimeException(s"JS compilation produced no output at $jsPath")),
           )
    yield jsPath

  /** Write source to Spec.idr, run idris2 --check, parse the result. */
  def check(source: String, workDir: os.Path, idris2: String = DefaultPath): IO[CompilerResult] =
    for
      prepared <- IO(ensureModuleDecl(source))
      path     <- IO(writeSource(prepared, workDir))
      raw      <- runCompiler(path, workDir, idris2)
      holes    <- queryHoles(path, workDir, idris2, prepared)
      result    = classify(raw, holes, path)
    yield result

  // ---------------------------------------------------------------------------
  // Multi-module support
  // ---------------------------------------------------------------------------

  /** Write multiple modules to their correct file paths. "Spec.Types" → Spec/Types.idr */
  def writeModules(modules: List[(String, String)], workDir: os.Path): IO[List[os.Path]] =
    IO.blocking {
      modules.map {
        case (name, source) =>
          val parts   = name.split('.')
          val dirPath = parts.init.foldLeft(workDir)(_ / _)
          os.makeDir.all(dirPath)
          val path = dirPath / s"${parts.last}.idr"
          os.write.over(path, source)
          path
      }
    }

  /** Generate a .ipkg file listing all modules. */
  def writeIpkg(moduleNames: List[String], workDir: os.Path): IO[os.Path] =
    IO.blocking {
      val mods = moduleNames.mkString("\n        , ")
      val content =
        s"""package spec
           |
           |sourcedir = "."
           |
           |modules = $mods
           |""".stripMargin
      val path = workDir / "spec.ipkg"
      os.write.over(path, content)
      path
    }

  /** Check a multi-module package via .ipkg. */
  def checkPackage(workDir: os.Path, idris2: String = DefaultPath): IO[CompilerResult] =
    for
      raw <- IO.blocking {
               val result = os.proc(idris2, "--typecheck", "spec.ipkg")
                 .call(cwd = workDir, check = false, timeout = Timeout * 2)
               CompilerOutput(
                 exitCode = result.exitCode,
                 stdout = result.out.text(),
                 stderr = result.err.text(),
               )
             }.handleError { e =>
               CompilerOutput(exitCode = 1, stdout = "", stderr = s"Compiler invocation failed: ${e.getMessage}")
             }
      // Collect holes from all module files
      allSources <- IO.blocking {
                      os.walk(workDir).filter(_.ext == "idr").map(p => os.read(p)).mkString("\n")
                    }
      holeNames = HolePattern.findAllMatchIn(allSources).map(_.group(1)).toList
      combined  = (raw.stdout + "\n" + raw.stderr).trim
      errors    = parseErrors(combined)
      outcome =
        if raw.exitCode != 0 && errors.nonEmpty then CompilerOutcome.Errors(errors)
        else if holeNames.nonEmpty then CompilerOutcome.Holes(holeNames.map(n => HoleInfo(n, "?", Nil)))
        else if raw.exitCode == 0 then CompilerOutcome.Clean
        else CompilerOutcome.Errors(errors.headOption.map(List(_)).getOrElse(List(combined)))
    yield CompilerResult(outcome, combined, None)

  // ---------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------

  private def writeSource(source: String, workDir: os.Path): os.Path =
    val path = workDir / "Spec.idr"
    os.write.over(path, source)
    path

  private def runCompiler(path: os.Path, workDir: os.Path, idris2: String): IO[CompilerOutput] =
    IO.blocking {
      val result = os.proc(idris2, "--check", path.toString)
        .call(cwd = workDir, check = false, timeout = Timeout)
      CompilerOutput(
        exitCode = result.exitCode,
        stdout = result.out.text(),
        stderr = result.err.text(),
      )
    }.handleError { e =>
      CompilerOutput(exitCode = 1, stdout = "", stderr = s"Compiler invocation failed: ${e.getMessage}")
    }

  /** Query the REPL for typed hole details (:t holeName). */
  private def queryHoles(
    path: os.Path,
    workDir: os.Path,
    idris2: String,
    source: String,
  ): IO[List[HoleInfo]] = IO.blocking {
    val holeNames = HolePattern.findAllMatchIn(source).map(_.group(1)).toList
    if holeNames.isEmpty then Nil
    else
      val commands = holeNames.map(n => s":t $n").mkString("\n") + "\n:q\n"
      val result = os.proc(idris2, "--find-ipkg", path.toString)
        .call(cwd = workDir, check = false, timeout = Timeout, stdin = commands)
      parseReplHoles(result.out.text())
  }.handleError(_ => Nil)

  private def classify(raw: CompilerOutput, holes: List[HoleInfo], path: os.Path): CompilerResult =
    val combined = (raw.stdout + "\n" + raw.stderr).trim
    val errors   = parseErrors(combined)
    val outcome =
      if raw.exitCode != 0 && errors.nonEmpty then CompilerOutcome.Errors(errors)
      else if holes.nonEmpty then CompilerOutcome.Holes(holes)
      else if raw.exitCode == 0 then CompilerOutcome.Clean
      else CompilerOutcome.Errors(errors.headOption.map(List(_)).getOrElse(List(combined)))
    CompilerResult(outcome, combined, Some(path))

  // ---------------------------------------------------------------------------
  // Source preprocessing
  // ---------------------------------------------------------------------------

  private val ModulePattern: Regex = """(?m)^\s*module\s+\S+""".r
  private val HolePattern: Regex   = """\?(\w+)""".r

  private def ensureModuleDecl(source: String): String =
    ModulePattern.findFirstIn(source) match
      case Some(_) =>
        // Replace existing module name with Spec
        ModulePattern.replaceFirstIn(source, "module Spec")
      case None =>
        s"module Spec\n\n$source"

  // ---------------------------------------------------------------------------
  // Output parsing
  // ---------------------------------------------------------------------------

  final private case class CompilerOutput(exitCode: Int, stdout: String, stderr: String)

  private val DashSep: Regex      = """^-{5,}$""".r
  private val HoleGoal: Regex     = """(\w+)\s*:\s*(.+)""".r
  private val PromptPrefix: Regex = """^\S+>\s*""".r

  private def parseReplHoles(output: String): List[HoleInfo] =
    val lines = output.linesIterator.toVector
    lines.zipWithIndex.collect {
      case (line, i) if DashSep.matches(line.trim) =>
        val context = collectContext(lines, i - 1)
        val goal    = if i + 1 < lines.length then Some(PromptPrefix.replaceFirstIn(lines(i + 1).trim, "")) else None
        goal.flatMap {
          case HoleGoal(name, goalType) => Some(HoleInfo(name, goalType, context))
          case _                        => None
        }
    }.flatten.toList

  /** Walk backwards from index collecting context lines until a stop condition. */
  @scala.annotation.tailrec
  private def collectContext(lines: Vector[String], idx: Int, acc: List[String] = Nil): List[String] =
    if idx < 0 then acc
    else
      val ctx = lines(idx).trim
      if ctx.isEmpty || ctx.endsWith(">") || ctx.contains("Welcome") then acc
      else
        val cleaned = PromptPrefix.replaceFirstIn(ctx, "")
        val next    = if cleaned.contains(":") then cleaned :: acc else acc
        collectContext(lines, idx - 1, next)

  private val ErrorStart: Regex = """^Error:""".r

  private def parseErrors(raw: String): List[String] =
    val (errors, current) = raw.linesIterator.foldLeft((List.empty[String], List.empty[String])) {
      case ((errs, cur), line) if ErrorStart.findFirstIn(line).isDefined =>
        val updated = if cur.nonEmpty then cur.reverse.mkString("\n") :: errs else errs
        (updated, List(line))
      case ((errs, cur), line) if cur.nonEmpty =>
        (errs, line :: cur)
      case (acc, _) => acc
    }
    val all = if current.nonEmpty then current.reverse.mkString("\n") :: errors else errors
    all.reverse

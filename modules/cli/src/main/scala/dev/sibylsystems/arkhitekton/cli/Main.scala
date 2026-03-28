package dev.sibylsystems.arkhitekton.cli

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*

import com.monovore.decline.*
import com.monovore.decline.effect.CommandIOApp

import dev.sibylsystems.arkhitekton.core.*

object Main extends CommandIOApp(
      name = "arkhitekton",
      header = "Round-trip spec iteration with a mechanical referee.",
      version = "0.1.0",
    ):

  // ---------------------------------------------------------------------------
  // CLI options
  // ---------------------------------------------------------------------------

  private val specFileArg: Opts[Option[String]] =
    Opts.argument[String]("spec-file").orNone

  private val onceFlag: Opts[Boolean] =
    Opts.flag("once", "Single pass, no interaction").orFalse

  private val modelOpt: Opts[String] =
    Opts.option[String]("model", "Claude model (sonnet, haiku, opus, or full ID)", "m")
      .withDefault(Models.defaultModel)

  private val idris2Opt: Opts[String] =
    Opts.option[String]("idris2", "Path to idris2 compiler")
      .withDefault("idris2")

  private val stepOpt: Opts[Option[String]] =
    Opts.option[String]("step", "Run a single step: readiness|triage|plan|formalize|compile|autofix|explain|back-translate")
      .orNone

  private val transpileFlag: Opts[Boolean] =
    Opts.flag("transpile", "After clean compile: fill holes, transpile to JS, generate tests, run them").orFalse

  private val workdirOpt: Opts[Option[String]] =
    Opts.option[String]("workdir", "Resume from an existing work directory (for --step)")
      .orNone

  private val backendOpt: Opts[Option[BackendFlag]] =
    Opts.flag("cc", "Force Claude Code CLI (claude) as the LLM backend").as(BackendFlag.ClaudeCli)
      .orElse(Opts.flag("ghc", "Force GitHub Copilot CLI (gh copilot) as the LLM backend").as(BackendFlag.CopilotCli))
      .orElse(Opts.flag("api", "Force Anthropic HTTP API (requires ANTHROPIC_API_KEY)").as(BackendFlag.AnthropicApi))
      .orNone

  // ---------------------------------------------------------------------------
  // Entry point
  // ---------------------------------------------------------------------------

  override def main: Opts[IO[ExitCode]] =
    (specFileArg, onceFlag, modelOpt, idris2Opt, stepOpt, transpileFlag, backendOpt, workdirOpt).mapN(run)

  private def run(
    specFile: Option[String],
    once: Boolean,
    model: String,
    idris2: String,
    step: Option[String],
    transpile: Boolean,
    backendFlag: Option[BackendFlag],
    workdirPath: Option[String],
  ): IO[ExitCode] =
    for
      apiKey  <- loadApiKey
      backend <- BackendSelector.resolve(backendFlag, apiKey)
      version <- Compiler.verify(idris2).flatMap {
                   case Some(v) => IO.pure(v)
                   case None => IO.raiseError(new RuntimeException(
                       s"idris2 compiler not found at: $idris2\nInstall via: pack install idris2",
                     ))
                 }
      // If --workdir is given, resume from that directory; otherwise create a new one
      (workDir, session) <- workdirPath match
                              case Some(wd) =>
                                val p = os.Path(wd, os.pwd)
                                StateIO.loadSession(p).map(s => (p, s)).handleErrorWith { _ =>
                                  // Workdir exists but no state — load spec fresh
                                  loadSpec(specFile).map(text => (p, Session(originalSpec = text, workDir = p)))
                                }
                              case None =>
                                for
                                  text <- loadSpec(specFile)
                                  wd   <- IO.blocking(os.Path(os.temp.dir(prefix = "arkhitekton-").toIO.getCanonicalPath))
                                  _    <- StateIO.saveSpec(wd, text)
                                yield (wd, Session(originalSpec = text, workDir = wd))
      resolvedModel = Models.resolve(model)
      _ <- IO.println(banner(version, resolvedModel, workDir, session.originalSpec))
      exitCode <- step match
                    case Some(s)           => Runner.singleStep(s, session, backend, resolvedModel, idris2)
                    case None if transpile => Runner.transpile(session, backend, resolvedModel, idris2)
                    case None if once      => Runner.once(session, backend, resolvedModel, idris2)
                    case None              => Runner.interactive(session, backend, resolvedModel, idris2)
      _ <- IO.println(s"\nWork directory preserved at: $workDir")
    yield exitCode

  // ---------------------------------------------------------------------------
  // Startup helpers
  // ---------------------------------------------------------------------------

  private def loadApiKey: IO[Option[String]] = IO.blocking {
    sys.env.get("ANTHROPIC_API_KEY").filter(_.nonEmpty).orElse(loadDotEnv("ANTHROPIC_API_KEY"))
  }

  private def loadDotEnv(key: String): Option[String] =
    val envFile = os.pwd / ".env"
    Option.when(os.exists(envFile))(os.read(envFile)).flatMap { content =>
      content.linesIterator
        .map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2) match
            case Array(k, v) if k.trim == key =>
              Some(v.trim.stripPrefix("'").stripSuffix("'").stripPrefix("\"").stripSuffix("\""))
            case _ => None
        }
        .nextOption()
    }

  private def loadSpec(specFile: Option[String]): IO[String] =
    specFile match
      case Some(path) =>
        IO.blocking {
          val p = os.Path(path, os.pwd)
          if !os.exists(p) then throw new RuntimeException(s"Spec file not found: $path")
          else os.read(p)
        }.flatTap { text =>
          if text.trim.isEmpty then IO.raiseError(new RuntimeException(s"Spec file is empty: $path"))
          else IO.println(s"Loaded spec from: $path")
        }
      case None =>
        IO.println("Reading spec from stdin (Ctrl+D to finish)...") *>
          IO.blocking(scala.io.Source.stdin.mkString)

  // ---------------------------------------------------------------------------
  // Banner
  // ---------------------------------------------------------------------------

  private def banner(version: String, model: String, workDir: os.Path, spec: String): String =
    val sep = "=" * 50
    s"""
       |$sep
       |  arkhitekton — Round-Trip Spec Iteration
       |$sep
       |  Compiler: $version
       |  Model:    $model
       |  Work dir: $workDir
       |  Spec:     ${spec.linesIterator.size} lines
       |$sep""".stripMargin

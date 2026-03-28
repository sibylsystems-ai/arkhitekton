package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

/**
 * Serialize/deserialize pipeline state to the work directory.
 *
 * Each step writes structured text files to `state/` that subsequent steps can read. Files are designed to be
 * human-readable and trivially parseable — no JSON, just `key | value` lines.
 */
object StateIO:

  private def stateDir(workDir: os.Path): os.Path =
    val d = workDir / "state"
    os.makeDir.all(d)
    d

  // ---------------------------------------------------------------------------
  // Original spec
  // ---------------------------------------------------------------------------

  def saveSpec(workDir: os.Path, spec: String): IO[Unit] =
    IO.blocking(os.write.over(stateDir(workDir) / "original-spec.md", spec))

  def loadSpec(workDir: os.Path): IO[String] =
    IO.blocking(os.read(stateDir(workDir) / "original-spec.md"))

  // ---------------------------------------------------------------------------
  // Readiness assessment
  // ---------------------------------------------------------------------------

  def saveReadiness(workDir: os.Path, verdict: ReadinessVerdict): IO[Unit] = IO.blocking {
    val lines = List(
      s"kind | ${verdict.kind.label}",
      s"intent | ${verdict.intentSummary}",
      s"verdict | ${if verdict.isReady then "ready" else "not-ready"}",
    ) ++ (verdict match
      case ReadinessVerdict.NotReady(qs, _, _) => qs.map(q => s"question | $q")
      case _                                   => Nil
    )
    os.write.over(stateDir(workDir) / "readiness.txt", lines.mkString("\n"))
  }

  def loadReadiness(workDir: os.Path): IO[Option[ReadinessVerdict]] = IO.blocking {
    val path = stateDir(workDir) / "readiness.txt"
    Option.when(os.exists(path)) {
      val lines = os.read(path).linesIterator.toList
      val kvs   = lines.flatMap(l => l.split("\\|", 2) match
        case Array(k, v) => Some((k.trim, v.trim))
        case _           => None
      )
      val kind = kvs.collectFirst { case ("kind", v) => parseKind(v) }.getOrElse(SpecKind.Other("unknown"))
      val intent    = kvs.collectFirst { case ("intent", v) => v }.getOrElse("(no intent)")
      val isReady   = kvs.collectFirst { case ("verdict", v) => v }.exists(_ == "ready")
      val questions = kvs.collect { case ("question", v) => v }
      if isReady then ReadinessVerdict.Ready(intent, kind)
      else ReadinessVerdict.NotReady(questions, intent, kind)
    }
  }

  private def parseKind(s: String): SpecKind =
    s.toLowerCase match
      case "checker"        => SpecKind.Checker
      case "pipeline"       => SpecKind.Pipeline
      case "domain-model"   => SpecKind.DomainModel
      case "data-transform" => SpecKind.DataTransform
      case other =>
        val desc = if other.startsWith("other:") then other.stripPrefix("other:").trim else other
        SpecKind.Other(desc)

  // ---------------------------------------------------------------------------
  // Triage result
  // ---------------------------------------------------------------------------

  def saveTriage(workDir: os.Path, result: TriageResult): IO[Unit] = IO.blocking {
    val lines = result.sections.map { s =>
      val cat = s.category match
        case SectionCategory.Formalizable   => "formalizable"
        case SectionCategory.Intent         => "intent"
        case SectionCategory.Infrastructure => "infrastructure"
      s"${s.heading} | $cat | ${s.lineRange._1}-${s.lineRange._2}"
    }
    os.write.over(stateDir(workDir) / "triage-sections.txt", lines.mkString("\n"))
    // Also save the full section content for reconstruction
    result.sections.zipWithIndex.foreach {
      case (s, i) =>
        os.write.over(stateDir(workDir) / f"section-${i + 1}%02d.md", s"${s.heading}\n\n${s.content}")
    }
  }

  def loadTriage(workDir: os.Path): IO[Option[TriageResult]] = IO.blocking {
    val path = stateDir(workDir) / "triage-sections.txt"
    Option.when(os.exists(path)) {
      val lines = os.read(path).linesIterator.toList
      val sections = lines.zipWithIndex.flatMap {
        case (line, i) =>
          line.split("\\|").map(_.trim) match
            case Array(heading, cat, range) =>
              val category = cat match
                case "formalizable"   => SectionCategory.Formalizable
                case "intent"         => SectionCategory.Intent
                case "infrastructure" => SectionCategory.Infrastructure
                case _                => SectionCategory.Formalizable
              val lr        = range.split("-").map(_.trim.toInt)
              val lineRange = if lr.length >= 2 then (lr(0), lr(1)) else (0, 0)
              // Load section content
              val contentPath = stateDir(workDir) / f"section-${i + 1}%02d.md"
              val fullContent = if os.exists(contentPath) then os.read(contentPath) else ""
              val content     = fullContent.linesIterator.drop(1).mkString("\n").trim // skip heading line
              Some(SpecSection(heading, content, category, lineRange))
            case _ => None
      }
      TriageResult(sections)
    }
  }

  // ---------------------------------------------------------------------------
  // Module plan
  // ---------------------------------------------------------------------------

  def saveModulePlan(workDir: os.Path, plans: List[ModulePlan]): IO[Unit] = IO.blocking {
    val lines = plans.map { p =>
      val deps     = if p.dependsOn.isEmpty then "none" else p.dependsOn.mkString(", ")
      val sections = p.sections.map(_.heading).mkString(", ")
      s"${p.moduleName} | $deps | $sections"
    }
    os.write.over(stateDir(workDir) / "module-plan.txt", lines.mkString("\n"))
  }

  def loadModulePlan(workDir: os.Path, triage: TriageResult): IO[Option[List[ModulePlan]]] = IO.blocking {
    val path = stateDir(workDir) / "module-plan.txt"
    Option.when(os.exists(path)) {
      val sectionsByHeading = triage.formalizable.groupBy(_.heading.replaceAll("^#+\\s*", "").trim.toLowerCase)
      os.read(path).linesIterator.flatMap { line =>
        line.split("\\|").map(_.trim) match
          case Array(name, deps, headings) =>
            val dependsOn = deps match
              case "none" | "(none)" | "" => Nil
              case other                  => other.split(",").map(_.trim).toList
            val matched = headings.split(",").map(_.trim.toLowerCase).toList.flatMap { h =>
              sectionsByHeading.find { case (k, _) => k.contains(h) || h.contains(k) }.toList.flatMap(_._2)
            }
            Some(ModulePlan(name, matched, dependsOn))
          case _ => None
      }.toList
    }.filter(_.nonEmpty)
  }

  // ---------------------------------------------------------------------------
  // Module sources (formalize output)
  // ---------------------------------------------------------------------------

  def saveModules(workDir: os.Path, modules: List[ModuleResult]): IO[Unit] = IO.blocking {
    val idx = modules.map(m => s"${m.moduleName} | ${m.idrisSource.linesIterator.size} lines").mkString("\n")
    os.write.over(stateDir(workDir) / "modules-index.txt", idx)
  }

  def loadModules(workDir: os.Path): IO[Option[List[ModuleResult]]] = IO.blocking {
    val indexPath = stateDir(workDir) / "modules-index.txt"
    if !os.exists(indexPath) then
      // Try single-module fallback
      val specIdr = workDir / "Spec.idr"
      Option.when(os.exists(specIdr))(Nil) // empty list = single module
    else
      val moduleNames =
        os.read(indexPath).linesIterator.flatMap(line => line.split("\\|").headOption.map(_.trim)).toList
      val results = moduleNames.flatMap { name =>
        val parts = name.split('.')
        val path  = parts.foldLeft(workDir)(_ / _).toString + ".idr"
        val p     = os.Path(path)
        Option.when(os.exists(p))(ModuleResult(name, os.read(p)))
      }
      Some(results).filter(_.nonEmpty)
  }

  // ---------------------------------------------------------------------------
  // Compile result
  // ---------------------------------------------------------------------------

  def saveCompileResult(workDir: os.Path, result: CompilerResult): IO[Unit] = IO.blocking {
    val outcome = result.outcome match
      case CompilerOutcome.Clean     => "clean"
      case CompilerOutcome.Holes(h)  => s"holes:${h.size}"
      case CompilerOutcome.Errors(e) => s"errors:${e.size}"
    os.write.over(stateDir(workDir) / "compile-outcome.txt", s"$outcome\n\n${result.rawOutput}")
  }

  // ---------------------------------------------------------------------------
  // Reconstruct a Session from a work directory
  // ---------------------------------------------------------------------------

  def loadSession(workDir: os.Path): IO[Session] =
    for
      spec      <- loadSpec(workDir)
      readiness <- loadReadiness(workDir)
      triage    <- loadTriage(workDir)
      modules   <- loadModules(workDir)
      idrisSource = modules match
                      case Some(ms) if ms.nonEmpty => Some(ms.map(_.idrisSource).mkString("\n\n"))
                      case _ =>
                        val p = workDir / "Spec.idr"
                        Option.when(os.exists(p))(os.read(p))
    yield Session(
      originalSpec = spec,
      workDir = workDir,
      triageResult = triage,
      intentContext = triage.flatMap(t => Option.when(t.intent.nonEmpty)(t.intentText)),
      infraContext = triage.flatMap(t => Option.when(t.infrastructure.nonEmpty)(t.infrastructureText)),
      modules = modules.getOrElse(Nil),
      lastIdris = idrisSource,
      readiness = readiness,
    )

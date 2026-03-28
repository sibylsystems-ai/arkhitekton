package dev.sibylsystems.arkhitekton

import cats.effect.IO

/** Plans how to split formalizable spec sections into Idris 2 modules. */
object ModulePlanner:

  /** Minimum formalizable sections to trigger multi-module splitting. */
  val MinSectionsForSplit: Int = 3

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Plan modules from triage result. Returns Nil if single-module is sufficient. */
  def plan(
    triage: TriageResult,
    backend: LlmBackend,
    model: String,
  ): IO[List[ModulePlan]] =
    val formalizable = triage.formalizable
    if formalizable.size < MinSectionsForSplit then
      // Too few sections — use single-module path
      IO.pure(Nil)
    else
      val summaries =
        formalizable.map(s => s"### ${s.heading}\n${s.content.linesIterator.take(5).mkString("\n")}").mkString(
          "\n\n",
        )
      for
        raw <- backend.complete(
                 model = model,
                 maxTokens = 2048,
                 system = Some(Prompts.modulePlanSystem),
                 messages = List(ClaudeMessage("user", Prompts.modulePlanUser(summaries))),
               )
        plans = parsePlan(raw, formalizable)
      yield if plans.size <= 1 then Nil else plans

  // ---------------------------------------------------------------------------
  // Topological sort for dependency-ordered execution
  // ---------------------------------------------------------------------------

  /** Sort modules so dependencies come before dependents. Group independent modules. */
  def topologicalBatches(plans: List[ModulePlan]): List[List[ModulePlan]] =
    @scala.annotation.tailrec
    def loop(
      remaining: List[ModulePlan],
      resolved: Set[String],
      acc: List[List[ModulePlan]],
    ): List[List[ModulePlan]] =
      if remaining.isEmpty then acc.reverse
      else
        val (ready, blocked) = remaining.partition(_.dependsOn.forall(resolved.contains))
        if ready.isEmpty then
          // Circular dependency or unresolvable — just process all remaining
          acc.reverse :+ remaining
        else
          val newResolved = resolved ++ ready.map(_.moduleName)
          loop(blocked, newResolved, ready :: acc)

    loop(plans, Set.empty, Nil)

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private val PlanLinePattern = """(.+?)\s*\|\s*(.+?)\s*\|\s*(.+)""".r

  private def parsePlan(raw: String, sections: List[SpecSection]): List[ModulePlan] =
    val sectionsByHeading = sections.groupBy(_.heading.replaceAll("^#+\\s*", "").trim.toLowerCase)

    raw.linesIterator.collect {
      case PlanLinePattern(name, deps, headings) =>
        val moduleName = name.trim
        val dependsOn = deps.trim match
          case "none" | "(none)" | "" => Nil
          case other                  => other.split(",").map(_.trim).toList
        val sectionHeadings = headings.split(",").map(_.trim.toLowerCase).toList
        val matchedSections = sectionHeadings.flatMap { h =>
          sectionsByHeading.find { case (k, _) => k.contains(h) || h.contains(k) }.toList.flatMap(_._2)
        }
        ModulePlan(moduleName, matchedSections, dependsOn)
    }.toList.filter(_.moduleName.nonEmpty)

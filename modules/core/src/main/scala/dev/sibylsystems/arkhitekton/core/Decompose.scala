package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

/**
 * Extract a definition manifest from an English spec and topologically sort it.
 *
 * The LLM identifies definitions and their dependencies (cheap Haiku call).
 * Scala guarantees the ordering is correct via topological sort.
 */
object Decompose:

  def run(
    specText: String,
    backend: LlmBackend,
    model: String,
  ): IO[DefinitionManifest] =
    for
      raw     <- callLlm(specText, backend, model)
      entries  = parseEntries(raw)
      sorted  <- topoSort(entries)
    yield DefinitionManifest(sorted)

  private def callLlm(specText: String, backend: LlmBackend, model: String): IO[String] =
    backend.complete(
      model = model,
      maxTokens = 2048,
      system = Some(Prompts.decomposeSystem),
      messages = List(ClaudeMessage("user", Prompts.decomposeUser(specText))),
    )

  // ---------------------------------------------------------------------------
  // Parsing
  // ---------------------------------------------------------------------------

  private def parseEntries(raw: String): List[DefinitionEntry] =
    raw.linesIterator
      .map(_.trim)
      .filterNot(l => l.isEmpty || l.startsWith("```") || l.startsWith("name"))
      .flatMap(parseLine)
      .toList

  private def parseLine(line: String): Option[DefinitionEntry] =
    line.split("\\|").map(_.trim) match
      case Array(name, kind, deps, desc) =>
        Some(DefinitionEntry(
          name = name,
          kind = parseKind(kind),
          dependsOn = parseDeps(deps),
          description = desc,
        ))
      case parts if parts.length >= 4 =>
        Some(DefinitionEntry(
          name = parts(0),
          kind = parseKind(parts(1)),
          dependsOn = parseDeps(parts(2)),
          description = parts.drop(3).mkString("|").trim,
        ))
      case _ => None

  private def parseKind(s: String): DefinitionKind =
    s.toLowerCase match
      case "enum"                     => DefinitionKind.Enum
      case "record"                   => DefinitionKind.Record
      case "function"                 => DefinitionKind.Function
      case "type-alias" | "typealias" => DefinitionKind.TypeAlias
      case _                          => DefinitionKind.Record // safe default

  private def parseDeps(s: String): List[String] =
    s.trim match
      case "(none)" | "none" | "" | "-" => Nil
      case other => other.split(",").map(_.trim).filter(_.nonEmpty).toList

  // ---------------------------------------------------------------------------
  // Topological sort with cycle detection
  // ---------------------------------------------------------------------------

  private def topoSort(entries: List[DefinitionEntry]): IO[List[DefinitionEntry]] =
    val byName = entries.map(e => e.name -> e).toMap
    val names  = entries.map(_.name).toSet

    // Only consider deps that reference known names in the manifest
    def validDeps(e: DefinitionEntry): List[String] =
      e.dependsOn.filter(names.contains)

    // Kahn's algorithm
    val inDegree = scala.collection.mutable.Map.from(entries.map(e => e.name -> validDeps(e).size))
    val adj      = scala.collection.mutable.Map.empty[String, List[String]].withDefaultValue(Nil)
    entries.foreach { e =>
      validDeps(e).foreach { dep =>
        adj(dep) = e.name :: adj(dep)
      }
    }

    val queue  = scala.collection.mutable.Queue.from(entries.map(_.name).filter(inDegree(_) == 0))
    val result = scala.collection.mutable.ListBuffer.empty[DefinitionEntry]

    while queue.nonEmpty do
      val name = queue.dequeue()
      byName.get(name).foreach(result += _)
      adj(name).foreach { next =>
        inDegree(next) -= 1
        if inDegree(next) == 0 then queue.enqueue(next)
      }

    val sorted = result.toList
    if sorted.size < entries.size then
      val missing = names -- sorted.map(_.name).toSet
      IO.println(s"    ${Terminal.yellow("⚠")} Circular dependencies detected: ${missing.mkString(", ")}") *>
        IO.println(s"      Appending them at the end — the compiler may reject these.") *>
        IO.pure(sorted ++ entries.filter(e => missing.contains(e.name)))
    else
      IO.pure(sorted)

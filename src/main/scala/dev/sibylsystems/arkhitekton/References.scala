package dev.sibylsystems.arkhitekton

/** Registry of loadable reference documents for tool-based retrieval. */
object References:

  /** Available reference documents the model can request. */
  val catalog: Map[String, String] = Map(
    "idris2-syntax"   -> "Idris 2 syntax reference: types, records, ADTs, interfaces, typed holes, IO, do-notation, totality",
    "io-patterns"     -> "Rules for classifying English phrases as IO vs pure operations (database, queue, API, file)",
    "js-backend"      -> "Idris 2 JS backend: %export directive, DCE, ADT representations, interop cheat sheet",
    "idiomatic-style" -> "Idiomatic Idris 2 patterns: total functions, explicit types, Maybe/Either, avoid believe_me",
    "js-type-mapping" -> "Idris 2 to TypeScript type mapping: primitives, ADTs, Maybe, Either, List, records, functions",
    "test-generation" -> "Guide for generating vitest tests from Idris 2 specs: law tests, boundary tests, property tests",
  )

  private def loadResource(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/$name"))
    stream.fold(s"[MISSING RESOURCE: $name]")(s => scala.io.Source.fromInputStream(s).mkString)

  /** Resolve a document name to its content. */
  def resolve(document: String): Option[String] =
    document match
      case "idris2-syntax"   => Some(loadResource("idris2-syntax-reference.md"))
      case "io-patterns"     => Some(loadResource("io-inference-patterns.md"))
      case "js-backend"      => Some(loadResource("js-backend-reference.md"))
      case "idiomatic-style" => Some(loadResource("idiomatic-style-reference.md"))
      case "js-type-mapping" => Some(loadResource("js-type-mapping-reference.md"))
      case "test-generation" => Some(loadResource("test-generation-guide.md"))
      case _                 => None

  /**
   * All reference content concatenated — used by CLI backends that cannot do
   * tool-calling round-trips and instead inline all docs into the system prompt.
   */
  def allContent: String =
    catalog.keys.toList.sorted
      .flatMap(k => resolve(k).map(content => s"## $k\n\n$content"))
      .mkString("\n\n---\n\n")

  /** The tool definition sent to the Claude API. */
  val toolDefinition: io.circe.Json =
    import io.circe.syntax.*
    io.circe.Json.obj(
      "name" -> "get_reference".asJson,
      "description" -> "Load a reference document for Idris 2 development. Call this when you need syntax details, IO classification rules, JS backend specifics, or style guidance."
        .asJson,
      "input_schema" -> io.circe.Json.obj(
        "type" -> "object".asJson,
        "properties" -> io.circe.Json.obj(
          "document" -> io.circe.Json.obj(
            "type"        -> "string".asJson,
            "enum"        -> catalog.keys.toList.sorted.asJson,
            "description" -> catalog.map((k, v) => s"$k: $v").mkString("\n").asJson,
          ),
        ),
        "required" -> List("document").asJson,
      ),
    )

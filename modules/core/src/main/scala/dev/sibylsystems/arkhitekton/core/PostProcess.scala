package dev.sibylsystems.arkhitekton.core

import scala.util.matching.Regex

import cats.effect.IO

/** Post-processes the raw JS output from idris2 --cg node into a usable module. */
object PostProcess:

  // %export "javascript:name" generates top-level: function name($0) { ... }
  // Internal Idris functions use: function Module_functionName($0) { ... }
  // Prelude functions use: function Prelude_Module_name($0) { ... }
  // Runtime functions use: function __name or const _name
  // Lazy values: const name = __lazy(function () { ... });
  private val FunctionDecl: Regex = """^function (\w+)\(.*""".r
  private val LazyConst: Regex    = """^const (\w+) = __lazy.*""".r
  private val InternalName: Regex = """^\w+_\w+$""".r // Module_name pattern (contains underscore)
  private val RuntimeName: Regex  = """^(__\w+|_\w+|IdrisError)$""".r

  /**
   * Extract the names of exported (user-facing) functions from the generated JS.
   *
   * %export "javascript:name" creates clean top-level names (no underscores in the prefix).
   * Internal Idris functions follow the Module_functionName convention.
   * We keep only names that DON'T match the internal Module_name pattern.
   */
  def extractExports(jsContent: String): List[String] =
    jsContent.linesIterator.flatMap { line =>
      val trimmed = line.trim
      val name = trimmed match
        case FunctionDecl(n) => Some(n)
        case LazyConst(n)    => Some(n)
        case _               => None
      // Keep only clean names: no Module_func pattern, no runtime __names
      name.filterNot(n => InternalName.matches(n) || RuntimeName.matches(n))
    }.toList

  /** Append module.exports to the JS file for the exported functions. */
  def appendExports(jsContent: String, exports: List[String]): String =
    if exports.isEmpty then jsContent
    else
      val exportLines = exports.map(name => s"  $name,").mkString("\n")
      // Remove the self-executing main at the bottom if present
      val cleaned = jsContent.replaceAll("""(?s)\ntry\{__mainExpression.*""", "")
      cleaned + s"\n\nmodule.exports = {\n$exportLines\n};\n"

  /** Full post-processing pipeline: extract exports, append module.exports, write. */
  def process(rawJsPath: os.Path, outputDir: os.Path): IO[(os.Path, List[String])] =
    IO.blocking {
      val content   = os.read(rawJsPath)
      val exports   = extractExports(content)
      val processed = appendExports(content, exports)
      val outPath   = outputDir / "spec.js"
      os.write.over(outPath, processed)
      (outPath, exports)
    }

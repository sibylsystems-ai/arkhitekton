package dev.sibylsystems.arkhitekton.cli

import cats.effect.IO

import munit.CatsEffectSuite

import dev.sibylsystems.arkhitekton.core.*

/**
 * Integration smoke test for the transpile pipeline.
 *
 * Requires `idris2` and `node` in PATH. Skipped automatically if either is absent,
 * so it is safe to run in CI environments that don't have these tools installed.
 */
class TranspileSuite extends CatsEffectSuite:

  private val sampleIdris: String =
    """|module Spec
       |
       |record Score where
       |  constructor MkScore
       |  value : Double
       |
       |data Priority = High | Medium | Low | Deferred | Optional
       |
       |weight : Priority -> Double
       |weight High     = 0.30
       |weight Medium   = 0.25
       |weight Low      = 0.20
       |weight Deferred = 0.15
       |weight Optional = 0.10
       |
       |combine : Score -> Score -> Score
       |combine (MkScore a) (MkScore b) = MkScore (min 1.0 (a + b))
       |
       |baseline : Score
       |baseline = MkScore 0.0
       |
       |%export "javascript:weight"
       |jsWeight : Priority -> Double
       |jsWeight = weight
       |
       |%export "javascript:combine"
       |jsCombine : Score -> Score -> Score
       |jsCombine = combine
       |
       |%export "javascript:baseline"
       |jsBaseline : Score
       |jsBaseline = baseline
       |
       |main : IO ()
       |main = pure ()
       |""".stripMargin

  private def commandExists(name: String): Boolean =
    scala.sys.process.Process(Seq("which", name)).! == 0

  test("idris2 → JS compilation produces a module with expected exports") {
    assume(commandExists("idris2"), "idris2 not in PATH — skipping integration test")
    assume(commandExists("node"), "node not in PATH — skipping integration test")

    for
      workDir                  <- IO.blocking(os.Path(os.temp.dir(prefix = "transpile-test-").toIO.getCanonicalPath))
      jsPath                   <- Compiler.compileToJS(sampleIdris, workDir)
      (processedPath, exports) <- PostProcess.process(jsPath, workDir)

      // All three %export declarations must survive DCE and post-processing
      _ <- IO(assert(exports.contains("weight"), s"missing export 'weight' in $exports"))
      _ <- IO(assert(exports.contains("combine"), s"missing export 'combine' in $exports"))
      _ <- IO(assert(exports.contains("baseline"), s"missing export 'baseline' in $exports"))

      // The generated module must be loadable and callable from Node
      nodeResult <- IO.blocking {
                      os.proc(
                        "node",
                        "-e",
                        s"""|const m = require('${processedPath}');
                            |if (typeof m.weight   !== 'function') throw new Error('weight not a function');
                            |if (typeof m.combine  !== 'function') throw new Error('combine not a function');
                            |const w = m.weight(0);
                            |if (typeof w !== 'number') throw new Error('weight(0) returned ' + typeof w);
                            |""".stripMargin,
                      ).call(check = false, timeout = 10_000)
                    }
      _ <- IO(assertEquals(nodeResult.exitCode, 0, nodeResult.err.text().take(500)))
    yield ()
  }

package dev.sibylsystems.arkhitekton

import cats.effect.IO

/** Runs vitest in the work directory and parses the results. */
object TestRunner:

  private val InstallTimeout: Long = 120_000 // ms
  private val RunTimeout: Long     = 60_000  // ms

  private val MinimalPackageJson: String =
    """{
      |  "private": true,
      |  "type": "commonjs",
      |  "scripts": { "test": "vitest run" }
      |}""".stripMargin

  private val VitestConfig: String =
    """import { defineConfig } from 'vitest/config';
      |export default defineConfig({
      |  test: { globals: true }
      |});""".stripMargin

  /** Run vitest in the given directory. Installs dependencies if needed. */
  def run(workDir: os.Path): IO[TestResult] =
    for
      _      <- ensureNodeProject(workDir)
      result <- runVitest(workDir)
    yield result

  private def ensureNodeProject(workDir: os.Path): IO[Unit] = IO.blocking {
    if !os.exists(workDir / "package.json") then os.write(workDir / "package.json", MinimalPackageJson)
    if !os.exists(workDir / "vitest.config.mjs") then os.write(workDir / "vitest.config.mjs", VitestConfig)
    if !os.exists(workDir / "node_modules") then
      val _ = os.proc("npm", "install", "--save-dev", "vitest")
        .call(cwd = workDir, check = false, timeout = InstallTimeout)
  }

  private def runVitest(workDir: os.Path): IO[TestResult] = IO.blocking {
    val result = os.proc("npx", "vitest", "run", "--reporter=verbose")
      .call(cwd = workDir, check = false, timeout = RunTimeout)

    val output  = result.out.text() + "\n" + result.err.text()
    val passed  = """(\d+) passed""".r.findFirstMatchIn(output).map(_.group(1).toInt).getOrElse(0)
    val failed  = """(\d+) failed""".r.findFirstMatchIn(output).map(_.group(1).toInt).getOrElse(0)
    val testDir = workDir

    // Find the test file (could be .test.ts or .test.js)
    val testPath = os.list(testDir).find(_.last.contains(".test.")).getOrElse(testDir / "spec.test.ts")

    TestResult(
      testPath = testPath,
      passed = passed,
      failed = failed,
      rawOutput = output.trim,
    )
  }

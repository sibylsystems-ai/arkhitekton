package dev.sibylsystems.arkhitekton.cli

import cats.effect.{IO, Resource}

import io.circe.Json

import dev.sibylsystems.arkhitekton.core.*

// ---------------------------------------------------------------------------
// Claude Code CLI backend — claude -p
// ---------------------------------------------------------------------------

object ClaudeCliBackend:

  def apply(claudeBin: String = "claude"): LlmBackend = new LlmBackend:

    def complete(
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json] = Nil,
    ): IO[String] =
      val sysContent = CliHelpers.buildSystemWithRefs(system, tools)
      val userPrompt = CliHelpers.flattenMessages(messages)
      // Use Resource so the temp file is always cleaned up, even on cancellation.
      CliHelpers.tempFile(sysContent).use { sysFile =>
        IO.blocking {
          // NOTE: --bare is intentionally omitted — it disables OAuth/keychain auth,
          // breaking users authenticated via `claude /login`. -p alone is non-interactive.
          val base = Seq(claudeBin, "--no-session-persistence", "-p", "--output-format", "text", "--model", model)
          val args = base ++ sysFile.map(f => Seq("--system-prompt-file", f.toString)).getOrElse(Seq.empty)
          val res  = os.proc(args).call(stdin = userPrompt, check = false)
          if res.exitCode != 0 then
            val detail = Seq(res.err.text(), res.out.text()).map(_.trim).filter(_.nonEmpty).mkString(" | ")
            throw new RuntimeException(s"claude CLI failed (exit ${res.exitCode}): ${detail.take(500)}")
          res.out.text().trim
        }
      }

// ---------------------------------------------------------------------------
// GitHub Copilot CLI backend — gh copilot -- -p
// ---------------------------------------------------------------------------

object CopilotCliBackend:

  def apply(): LlmBackend = new LlmBackend:

    def complete(
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json] = Nil,
    ): IO[String] =
      IO.blocking {
        // gh copilot has no --system-prompt flag: prepend instructions to the prompt.
        val sysPrefix  = CliHelpers.buildSystemWithRefs(system, tools).map(s => s"[Instructions]\n$s\n\n").getOrElse("")
        val userPrompt = sysPrefix + CliHelpers.flattenMessages(messages)
        // --allow-all-tools is required for non-interactive (-p) mode.
        val args = Seq("gh", "copilot", "--", "-p", userPrompt, "--allow-all-tools", "--model", model)
        val res  = os.proc(args).call(check = false)
        if res.exitCode != 0 then
          throw new RuntimeException(s"gh copilot failed (exit ${res.exitCode}): ${res.err.text().take(500)}")
        stripStats(res.out.text())
      }

  // The copilot CLI appends a usage/stats footer after the response; strip it.
  private def stripStats(output: String): String =
    val idx = output.indexOf("\n\nTotal usage est:")
    if idx >= 0 then output.substring(0, idx).trim else output.trim

// ---------------------------------------------------------------------------
// Shared helpers for CLI backends
// ---------------------------------------------------------------------------

private object CliHelpers:

  /** When tools are requested, inline all reference docs into the system prompt. */
  def buildSystemWithRefs(system: Option[String], tools: List[Json]): Option[String] =
    if tools.isEmpty then system
    else
      val refs = s"## Reference Documents\n\n${References.allContent}"
      Some(system.fold(refs)(s => s"$s\n\n$refs"))

  /** Flatten a message list to a single string for CLI consumption. */
  def flattenMessages(messages: List[ClaudeMessage]): String =
    messages match
      case List(single) => single.content
      case multiple     => multiple.map(m => s"[${m.role}]\n${m.content}").mkString("\n\n")

  /** A Resource that writes content to a temp file and deletes it on release. */
  def tempFile(content: Option[String]): Resource[IO, Option[os.Path]] =
    Resource.make(
      IO.blocking(content.map(s => os.temp(s, suffix = ".txt"))),
    )(sf => IO.blocking(sf.foreach(os.remove)))

// ---------------------------------------------------------------------------
// Backend selection
// ---------------------------------------------------------------------------

enum BackendFlag:
  case ClaudeCli, CopilotCli, AnthropicApi

object BackendSelector:

  /**
   * Resolve the backend to use.
   *
   * A forced flag takes priority. In default mode the order is:
   * claude CLI -> gh copilot CLI -> Anthropic HTTP API.
   * The API key is only required when the HTTP backend is used.
   */
  def resolve(flag: Option[BackendFlag], apiKey: Option[String]): IO[LlmBackend] =
    flag match
      case Some(BackendFlag.ClaudeCli)  => IO.pure(ClaudeCliBackend())
      case Some(BackendFlag.CopilotCli) => IO.pure(CopilotCliBackend())
      case Some(BackendFlag.AnthropicApi) =>
        IO.fromOption(apiKey)(
          new RuntimeException(
            "--api requires ANTHROPIC_API_KEY. Set it in .env or export ANTHROPIC_API_KEY='sk-ant-...'",
          ),
        ).map(AnthropicHttpBackend(_))
      case None => autoDetect(apiKey)

  private def autoDetect(apiKey: Option[String]): IO[LlmBackend] =
    for
      hasClaude    <- commandExists("claude")
      hasGhCopilot <- commandExists("gh")
      backend <- (hasClaude, hasGhCopilot, apiKey) match
                   case (true, _, _) =>
                     IO.println("  [backend] claude (Claude Code CLI)").as(ClaudeCliBackend())
                   case (_, true, _) =>
                     IO.println("  [backend] gh copilot (GitHub Copilot CLI)").as(CopilotCliBackend())
                   case (_, _, Some(key)) =>
                     IO.println("  [backend] Anthropic HTTP API").as(AnthropicHttpBackend(key))
                   case _ =>
                     IO.raiseError(new RuntimeException(
                       "No LLM backend found. Install claude or gh-copilot, or set ANTHROPIC_API_KEY.",
                     ))
    yield backend

  private def commandExists(name: String): IO[Boolean] =
    IO.blocking(scala.sys.process.Process(Seq("which", name)).! == 0)

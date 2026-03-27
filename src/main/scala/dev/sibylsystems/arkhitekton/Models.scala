package dev.sibylsystems.arkhitekton

/**
 * Model aliases and resolution.
 *
 * Short names like "sonnet" or "haiku" resolve to full model IDs.
 * Full model IDs pass through unchanged.
 * Lightweight pipeline steps (readiness, triage, planning, explain)
 * automatically use Haiku to save tokens.
 */
object Models:

  private val aliases: Map[String, String] = Map(
    "sonnet" -> "claude-sonnet-4-6-20250514",
    "haiku"  -> "claude-haiku-4-5-20251001",
    "opus"   -> "claude-opus-4-6-20250918",
  )

  val defaultModel: String = "sonnet"
  val fastModel: String    = "haiku"

  /** Resolve a short alias or pass through a full model ID. */
  def resolve(nameOrId: String): String =
    aliases.getOrElse(nameOrId.toLowerCase, nameOrId)

  /** The cheap model for lightweight steps — always Haiku. */
  val fast: String = resolve(fastModel)

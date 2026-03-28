package dev.sibylsystems.arkhitekton.core

/** ANSI terminal formatting helpers. No-op when output is piped or terminal is dumb. */
object Terminal:

  private val enabled: Boolean =
    System.console() != null && sys.env.get("TERM").exists(_ != "dumb")

  // Primitives
  def bold(s: String): String   = if enabled then s"\u001b[1m$s\u001b[0m" else s
  def dim(s: String): String    = if enabled then s"\u001b[2m$s\u001b[0m" else s
  def italic(s: String): String = if enabled then s"\u001b[3m$s\u001b[0m" else s
  def cyan(s: String): String   = if enabled then s"\u001b[36m$s\u001b[0m" else s
  def yellow(s: String): String = if enabled then s"\u001b[33m$s\u001b[0m" else s
  def green(s: String): String  = if enabled then s"\u001b[32m$s\u001b[0m" else s
  def red(s: String): String    = if enabled then s"\u001b[31m$s\u001b[0m" else s

  // Composites
  def label(key: String, value: String): String  = s"  ${bold(key + ":")} $value"
  def ok(msg: String): String                    = s"$msg ${green("✓")}"
  def warn(msg: String): String                  = yellow(msg)
  def err(msg: String): String                   = red(msg)
  def numberedItem(n: Int, text: String): String = s"  ${cyan(s"$n.")} $text"

  // Box drawing
  def box(title: String, lines: List[String], width: Int = 50): String =
    val top  = s"╭─ ${bold(title)} ${"─" * math.max(0, width - title.length - 5)}╮"
    val bot  = s"╰${"─" * (width - 2)}╯"
    val body = lines.map(l => s"  $l").mkString("\n")
    s"$top\n\n$body\n\n$bot"

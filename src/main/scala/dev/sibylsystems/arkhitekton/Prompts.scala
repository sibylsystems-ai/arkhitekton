package dev.sibylsystems.arkhitekton

// Prompt templates for each pipeline step.
// Text lives in src/main/resources/prompts/*.md.
// User-message templates use {placeholder} substitution.
object Prompts:

  // ---------------------------------------------------------------------------
  // 0. Readiness: assess whether spec is ready to formalize
  // ---------------------------------------------------------------------------

  val readinessSystem: String = load("readiness-system.md")

  def readinessUser(spec: String): String =
    load("readiness-user.md").replace("{spec}", spec)

  // ---------------------------------------------------------------------------
  // 0b. Decompose: extract definition manifest from spec
  // ---------------------------------------------------------------------------

  val decomposeSystem: String = load("decompose-system.md")

  def decomposeUser(spec: String): String =
    load("decompose-user.md").replace("{spec}", spec)

  // ---------------------------------------------------------------------------
  // 0c. Triage: classify spec sections
  // ---------------------------------------------------------------------------

  val triageSystem: String = load("triage-system.md")

  def triageUser(sections: String): String =
    load("triage-user.md").replace("{sections}", sections)

  // ---------------------------------------------------------------------------
  // 0b. Module planning: split formalizable sections into modules
  // ---------------------------------------------------------------------------

  val modulePlanSystem: String = load("module-plan-system.md")

  def modulePlanUser(sections: String): String =
    load("module-plan-user.md").replace("{sections}", sections)

  // ---------------------------------------------------------------------------
  // 1. Formalize: English → Idris 2
  // ---------------------------------------------------------------------------

  val formalizeSystem: String = load("formalize-system.md")

  def formalizeUser(spec: String): String =
    load("formalize-user.md").replace("{spec}", spec)

  def formalizeFollowup(spec: String, answers: List[String], feedback: String): String =
    val answersText = if answers.isEmpty then "(none)" else answers.map(a => s"- $a").mkString("\n")
    load("formalize-followup.md")
      .replace("{spec}", spec)
      .replace("{answers}", answersText)
      .replace("{feedback}", feedback)

  // ---------------------------------------------------------------------------
  // 1b. Formalize module: English → single Idris 2 module (multi-module mode)
  // ---------------------------------------------------------------------------

  val formalizeModuleSystem: String = load("formalize-module-system.md")

  def formalizeModuleUser(
    moduleName: String,
    dependencies: String,
    signatures: String,
    sections: String,
  ): String =
    load("formalize-module-user.md")
      .replace("{moduleName}", moduleName)
      .replace("{dependencies}", dependencies)
      .replace("{signatures}", signatures)
      .replace("{sections}", sections)

  // ---------------------------------------------------------------------------
  // 2. Explain Holes: typed holes → SME questions
  // ---------------------------------------------------------------------------

  val explainHolesSystem: String = load("explain-holes-system.md")

  def explainHolesUser(source: String, holes: String): String =
    load("explain-holes-user.md")
      .replace("{source}", source)
      .replace("{holes}", holes)

  // ---------------------------------------------------------------------------
  // 2b. Auto-fix: LLM fixes its own structural mistakes
  // ---------------------------------------------------------------------------

  val autoFixSystem: String = load("auto-fix-system.md")

  def autoFixUser(source: String, errors: String): String =
    load("auto-fix-user.md")
      .replace("{source}", source)
      .replace("{errors}", errors)

  // ---------------------------------------------------------------------------
  // 3. Explain Errors: type errors → English explanation
  // ---------------------------------------------------------------------------

  val explainErrorsSystem: String = load("explain-errors-system.md")

  def explainErrorsUser(source: String, errors: String): String =
    load("explain-errors-user.md")
      .replace("{source}", source)
      .replace("{errors}", errors)

  // ---------------------------------------------------------------------------
  // 4. Back-Translate: clean Idris 2 → refined English spec
  // ---------------------------------------------------------------------------

  val backTranslateSystem: String = load("back-translate-system.md")

  def backTranslateUser(source: String, spec: String): String =
    load("back-translate-user.md")
      .replace("{source}", source)
      .replace("{spec}", spec)

  // ---------------------------------------------------------------------------
  // 5. Fill Holes: complete the implementation
  // ---------------------------------------------------------------------------

  val fillHolesSystem: String = load("fill-holes-system.md")

  def fillHolesUser(source: String, spec: String): String =
    load("fill-holes-user.md")
      .replace("{source}", source)
      .replace("{spec}", spec)

  // ---------------------------------------------------------------------------
  // 6. Generate .d.ts + adapter
  // ---------------------------------------------------------------------------

  val generateTypesSystem: String = load("generate-types-system.md")

  def generateTypesUser(source: String): String =
    load("generate-types-user.md").replace("{source}", source)

  // ---------------------------------------------------------------------------
  // 7. Generate tests
  // ---------------------------------------------------------------------------

  val generateTestsSystem: String = load("generate-tests-system.md")

  def generateTestsUser(source: String, adapter: String, spec: String): String =
    load("generate-tests-user.md")
      .replace("{source}", source)
      .replace("{adapter}", adapter)
      .replace("{spec}", spec)

  // ---------------------------------------------------------------------------

  private def load(name: String): String =
    val stream = Option(getClass.getResourceAsStream(s"/prompts/$name"))
    stream.fold(s"[MISSING PROMPT: $name]")(s => scala.io.Source.fromInputStream(s).mkString.trim)

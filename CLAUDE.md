# CLAUDE.md — arkhitekton

Round-trip spec iteration and transpile pipeline.
English spec → Idris 2 (compiler-checked) → JavaScript → TypeScript tests.

@README.md for the full story, pipeline diagram, and usage.
@PLAN.md for implementation status and next steps.
@MISSING_MANUAL.md for Idris 2 gotchas (JS backend, DCE, ADT representations, etc.)
@IDRIS2-instructions.md for idiomatic Idris 2 coding guidance.

## Build & Run

```bash
sbt compile
sbt scalafmtAll
sbt "scalafix --check"
sbt "runMain dev.sibylsystems.speccheck.Main examples/crucible_spec_v2.md --transpile"
```

API key: set `ANTHROPIC_API_KEY` in `.env` (gitignored) or environment.

## Project Layout

```
src/main/scala/dev/sibylsystems/speccheck/
  Main.scala          CLI entry point (Decline CommandIOApp)
  Domain.scala        ADTs: CompilerOutcome, HoleInfo, Session, TranspileResult, TestResult
  Compiler.scala      idris2 --check and --cg node, REPL hole querying
  Claude.scala        Claude API client with tool-calling loop
  Pipeline.scala      Orchestration: formalize → compile → explain → fill → transpile → test
  Prompts.scala       System/user prompt templates for each LLM step
  References.scala    Tool definition + resolver for on-demand reference loading
  PostProcess.scala   JS post-processing: export extraction, module.exports injection
  TestRunner.scala    npm/vitest scaffolding and execution

src/main/resources/   Reference docs loaded via tool calls (not inlined in prompts)
```

## Conventions

Same patterns as event-catalog-verifier:
- ADTs for domain states (`CompilerOutcome` enum)
- `IO[A]` for all effects, no side effects in pure code
- No vars, no nulls, no throws (enforced by scalafix)
- Imports organized: `scala.` → `cats.` → `*`

## Key Design Decisions

- **Tool-based reference loading.** Prompts are short. The LLM calls `get_reference`
  to load syntax guides, IO patterns, etc. on demand.
- **`%export "javascript:name"`** prevents DCE and creates clean JS function names.
- **Triangle verification.** Implementation is compiled mechanically (idris2 --cg node).
  Tests are generated independently from the English spec + Idris types.
- **Original spec travels with the pipeline.** Both hole-filling and test generation
  receive the English spec. Types constrain shape, English constrains intent.

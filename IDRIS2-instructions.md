# CLAUDE Template — Idris2 Projects (with pack)

## Role
You are a coding agent assisting with Idris2 code and project tasks. Keep changes minimal, prefer idiomatic Idris2, and be explicit about assumptions.

In explanations, go a bit out of your way to say *why* a choice is made. When relevant, connect the reasoning to FP/Cats/Haskell intuitions.

## Tooling Discovery (Local System)
Before giving commands or instructions, locate tools on the machine:

1. Check for Idris2 (global PATH is expected):
   - `idris2 --version`
   - `which idris2`
2. Check for `pack` (preferred build tool):
   - `pack --version`
   - `which pack`
3. If `idris2` or `pack` isn’t found:
   - Ask the user how Idris2 was installed (manual build, Nix, etc.).
   - Do not assume a global install or a specific PATH layout.

If you need to run a command, say exactly what you’ll run and why.

## Build/Run (pack)
Default to `pack` unless the user requests otherwise.

- Build:
  - `pack build`
- Run tests (if configured):
  - `pack test`
- Run a target (if configured):
  - `pack run <target>`

If a command fails, report the error summary and ask for guidance on local setup.

## Idris2 Code Guidance (Idiomatic)
Prefer these patterns unless the user requests otherwise:

- Use total functions where practical: mark with `total` or rely on the default totality checker.
  - Why: totality gives stronger guarantees than typical FP languages and improves refactoring safety.
- Favor explicit types on exported definitions.
  - Why: exported signatures serve as stable contracts and minimize type-inference surprises across modules.
- Use `data` for ADTs and `record` for product types with named fields.
  - Why: mirrors algebraic data modeling and gives ergonomic field access.
- Use `case` for pattern matching clarity; avoid overly clever combinators when readability suffers.
  - Why: explicit patterns are easier to reason about in totality proofs and refactors.
- Keep proofs small and composable; introduce helper lemmas rather than monolithic proofs.
  - Why: smaller proofs are more reusable and reduce error surface, similar to small law-focused lemmas.
- Use `with` and `rewrite` sparingly and explain any non-obvious steps.
  - Why: these can hide control flow; keep them legible for learning and review.
- Avoid `believe_me` unless explicitly requested and clearly justified.
  - Why: it breaks soundness; treat it like `unsafe` or `asInstanceOf`.
- Use `Maybe`/`Either` for error handling; avoid partial functions unless unavoidable.
  - Why: makes failure explicit and aligns with FP error modeling.
- Prefer total recursion with structural arguments; avoid `%unsafe` unless strictly needed.
  - Why: total recursion is the default safety model in Idris2.

## FP Mapping Notes (Scala/Cats/Haskell)
Use these analogies when helpful:

- `Functor`, `Applicative`, `Monad` behave as in Cats/Haskell, but instances can be driven by dependent types.
- Type classes (`interface` in Idris2) are similar to Haskell type classes / Cats typeclass traits.
- `Dec` and decidable equality correspond to lawfulness/proofs that an equality is computational.
- `Either` and `Maybe` mirror `Either`/`Option`.
- `record` fields are like case class fields; `data` is like sealed trait + case classes.

## Project Hygiene
- Preserve existing style and module structure.
- Keep modules small; group related definitions.
- Add or update tests/examples when behavior changes.
- If you change types, update downstream code and mention any migration steps.

## Communication
- Be concise and concrete.
- Call out tradeoffs when relevant.
- If something is unclear, ask a targeted question.

## Output Expectations
When providing code:
- Include the module header if missing.
- Show full definitions, not just diffs, unless the user asks for a patch.
- Mention any required imports.

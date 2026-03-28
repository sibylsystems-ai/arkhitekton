# Idris 2 Missing Manual

Our private reference for gotchas, tricks, and underdocumented features discovered during dev

**Last Edited**: Mar 2026
**Idris Version**: 0.8.0-b714fcaea
**Package Manager**: pack (nightly-260205)

---

## Table of Contents

1. [Automatic Derivation](#automatic-derivation)
2. [Module System](#module-system)
3. [Package Management](#package-management)
4. [Build System](#build-system)
5. [Common Errors](#common-errors)
6. [Record Field Accessors](#record-field-accessors)

---

## Automatic Derivation

### The One-Line Solution

**Problem**: Need to implement `Eq`, `Show`, `Ord` for simple ADTs without boilerplate.

**Solution**:
```idris
module MyModule

import Derive.Prelude  -- ← THE KEY IMPORT

%language ElabReflection

public export
data MyType = Constructor1 | Constructor2 | Constructor3

%runElab derive "MyType" [Show, Eq, Ord]  -- All interfaces in ONE call
```

### What Doesn't Work (Common Mistakes)

**Wrong imports**:
```idris
import Derive.Eq      -- NO - these are internal modules
import Derive.Show
import Derive.Ord
```

**Separate calls**:
```idris
%runElab deriveEq "MyType"     -- NO - these functions don't exist
%runElab deriveShow "MyType"
%runElab deriveOrd "MyType"
```

**Wrong function name**:
```idris
%runElab derive "MyType" [Show, Eq, Ord]  -- NO - needs list syntax
```

### What Does Work

**Correct pattern**:
```idris
import Derive.Prelude
%language ElabReflection
%runElab derive "TypeName" [Show, Eq, Ord]
```

### Available Interfaces for Derivation

From `Derive.Prelude`, you can derive:
- `Eq` - Equality checking
- `Ord` - Ordering (requires `Eq`)
- `Show` - String representation
- (More available, see elab-util docs)

### When Derivation Fails

**Complex parameterized constructors** (e.g., `Double`, nested `List`) can cause derivation to fail. In that case, fall back to manual implementation:

```idris
-- This might fail:
data Complex = MkComplex Double (List String)
%runElab derive "Complex" [Show]  -- May error

-- Solution: Manual implementation
public export
Show Complex where
  show (MkComplex d strs) = "MkComplex " ++ show d ++ " " ++ show strs
```

### Documentation References

- **Main docs**: [idris2-elab-util/Derive.md](https://github.com/stefan-hoeck/idris2-elab-util/blob/main/src/Doc/Derive.md)
- **Examples**: [idris2-sop/Deriving.md](https://github.com/stefan-hoeck/idris2-sop/blob/main/docs/src/Docs/Deriving.md)

---

## Module System

### `export` vs `public export`

**Three levels of visibility**:

#### 1. Private (default)
```idris
data Secret = MkSecret String  -- Only visible in this module
```

#### 2. `export` - Type visible, constructors hidden
```idris
export
data Opaque = Constructor1 | Constructor2
```
Other modules can:
- Use `Opaque` in type signatures
- Pattern match on constructors
- Construct values directly

**Use case**: Encapsulation, abstract types

#### 3. `public export` - Fully transparent
```idris
public export
data Transparent = Constructor1 | Constructor2
```
Other modules can:
- Use `Transparent` in type signatures
- Pattern match on constructors
- Construct values directly

**Use case**: Domain types, public API

### When to Use Which

| Visibility Level | Use When | Example |
|-----------------|----------|---------|
| **Private** | Internal implementation details | Helper functions, internal state |
| **`export`** | Want to hide implementation | `data ConnectionPool`, `data DatabaseHandle` |
| **`public export`** | Domain types users need to inspect | `data OptimizationGoal`, `data Factor` |

### Mental Model

- **Private**: Not exported at all
- **`export`**: "Here's an opaque box. Pass it around, but can't look inside."
- **`public export`**: "Here's a transparent box. Inspect and modify freely."

---

## Package Management

### pack vs idris2

**Use pack for everything** (not standalone `idris2`):

```bash
# Correct workflow
pack build                    # Build package
pack typecheck                # Type-check without building executable
pack --cg javascript build    # Build with JavaScript backend

# Don't use idris2 directly (misses package dependencies)
idris2 --build myproject.ipkg
```

### JavaScript Codegen

**The `--cg` flag is a global option** (before the command):

```bash
# Correct
pack --cg javascript build

# Wrong
pack build --cg javascript  # Fails: unknown argument
```

### Adding Dependencies

Edit `.ipkg` file:
```idris
depends = base
        , elab-util    -- Comma on new line (Haskell-style)
        , dom
```

Then rebuild:
```bash
pack build
```

### Installing Packages

```bash
pack install <package-name>        # Install from package collection
pack install <pkg1> <pkg2> ...     # Install multiple at once
```

**Note**: Packages are installed globally per pack installation, not per-project.

---

## Build System

### Project Structure

```
project-root/
├── myproject.ipkg       # Package definition
├── src/
│   ├── Main.idr         # Entry point (if executable)
│   ├── Types/
│   │   └── MyType.idr
│   └── ...
└── build/               # Generated by pack (gitignore this)
    └── exec/
        └── myproject.js
```

### .ipkg File Template

```idris
package myproject
version = 0.1.0
authors = "Your Name"

sourcedir = "src"
modules = Main
        , Types.MyType
        , Types.OtherType

depends = base
        , elab-util

main = Main
executable = "myproject.js"

-- Note: Use pack --cg javascript build for JS output
```

### Module Naming

**File path MUST match module name**:

```
src/Types/Goal.idr     → module Types.Goal
src/Data/Presets.idr   → module Data.Presets
src/Main.idr           → module Main
```

**Type-checking from correct directory**:

```bash
# From project root (with .ipkg):
pack typecheck

# From src/ (single file):
cd src
idris2 --check Types/Goal.idr  # Note: path relative to src/
```

---

## Common Errors

### "Module name does not match file name"

**Error**:
```
Error: Module name Types.Goal does not match file name "src/Types/Goal.idr"
```

**Cause 1**: Running `idris2` from wrong directory

**Fix**: Either:
1. Run `pack typecheck` from project root (recommended)
2. Run `idris2 --check Types/Goal.idr` from `src/` directory

**Cause 2**: Module declaration doesn't match file path

**Example**:
```idris
-- File: src/Types/Goal.idr
module Types.GoalDerived  -- WRONG - doesn't match file path
```

**Fix**: Make module name match file path:
```idris
-- File: src/Types/Goal.idr
module Types.Goal  -- CORRECT
```

---

### "Imports must go before directives"

**Error**:
```
Error: Imports must go before any declarations or directives.
```

**Cause**: `%language` directive before `import` statements

**Fix**: Imports ALWAYS come first:
```idris
module MyModule

import Foo        -- Imports first
import Bar

%language ElabReflection  -- Then directives
```

---

### "Undefined name Language.Reflection.Elab"

**Error**:
```
Error: Undefined name Language.Reflection.Elab.
```

**Cause**: Missing `Derive.Prelude` import for derivation

**Fix**:
```idris
import Derive.Prelude  -- Add this!
%language ElabReflection
```

---

### "No local .ipkg files found"

**Error**:
```
[ fatal ] No local `.ipkg` files found.
```

**Cause**: Running `pack` commands from wrong directory

**Fix**: Navigate to directory containing `.ipkg` file:
```bash
cd /path/to/project-root  # Directory with myproject.ipkg
pack build
```

---

## Tips & Tricks

### Reference-Only Files

You can keep files in `src/` for documentation without building them by excluding from the `.ipkg` modules list:

```idris
-- In myproject.ipkg
modules = Main
        , Types.Production
        -- Types.Reference NOT listed (won't be built)
```

**Use case**: Keep manual implementations as learning reference while using derived versions in production.

**Example**: `Types/Goal.idr` (manual) vs `Types/GoalDerived.idr` (derived)

### `typecheck` vs `build`

**`pack typecheck`** - Type-checking only (FAST)
- Verifies types are correct
- Checks interface implementations
- Does NOT generate executable
- Fast feedback loop

**`pack build`** - Full compilation (SLOW)
- Type-checks everything
- Generates executable output
- Slower (includes code generation)

**Typical workflow**:
```bash
# Iterate quickly
pack typecheck    # Edit → typecheck → edit → typecheck
pack typecheck
pack typecheck

# Ready to run
pack --cg javascript build
node build/exec/myproject.js
```

Think of it like:
- Rust: `cargo check` vs `cargo build`
- TypeScript: `tsc --noEmit` vs `tsc`
- Scala: `compile` vs `package`

### Measuring Bundle Size

After building to JavaScript:
```bash
ls -lh build/exec/myproject.js    # Human-readable size
wc -l build/exec/myproject.js     # Line count
```

Our Hello World: 7KB, 288 lines (baseline for comparison)

### Running JavaScript Output

```bash
node build/exec/myproject.js
```

### Verbose Build Output

```bash
pack -v build  # Show detailed compilation steps
```

---

## Codegen Backend Selection

### Default Backend (Chez Scheme)

**`pack build` uses Chez Scheme by default**, not JavaScript:

```bash
pack build  # Generates Chez Scheme output (not runnable with node)
node build/exec/myproject.js  # ERROR: SyntaxError
```

**The output file will still be named `.js`** even though it's Scheme code! This is confusing.

### JavaScript Codegen

**Option 1**: Use `idris2` directly with `--codegen node`:

```bash
idris2 --build myproject.ipkg --codegen node
node build/exec/myproject.js  # Works
```

**Option 2**: Use `pack` with global `--cg` flag (NOT YET WORKING in our setup):

```bash
pack --cg javascript build  # May work in some pack versions
```

**In practice**: We use `idris2 --build ... --codegen node` for JavaScript output.

### Available Backends

- `chez` - Chez Scheme (default, fastest compilation)
- `node` - JavaScript via Node.js
- `javascript` - Browser JavaScript (no Node APIs)
- `racket` - Racket Scheme
- `refc` - Reference-counted C backend

### Workflow

```bash
# Development: Fast iteration with Chez
pack build
chez build/exec/myproject.so  # If using Chez

# Production: JavaScript for web
idris2 --build myproject.ipkg --codegen node
node build/exec/myproject.js
```

---

## Domain Modeling Patterns

### Modeling Context-Dependent Scoring

**Problem**: An entity's contribution depends on the context it's used in. Same entity, different value.

**Example**: A plugin boosts different project dimensions depending on which workflow it's assigned to.

**Pattern 1 - Bonus Function** (simple):

```idris
pluginBonus : Plugin -> Workflow -> Dimension -> Score
pluginBonus Linter CodeReview Quality = 3    -- High impact
pluginBonus Linter Deployment Speed = 1      -- Minor speedup
pluginBonus Linter _ _ = 0                   -- No effect otherwise
```

**Advantages**:
- Simple scoring model
- Captures strategic value
- Easy to test against known-good configurations
- Doesn't require detailed capability data

**When to use**: Early validation, MVP scoring systems

**Pattern 2 - Detailed Capabilities** (future refinement):

```idris
data PluginCapability
  = StaticAnalysis
  | AutoFormat
  | CacheInvalidation
  | ParallelExec
  -- etc.

pluginCapabilities : Plugin -> Workflow -> List PluginCapability
pluginCapabilities Linter CodeReview = [StaticAnalysis, AutoFormat]
pluginCapabilities Linter Deployment = [CacheInvalidation]
-- ...

capabilityValue : PluginCapability -> Dimension -> Score
capabilityValue StaticAnalysis Quality = 3
capabilityValue ParallelExec Speed = 3
-- ...
```

**Advantages**:
- Accurate to real capability model
- Can generate UI descriptions
- Self-documenting

**When to use**: Production app, UI generation, comprehensive modeling

**Lesson**: Start simple (Pattern 1), refine later (Pattern 2). Validate against known-good configurations before adding complexity.

---

## Type Ambiguities

### String.Nil vs Prelude.Nil

**Error**:
```
Ambiguous elaboration. Possible results:
    Data.String.Nil
    Prelude.Nil
```

**Cause**: Both `Data.String` (for String operations) and `Prelude` (for List operations) export a `Nil` constructor. Compiler can't infer which one you mean.

**Context**: Happens when using `[]` in contexts where type could be `List a` or something String-related.

**Fix 1 - Explicit type annotation**:
```idris
summaryLines : List String
summaryLines = case report.pluginSummary of
  [] => Prelude.Nil  -- Explicitly qualified
  bs => ["  Plugin Bonuses:"] ++ map (\s => "    " ++ s) bs
```

**Fix 2 - Add type signature to binding**:
```idris
summaryLines : List String  -- Type annotation clarifies
summaryLines = case report.pluginSummary of
  [] => []  -- Now compiler knows which Nil
  bs => ...
```

**Lesson**: When you see "Ambiguous elaboration", add a type signature to the binding or explicitly qualify the constructor.

---

## Record Field Accessors

### The Critical Difference from Haskell

**In Haskell**: Record fields automatically become projection functions.

**In Idris 2**: Record fields do NOT automatically become standalone functions. You must define them explicitly.

### Common Symptom

**Error**:
```
Error: Undefined name completionRate.
Did you mean: MaxRate?
```

**Context**: Using a record field name in a higher-order context like `map`:
```idris
record ProjectReport where
  constructor MkProjectReport
  completionRate : Nat
  -- ... 20 other fields ...

-- This FAILS:
averageCompletionRate reports =
  sum (map completionRate reports)  -- "Undefined name completionRate"
```

**Why it fails**: `completionRate` is just a field name, not a function. Idris 2 doesn't automatically create the projection function.

### The Idiomatic Solution: Explicit Projections

**Define projection functions for fields you'll use in higher-order contexts**:

```idris
-- Record definition
public export
record ProjectReport where
  constructor MkProjectReport
  team : TeamInfo
  completionRate : Nat
  bugOverlap : Nat
  -- ... 18 more fields ...

-- Explicit projection functions
export
completionRate : ProjectReport -> Nat
completionRate (MkProjectReport _ rate _ _ _ _ ... _) = rate

export
bugOverlap : ProjectReport -> Nat
bugOverlap (MkProjectReport _ _ overlap _ _ ... _) = overlap

-- Now higher-order usage works:
averageCompletionRate : List ProjectReport -> Nat
averageCompletionRate reports =
  let total = sum (map completionRate reports)  -- Works!
  in total `div` length reports
```

### Pattern Matching Tricks for Large Records

When a record has 20+ fields and you only need a few, use wildcards:

**Option 1 - Count wildcards carefully**:
```idris
-- ProjectReport has 21 fields, completionRate is field 19
export
completionRate : ProjectReport -> Nat
completionRate (MkProjectReport _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ rate _ _) = rate
```

**Option 2 - Local helper with pattern matching**:
```idris
averageCompletionRate reports =
  let extractRate : ProjectReport -> Nat
      extractRate (MkProjectReport _ _ _ ... rate ... _) = rate
      total = sum (map extractRate reports)
  in total `div` length reports
```

**Option 3 - Pattern match destructuring in function parameters**:
```idris
formatProjectReport : ProjectReport -> String
formatProjectReport pr =
  -- Extract just the fields we need
  let MkProjectReport team velocity throughput _ _ _ ... = pr
      MkTeamInfo name lead members region = team
  in "Team: " ++ show name ++ " (" ++ show lead ++ ")"
```

### Why This is Idiomatic

This pattern appears throughout Idris 2 standard library:

**Example from `Data.Vect`**:
```idris
-- Vect is a record with length index
-- But stdlib defines explicit head, tail functions:
export
head : Vect (S n) a -> a
head (x :: xs) = x

export
tail : Vect (S n) a -> Vect n a
tail (x :: xs) = xs
```

**This is NOT a workaround - it's the standard pattern.**

### When You DON'T Need Projections

If you only use field access with dot notation in direct contexts, projections aren't needed:

```idris
showReport : ProjectReport -> String
showReport pr =
  "Completion rate: " ++ show pr.completionRate  -- Dot notation works here
```

But for higher-order contexts (map, filter, composition), you need the explicit projections.

### Nested Field Access in Higher-Order Contexts

**Problem**: Accessing nested fields in lambdas or map:
```idris
-- This FAILS:
totalMembers = sum (map (\r => length (members (team r))) reports)
--                                ^^^^^^^ "Undefined name members"
```

**Solution**: Define projections at each level:
```idris
-- TeamInfo projections
export
members : TeamInfo -> List Person
members (MkTeamInfo _ _ ms _) = ms

-- ProjectReport projections
export
team : ProjectReport -> TeamInfo
team (MkProjectReport t _ _ ... _) = t

-- Now composition works:
totalMembers = sum (map (length . members . team) reports)  -- Works!
```

### Division on Nat

**Bonus gotcha discovered while fixing accessors**: `div` for Nat requires specific syntax.

**Wrong**:
```idris
average = total / count        -- No (/) for Nat
average = div total count      -- Ambiguous
average = Data.Nat.div total count  -- Not exported
```

**Right**:
```idris
average = total `div` count    -- Backtick syntax
-- But guard against division by zero:
average = case count of
  Z => 0
  S k => total `div` count
```

### Module-Qualified Names to Avoid Conflicts

If your projection name conflicts with imported functions (e.g., `Types.Reports.completionRate`), use module qualification:

```idris
-- In Reporting module
formatCompletionRate : ProjectReport -> List String
formatCompletionRate pr =
  let rate = Reporting.completionRate pr  -- Fully qualified
      overlap = Reporting.bugOverlap pr
  in ["Completion: " ++ show rate ++ "%"]
```

### Checklist for Adding Record Field Accessors

When you create a new record type:

1. Define the record with all fields
2. Identify which fields will be used in higher-order contexts (map, filter, fold, composition)
3. Define explicit projection functions for those fields
4. Export the projections
5. Count wildcards carefully (field count = total constructor args)
6. Test in a higher-order context (e.g., `map fieldName records`)

### Real-World Example

```idris
-- 21-field record
public export
record ProjectReport where
  constructor MkProjectReport
  team : TeamInfo
  -- ... 19 more fields ...
  completionRate : Nat

-- 8 projection functions defined for commonly-used fields
export
team : ProjectReport -> TeamInfo
team (MkProjectReport t _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _) = t

export
completionRate : ProjectReport -> Nat
completionRate (MkProjectReport _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ _ rate _ _) = rate

-- Now works in map:
averageCompletionRate : List ProjectReport -> Nat
averageCompletionRate reports =
  let total = sum (map completionRate reports)  -- Projection function
      count = length reports
  in case count of
       Z => 0
       S k => total `div` count
```

**Pattern**: 8 explicit projections for 21-field record. All higher-order operations working.

### Key Takeaway

**Record field accessors are NOT automatic in Idris 2.** This is intentional - it gives you control over what gets exported and how. Define explicit projection functions for any field you'll use with `map`, `filter`, or function composition.

This is the same pattern used throughout the Idris 2 ecosystem - it's not a limitation, it's the idiom.

---

## JavaScript Backend: Compiling Idris 2 to Usable JS Modules

**When**: March 2026
**Context**: Building `arkhitekton` — transpiling Idris 2 specs to JS modules consumed by TypeScript tests.

### The Two JS Backends

```bash
idris2 --cg node -o Output.js Main.idr         # Node.js (uses process.stdout)
idris2 --cg javascript -o Output.js Main.idr    # Browser (uses console.log)
```

Both produce a single self-contained `.js` file. No ESM, no CommonJS `module.exports` — just
a script that auto-executes `main`. You add the exports yourself.

### Dead Code Elimination Will Ruin Your Day

Idris 2's DCE is **aggressive**. Functions not reachable from `main` are eliminated entirely.
Even `let _ = myFunction` inside `main` isn't enough — the compiler sees through it and
inlines/eliminates.

**This doesn't work:**
```idris
main : IO ()
main = do
  let _ = weight High        -- Compiler inlines and eliminates weight
  let _ = combine x y       -- combine vanishes too
  printLn "ok"
```

Result: `weight` and `combine` are gone from the JS output. Only `main` survives.

### The Solution: `%export` Directive

**This works:**
```idris
weight : Priority -> Double
weight High = 0.30
weight Medium = 0.25
-- ...

%export "javascript:weight"
jsWeight : Priority -> Double
jsWeight = weight

main : IO ()
main = pure ()
```

`%export "javascript:name"` does **two things at once**:
1. **Prevents DCE** — the function survives compilation
2. **Creates a clean JS function name** — `weight` instead of `Spec_weight`

The generated JS:
```js
/* Spec.weight : Priority -> Double */
function Spec_weight($0) {             // internal, mangled name
 switch($0) { case 0: return 0.3; ... }
}

/* Spec.jsWeight : Priority -> Double */
function weight($0) {                  // clean, exported name
 return Spec_weight($0);
}
```

### The Export Pattern

For every public function in your spec, add an `%export` wrapper:

```idris
-- The actual function
myFunction : A -> B -> C
myFunction a b = ...

-- The export (one-liner)
%export "javascript:myFunction"
jsMyFunction : A -> B -> C
jsMyFunction = myFunction
```

Convention: prefix the wrapper with `js` to distinguish from the real function.
The `"javascript:name"` string becomes the JS function name verbatim.

### What the Generated JS Looks Like

**Functions**: clean switches and expressions.
```js
// data Priority = High | Medium | Low | Deferred | Optional
// Constructors become integers: High=0, Medium=1, Low=2, ...
function Spec_weight($0) {
  switch($0) {
    case 0: return 0.3;   // High
    case 1: return 0.25;  // Medium
    case 2: return 0.2;   // Low
    case 3: return 0.15;  // Deferred
    case 4: return 0.1;   // Optional
  }
}
```

**Single-field records are unboxed.** `record Score where constructor MkScore; value : Double`
becomes just a `Double` in JS. No wrapper object. `combine` takes two Doubles directly.

**Multi-field records** become objects with positional fields: `{a1: field1, a2: field2}`.

**Maybe**: `Nothing` = `{h: 0}`, `Just x` = `{a1: x}` (no `h` field).
Pattern matching uses `switch($0.h) { case 0: ...; case undefined: ... }`.

**Sum types (ADTs)**: tag in `h`, data in `a1`, `a2`, etc.
`Left e` = `{h: 0, a1: e}`, `Right a` = `{h: 1, a1: a}`.

**Lazy values**: wrapped in `__lazy(function() { ... })`. The thunk replaces itself
on first call, so subsequent calls return the cached value. But the export is a
function, not a value — callers need to invoke it: `baseline()`.

### Making It a CommonJS Module

The generated JS has no `module.exports`. Append it yourself:

```js
// Appended by post-processing
module.exports = {
  weight,
  combine,
  baseline,
};
```

Functions exported via `%export "javascript:name"` have clean names (no prefix),
so the exports are trivial.

Also strip the self-executing `try{__mainExpression_0()}catch(e){...}` at the
bottom — you don't want `main` running on `require()`.

### Calling from Node.js / TypeScript

```js
const { weight, combine, baseline } = require('./spec.js');

console.log(weight(0));          // 0.3 (High — constructors are integers)
console.log(combine(0.3, 0.4));  // 0.7
console.log(baseline());         // 0.0 (lazy value — must call it)
```

For TypeScript consumption, write a `.d.ts` by hand or generate one:
```typescript
export declare function weight(factor: number): number;
export declare function combine(a: number, b: number): number;
export declare function baseline(): number;
```

### ADT Interop Cheat Sheet

| Idris Type | JS Representation | TS Type |
|-----------|-------------------|---------|
| `Bool` | `0` / `1` | `number` (0 or 1) |
| `Nat` | `BigInt` (`0n`, `1n`) | `bigint` |
| `Int` | `number` (32-bit) | `number` |
| `Integer` | `BigInt` | `bigint` |
| `Double` | `number` | `number` |
| `String` | `string` | `string` |
| `Nothing` | `{h: 0}` | `{ h: 0 }` |
| `Just x` | `{a1: x}` | `{ a1: T }` |
| `Left e` | `{h: 0, a1: e}` | `{ h: 0, a1: E }` |
| `Right a` | `{h: 1, a1: a}` | `{ h: 1, a1: A }` |
| `Nil` | `{h: 0}` | `{ h: 0 }` |
| `x :: xs` | `{a1: x, a2: xs}` | `{ a1: T, a2: List }` |
| Enum `Con1 \| Con2 \| Con3` | `0`, `1`, `2` | `number` |
| `record` (1 field) | unwrapped to the field value | field type |
| `record` (N fields) | `{a1: f1, a2: f2, ...}` | positional object |

### What Doesn't Work (or Needs Care)

- **No ESM support.** Output is CommonJS-only (append `module.exports` yourself).
  No `export default` or `export { }`.

- **No `.d.ts` generation.** Write them by hand or have an LLM generate them
  from the Idris source.

- **Lazy values are functions.** `const baseline = __lazy(function() { ... })`.
  You must call `baseline()` to get the value. The adapter layer should handle this.

- **No async/Promise support.** IO compiles to synchronous continuation-passing.
  For async operations, use FFI:
  ```idris
  %foreign "node:lambda: () => fetch('https://...')"
  prim__fetch : PrimIO String
  ```

- **Integer/Nat use BigInt.** If you pass a regular JS `number` where Idris expects
  `Nat` or `Integer`, you'll get type errors at runtime. Use `10n` not `10`.

- **Constructor order matters.** Enum constructors compile to sequential integers
  based on declaration order. `data Priority = High | Medium | Low` means `High=0`,
  `Medium=1`, `Low=2`. If you reorder the constructors, the numbers change.
  Your adapter/tests must match.

### The Runtime Preamble

Every generated JS file starts with ~220 lines of runtime support: `__lazy`,
`__tailRec`, `__prim_js2idris_array`, BigInt helpers, etc. This is inlined
per file. For single-module projects (like arkhitekton), this is fine. For
multi-module projects, consider extracting it into a shared `idris-runtime.js`.

### File Size Reference

| Idris Source | JS Output | Lines | Size |
|-------------|-----------|-------|------|
| Hello World | `Spec.js` | ~290 | ~7KB |
| Small spec (5 functions + exports) | `Spec.js` | ~340 | ~8KB |

The runtime preamble is the bulk of it. User code adds relatively little.

---

## Next: Learnings to Add

As we continue building, add sections for:
- [x] Record field accessors (Added Feb 9, 2026)
- [x] JavaScript backend: compilation, DCE, `%export`, interop, ADT representations (Added Mar 19, 2026)
- [ ] Dependent types (when we implement proofs)
- [ ] Working with `Vect` (sized vectors)
- [ ] `Fin` for bounded numbers
- [ ] Pattern matching gotchas
- [ ] Totality checking
- [ ] Interface resolution
- [ ] FFI for browser APIs (when we get to DOM)
- [ ] Performance tips
- [ ] Uniqueness constraints (assignment proofs)
- [ ] Typed holes as requirements elicitation (arkhitekton pipeline)
- [ ] Custom interfaces vs Semigroup/Monoid (CompositionRule, HasBaseline pattern)

---

## Meta

**How to use this document**:
- Add new discoveries as we encounter them
- Include both "wrong" and "right" examples
- Link to official docs when available
- Keep it pragmatic (what actually helps us build)

**When to update**:
- Hit a confusing error? Document the fix.
- Find underdocumented feature? Write it down.
- Discover a pattern? Capture it here.

This is OUR missing manual - optimize for future us, not external readers.

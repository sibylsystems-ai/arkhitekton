# Idris 2 JS Backend Reference

## Compilation

```bash
idris2 --cg node -o Output.js Main.idr    # Node.js output
idris2 --cg javascript -o Output.js Main.idr  # Browser output
```

Both produce a single self-contained .js file.

## DCE Prevention: %export Directive

Idris 2's dead code elimination is aggressive. Functions not reachable from `main`
are eliminated. Use `%export` to keep functions alive AND give them clean JS names:

```idris
weight : Priority -> Double
weight High = 0.30
weight Medium = 0.25

-- This prevents DCE and creates a clean JS function named "weight"
%export "javascript:weight"
jsWeight : Priority -> Double
jsWeight = weight
```

For every public function, add an `%export` wrapper. Convention: prefix with `js`.

## Generated JS Representations

### Enum constructors → sequential integers
```idris
data Priority = High | Medium | Low | Deferred | Optional
-- High=0, Medium=1, Low=2, Deferred=3, Optional=4
```

### Single-field records → unboxed
```idris
record Score where constructor MkScore; value : Double
-- In JS: just a Double, no wrapper object
```

### Multi-field records → positional objects
```idris
record Project where constructor MkProject; name : String; priority : Nat
-- In JS: {a1: "Alpha", a2: 3n}
```

### Maybe
- Nothing → `{h: 0}`
- Just x → `{a1: x}` (no h field)
- Pattern match: `switch($0.h) { case 0: ...; case undefined: ... }`

### Either
- Left e → `{h: 0, a1: e}`
- Right a → `{h: 1, a1: a}`

### List
- Nil → `{h: 0}`
- x :: xs → `{a1: x, a2: xs}`

### Nat / Integer → BigInt
Use `0n`, `1n`, `10n` etc. Regular JS numbers won't work.

## Lazy Values

Constants compile to `__lazy` thunks:
```js
const baseline = __lazy(function () { return 0.0; });
```
Must be called as `baseline()` to get the value.

## Making It a Module

The generated JS has no `module.exports`. Append it yourself:
```js
module.exports = { weight, combine, baseline };
```
Strip the `try{__mainExpression...}` auto-execution at the bottom.

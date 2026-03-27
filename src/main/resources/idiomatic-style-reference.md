# Idiomatic Idris 2 Style Guide

## Total Functions
Mark functions with `total` or rely on `%default total`. The compiler rejects
non-covering pattern matches. This is a feature, not an annoyance.

## Explicit Types on Exports
Every exported function gets a type signature. These are stable contracts.

## ADTs and Records
- `data` for sum types (enums, tagged unions)
- `record` for product types with named fields

## Error Handling
Use `Maybe`/`Either`, never partial functions. Make failure explicit.
```idris
-- Good
safeDivide : Double -> Double -> Either String Double
safeDivide _ 0.0 = Left "division by zero"
safeDivide a b   = Right (a / b)

-- Bad
divide : Double -> Double -> Double  -- partial, crashes on zero
```

## IO at the Edges
Pure functions for business logic. IO only for external system interaction.
Never mix IO into calculation functions.

## Avoid believe_me
It breaks soundness. Only use when explicitly justified (e.g., FFI stubs
for IO functions that will be implemented in the target language).

## Pattern Matching
Prefer explicit `case` over clever combinators when readability matters.
Each case is visible to the totality checker.

## FP Analogies (Scala/Haskell)
- `interface` = type class / Cats trait
- `data` = sealed trait + case classes
- `record` = case class
- `Maybe` = Option
- `Either` = Either
- `Functor`/`Applicative`/`Monad` = same as Cats/Haskell
- `Dec` = decidable equality (computational proof)

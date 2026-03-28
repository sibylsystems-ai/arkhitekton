# Test Generation Guide: Idris 2 Spec → TypeScript Tests

## Principle

Tests are generated from the SPECIFICATION (Idris types and laws),
NOT from the implementation. You are testing whether the compiled JS
matches the spec. You have never seen the JS code.

## Test Categories

### 1. Type Shape Tests
Verify returned values have the right structure.
```typescript
it('weight returns a number', () => {
  expect(typeof weight(Priority.High)).toBe('number');
});
```

### 2. Algebraic Law Tests
If the spec defines composition rules, test their laws.
```typescript
// Associativity: compose(compose(a, b), c) === compose(a, compose(b, c))
it('composition is associative', () => {
  const a = 0.2, b = 0.3, c = 0.1;
  expect(combine(combine(a, b), c)).toBeCloseTo(combine(a, combine(b, c)));
});

// Identity: compose(baseline, x) === x
it('baseline is the identity element', () => {
  const x = 0.5;
  expect(combine(getBaseline(), x)).toBeCloseTo(x);
});

// Commutativity (if stated): compose(a, b) === compose(b, a)
it('composition is commutative', () => {
  expect(combine(0.3, 0.4)).toBeCloseTo(combine(0.4, 0.3));
});
```

### 3. Boundary Tests
Test edges of bounded types.
```typescript
// Score must be between 0.0 and 1.0
it('combine never exceeds 1.0', () => {
  expect(combine(0.9, 0.8)).toBeLessThanOrEqual(1.0);
});

it('combine never goes below 0.0', () => {
  expect(combine(0.0, 0.0)).toBeGreaterThanOrEqual(0.0);
});
```

### 4. Case Coverage Tests
For each constructor of a sum type, verify the function handles it.
```typescript
// Every Priority must have a weight
it.each([
  [Priority.High, 0.3],
  [Priority.Medium, 0.25],
  [Priority.Low, 0.2],
  [Priority.Deferred, 0.15],
  [Priority.Optional, 0.1],
])('weight(%s) = %s', (priority, expected) => {
  expect(weight(priority)).toBeCloseTo(expected);
});
```

### 5. Property Tests
Test invariants stated in comments or types.
```typescript
// "Output: a number between 0.0 and 1.0"
it('all weights are between 0 and 1', () => {
  for (const f of [0, 1, 2, 3, 4]) {
    const w = weight(f);
    expect(w).toBeGreaterThanOrEqual(0.0);
    expect(w).toBeLessThanOrEqual(1.0);
  }
});

// Weights should sum to 1.0
it('weights sum to 1.0', () => {
  const total = [0, 1, 2, 3, 4].reduce((sum, f) => sum + weight(f), 0);
  expect(total).toBeCloseTo(1.0);
});
```

### 6. Composition / Pipeline Tests
Test end-to-end flows.
```typescript
it('calculating total from all priorities produces a valid score', () => {
  const score = calculate([...]);
  expect(score).toBeGreaterThanOrEqual(0.0);
  expect(score).toBeLessThanOrEqual(1.0);
});
```

### 7. Maybe / Default Handling Tests
```typescript
it('fillDefault uses median when value is missing', () => {
  const result = fillDefault(Priority.High, null);
  expect(typeof result).toBe('number');
});

it('fillDefault uses provided value when present', () => {
  expect(fillDefault(Priority.High, 0.8)).toBe(0.8);
});
```

## Import Pattern

```typescript
import { describe, it, expect } from 'vitest';
import { weight, combine, baseline, fillDefault } from './spec';
import { Priority, getBaseline, fromMaybe, toMaybe } from './spec-adapter';
```

## What NOT to Test

- IO functions (database, queue, API calls) — mark as TODO
- Internal helper functions not in the public API
- The Idris runtime internals (`__lazy`, `__tailRec`, etc.)
- Implementation details (HOW it computes, only WHAT it returns)

## Floating Point

Use `toBeCloseTo` for all Double comparisons, never strict equality.
```typescript
expect(weight(Priority.High)).toBeCloseTo(0.3);  // ✅
expect(weight(Priority.High)).toBe(0.3);          // floating point
```

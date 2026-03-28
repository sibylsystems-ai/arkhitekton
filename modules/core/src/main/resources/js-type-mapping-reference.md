# Idris 2 → TypeScript Type Mapping

Use this table when generating `.d.ts` files or TypeScript adapters
from Idris 2 source code.

## Primitive Types

| Idris 2 | TypeScript | JS Runtime Value |
|---------|-----------|-----------------|
| `Double` | `number` | `number` |
| `Int` | `number` | `number` (32-bit) |
| `Nat` | `number` | `bigint` — adapter must convert |
| `Integer` | `number` | `bigint` — adapter must convert |
| `String` | `string` | `string` |
| `Char` | `string` | `string` (single char) |
| `Bool` | `boolean` | `0` or `1` — adapter must convert |

## Algebraic Data Types

### Enums (nullary constructors only)
```idris
data Priority = High | Medium | Low | Deferred | Optional
```
→ TS type: `type Priority = 0 | 1 | 2 | 3 | 4`
→ TS enum (for readability): `enum Priority { High = 0, Medium = 1, Low = 2, Deferred = 3, Optional = 4 }`
→ JS runtime: plain integers `0`, `1`, `2`, ...
→ Constructor order = integer value

### Sum types (constructors with data)
```idris
data Shape = Circle Double | Rectangle Double Double
```
→ TS: `type Shape = { h: 0; a1: number } | { h: 1; a1: number; a2: number }`
→ Or with named tags: `type Shape = { tag: 'Circle'; radius: number } | { tag: 'Rectangle'; width: number; height: number }`
→ The adapter converts between tagged objects and the `{h, a1, a2}` representation

### Maybe
```idris
Maybe a
```
→ JS: Nothing = `{h: 0}`, Just x = `{a1: x}` (no `h` field when Just)
→ TS (raw): `{ h: 0 } | { a1: T }`
→ TS (adapted): `T | null`
→ Adapter: `fromMaybe<T>(v: any): T | null`

### Either
```idris
Either e a
```
→ JS: Left e = `{h: 0, a1: e}`, Right a = `{h: 1, a1: a}`
→ TS (raw): `{ h: 0; a1: E } | { h: 1; a1: A }`
→ TS (adapted): `{ tag: 'Left'; value: E } | { tag: 'Right'; value: A }`
→ Adapter: `fromEither<E, A>(v: any): { tag: 'Left'; value: E } | { tag: 'Right'; value: A }`

### List
```idris
List a
```
→ JS: Nil = `{h: 0}`, x :: xs = `{a1: x, a2: xs}` (linked list)
→ TS (adapted): `ReadonlyArray<T>`
→ Adapter: `fromList<T>(v: any): T[]` (walks the linked list)

## Records

### Single-field record (UNBOXED)
```idris
record Score where constructor MkScore; value : Double
```
→ JS: just the `Double` value directly, no wrapper
→ TS: `number` (not an interface — the record is erased)

### Multi-field record
```idris
record Project where
  constructor MkProject
  name : String
  priority : Nat
  budget : Double
```
→ JS: `{a1: "Alpha", a2: 3n, a3: 50000.0}`
→ TS (raw): `{ a1: string; a2: bigint; a3: number }`
→ TS (adapted): `interface Project { name: string; priority: number; budget: number }`
→ Adapter maps positional `a1, a2, a3` to named fields

## Functions

### Pure functions
```idris
weight : Priority -> Double
```
→ TS: `export declare function weight(priority: Priority): number`

### IO functions
```idris
fetchProject : ProjectId -> IO (Either ApiError Project)
```
→ TS: `export declare function fetchProject(id: ProjectId): Promise<Project>`
→ The IO + Either pattern becomes async + throw in TS

## Lazy Values

Constants defined at the top level compile to `__lazy` thunks.
```idris
baseline : Score
baseline = MkScore 0.0
```
→ JS: `const baseline = __lazy(function() { return 0.0; })`
→ Must be called as a function: `baseline()`
→ Adapter wraps this: `export function getBaseline(): number { return baseline(); }`

## Adapter Pattern

The adapter module (`spec-adapter.ts`) provides:

1. **Type converters**: `fromMaybe`, `fromEither`, `fromList`, `toBool`, `fromBool`
2. **Enum constructors**: `Priority.High`, `Priority.Medium`, etc. mapping to integers
3. **Record constructors/destructors**: named fields ↔ positional `a1, a2, ...`
4. **Lazy value unwrappers**: `getBaseline()` calls the thunk
5. **BigInt converters**: `fromNat(n: bigint): number`

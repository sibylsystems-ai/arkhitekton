# Idris 2 Syntax Reference for LLM Code Generation

This reference is injected into the system prompt when asking an LLM to generate
Idris 2 from English specifications. It covers the subset of Idris 2 needed for
spec formalization — not the full language.

## Module Declaration

Every file MUST start with a module declaration matching the filename.

```idris
module Spec
```

## Basic Types

```idris
-- Primitive types
Bool, Nat, Int, Integer, Double, String, Char

-- Maybe: a value that might be absent
Maybe a = Nothing | Just a

-- Either: a value or an error
Either e a = Left e | Right a

-- List
List a = Nil | (::) a (List a)
-- Sugar: [1, 2, 3] is 1 :: 2 :: 3 :: Nil

-- Pair
(a, b)       -- a pair/tuple
(x, y)       -- construct
fst p        -- first element
snd p        -- second element
```

## Records

Records are named product types with accessor functions.

```idris
record Project where
  constructor MkProject
  name : String
  priority : Nat
  budget : Double

-- Construction
project : Project
project = MkProject "Alpha" 3 50000.0

-- Field access
project.name      -- "Alpha"
project.priority  -- 3

-- Update syntax
{ priority := 2 } project
```

## Data Types (Algebraic / Sum Types)

```idris
-- Enumeration
data Color = Red | Green | Blue

-- Sum type with payloads
data Shape
  = Circle Double              -- radius
  | Rectangle Double Double    -- width, height

-- Parameterized
data Result e a
  = Failure e
  | Success a
```

## Functions

```idris
-- Type signature on one line, definition on the next
double : Nat -> Nat
double n = n + n

-- Pattern matching (each case is a separate equation)
describe : Color -> String
describe Red   = "warm"
describe Green = "cool"
describe Blue  = "cool"

-- Guards
classify : Double -> String
classify x = if x < 0.3 then "low"
             else if x < 0.7 then "medium"
             else "high"

-- Lambda
map (\x => x + 1) [1, 2, 3]

-- Where clauses
circumference : Double -> Double
circumference r = 2.0 * pi * r
  where
    pi : Double
    pi = 3.14159265
```

## Pattern Matching on Maybe and Either

```idris
-- Maybe
withDefault : a -> Maybe a -> a
withDefault def Nothing  = def
withDefault _   (Just x) = x

-- Either
handleResult : Either String Int -> String
handleResult (Left err) = "Error: " ++ err
handleResult (Right n)  = "Got: " ++ show n
```

## Interfaces (Type Classes)

Interfaces define a contract. Implementations provide the behavior.

```idris
-- Define an interface
interface Combinable a where
  combine : a -> a -> a

-- Implement it for a specific type
Combinable Score where
  combine (MkScore x) (MkScore y) = MkScore (min 1.0 (x + y))
```

### Key Built-in Interfaces

```idris
-- Semigroup: types with an associative combine operation
interface Semigroup a where
  (<+>) : a -> a -> a
  -- Law: (a <+> b) <+> c = a <+> (b <+> c)

-- Monoid: Semigroup with an identity element
interface Semigroup a => Monoid a where
  neutral : a
  -- Law: neutral <+> a = a = a <+> neutral

-- Eq: equality comparison
interface Eq a where
  (==) : a -> a -> Bool

-- Ord: ordering
interface Eq a => Ord a where
  compare : a -> a -> Ordering

-- Show: convert to string
interface Show a where
  show : a -> String

-- Functor, Applicative, Monad (for IO and effect chaining)
interface Functor f where
  map : (a -> b) -> f a -> f b

interface Functor f => Applicative f where
  pure : a -> f a
  (<*>) : f (a -> b) -> f a -> f b

interface Applicative m => Monad m where
  (>>=) : m a -> (a -> m b) -> m b
```

## Typed Holes

A typed hole is a placeholder for code you haven't written yet.
The compiler accepts it and tells you what type is needed.

```idris
-- Use ?name to leave a hole
populationMedian : Factor -> Double
populationMedian f = ?what_is_the_median

-- The compiler will report:
--    f : Factor
--    --------------
--    what_is_the_median : Double
--
-- Meaning: "you have a Factor in scope, I need a Double"
```

**When generating from specs:** use typed holes for anything the spec
does not explicitly define. Use descriptive names that map back to
the English spec.

```idris
-- GOOD: descriptive hole names
defaultTimeout = ?what_timeout_in_seconds
retryCount     = ?how_many_retries

-- BAD: generic names
defaultTimeout = ?hole1
retryCount     = ?hole2
```

## IO and Effects

IO marks functions that interact with the outside world.
A function that reads from a database, writes to a queue, calls an API,
or touches the filesystem MUST return IO.

```idris
-- Pure function: no side effects
calculateScore : Project -> Score
calculateScore p = MkScore (p.budget * 0.3)

-- IO function: reads from external system
fetchProject : ProjectId -> IO (Either ApiError Project)

-- IO function: writes to external system
publishEvent : Event -> IO (Either QueueError ())

-- IO function: reads AND writes
processProject : ProjectId -> IO (Either AppError Score)
processProject pid = do
  project <- fetchProject pid     -- IO read
  let score = calculateScore project  -- pure calculation
  publishEvent (ScoreComputed pid score)  -- IO write
  pure (Right score)
```

### IO Separation Principle

The spec should make the IO boundary explicit. Pure logic goes in
pure functions. IO operations are at the edges.

```idris
-- GOOD: IO at the edges, pure in the middle
fetchEntries : ProjectId -> IO (Either ApiError (List Entry))
calculateScore : List Entry -> Score    -- pure!
storeResult   : ProjectId -> Score -> IO (Either DbError ())

pipeline : ProjectId -> IO (Either AppError ())
pipeline pid = do
  entries <- fetchEntries pid
  let score = calculateScore entries
  storeResult pid score

-- BAD: IO mixed into business logic
calculateScore : ProjectId -> IO Score  -- why does calculation need IO?
```

## Common External System Patterns

When a spec mentions external systems, model them as IO boundaries:

```idris
-- Database read
readFromDb : QueryParams -> IO (Either DbError result)

-- Database write
writeToDb : record -> IO (Either DbError ())

-- REST API call
callApi : Request -> IO (Either HttpError Response)

-- Message queue publish
publishToQueue : Message -> IO (Either QueueError ())

-- Message queue consume (callback style)
consumeFromQueue : (Message -> IO ()) -> IO ()

-- File system
readFile : FilePath -> IO (Either FileError String)
writeFile : FilePath -> String -> IO (Either FileError ())

-- Cache
readCache : Key -> IO (Maybe Value)
writeCache : Key -> Value -> IO (Either CacheError ())
```

### Domain-Specific IO Aliases

When the spec names a specific system, create a domain-typed alias:

```idris
-- "read from Airtable" →
fetchFromAirtable : AirtableBaseId -> IO (Either AirtableError FormData)

-- "write to DynamoDB" →
writeToDynamo : TableName -> Item -> IO (Either DynamoError ())

-- "send to SQS queue" →
sendToSQS : QueueUrl -> Message -> IO (Either SQSError ())

-- "call the GraphQL API" →
queryGraphQL : GraphQLQuery -> IO (Either GraphQLError Response)

-- "read from S3" →
getFromS3 : BucketName -> Key -> IO (Either S3Error ByteString)

-- "publish to SNS" →
publishToSNS : TopicArn -> Message -> IO (Either SNSError ())
```

## Do-Notation

Chain IO operations (or any Monad) with `do`:

```idris
main : IO ()
main = do
  putStrLn "Enter name:"
  name <- getLine
  putStrLn ("Hello, " ++ name)

-- Each `<-` unwraps the monadic value
-- `let` introduces pure bindings inside do
processBatch : List ProjectId -> IO (List Score)
processBatch pids = do
  let validated = filter isValid pids  -- pure
  results <- traverse processOne validated  -- IO for each
  pure results
```

## Traversal and Folding

```idris
-- traverse: apply an effectful function to each element
traverse : (a -> IO b) -> List a -> IO (List b)
-- Also works with Maybe, Either, etc.

-- map: apply a pure function to each element
map : (a -> b) -> List a -> List b

-- foldl: reduce a list to a single value
foldl : (acc -> elem -> acc) -> acc -> List elem -> acc

-- concat: combine a list of monoidal values
concat : Monoid a => List a -> a
-- e.g., concat [MkScore 0.3, MkScore 0.2] = MkScore 0.5

-- filter
filter : (a -> Bool) -> List a -> List a
```

## Totality

```idris
-- Total functions must handle all inputs and terminate
%default total

-- The compiler will reject:
weight : Factor -> Double
weight High    = 0.30
weight Age     = 0.25
-- ERROR: weight is not covering. Missing cases: weight Low, ...

-- Partial functions (escape hatch — avoid in specs)
partial
unsafeHead : List a -> a
unsafeHead (x :: _) = x
```

## Common Patterns in Spec Formalization

### Bounded numeric type
```idris
record Percentage where
  constructor MkPct
  value : Double
  -- Invariant: 0.0 <= value <= 100.0

record Probability where
  constructor MkProb
  value : Double
  -- Invariant: 0.0 <= value <= 1.0
```

### Named wrappers (newtypes)
```idris
record ProjectId where
  constructor MkProjectId
  unwrap : String

record Temperature where
  constructor MkTemp
  celsius : Double
```

### Error type for a domain
```idris
data AppError
  = NotFound String
  | ValidationFailed String
  | Unauthorized
  | ExternalServiceDown String
```

### Pipeline with error propagation
```idris
pipeline : Input -> Either AppError Output
pipeline input = do
  validated <- validate input        -- may fail
  enriched  <- enrich validated      -- may fail
  pure (transform enriched)          -- pure step
```

### Idempotent operations
```idris
-- An idempotent function satisfies: f (f x) = f x
-- Express as a comment/law, not a proof (for pragmatism)
fillDefault : Maybe Value -> Value
fillDefault Nothing  = defaultValue
fillDefault (Just v) = v
-- Law: fillDefault (Just (fillDefault x)) = fillDefault x
```

### State machine / workflow
```idris
data OrderState
  = Draft
  | Submitted
  | Approved
  | Rejected
  | Completed

-- Only certain transitions are valid
data Transition : OrderState -> OrderState -> Type where
  Submit  : Transition Draft Submitted
  Approve : Transition Submitted Approved
  Reject  : Transition Submitted Rejected
  Complete : Transition Approved Completed

-- The type prevents invalid transitions at compile time
advance : Transition s1 s2 -> Order s1 -> Order s2
```

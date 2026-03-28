# IO Inference Patterns

When translating English specs to Idris 2, certain phrases signal
that an operation involves the outside world and must be typed as IO.

## Trigger Phrases → IO Classification

### Database Operations (IO read/write)
- "read from", "query", "fetch from", "look up in", "select from"
  → `IO (Either DbError result)`
- "write to", "store in", "save to", "insert into", "update in", "upsert"
  → `IO (Either DbError ())`
- "delete from", "remove from", "purge"
  → `IO (Either DbError ())`

### Specific Database Systems
- "DynamoDB", "dynamo" → `DynamoError`, table-based access
- "PostgreSQL", "postgres", "RDS" → `DbError`, SQL-based
- "Redis", "ElastiCache" → `CacheError`, key-value
- "S3", "bucket" → `S3Error`, object storage
- "Airtable" → `AirtableError`, form/record-based

### Message Queue / Event Operations (IO write)
- "send to queue", "publish", "enqueue", "emit event", "fire event"
  → `IO (Either QueueError ())`
- "consume from", "subscribe to", "listen for", "poll queue"
  → callback pattern, `(Message -> IO ()) -> IO ()`

### Specific Queue/Event Systems
- "SQS" → `SQSError`, `QueueUrl`
- "SNS" → `SNSError`, `TopicArn`
- "EventBridge" → `EventBridgeError`
- "Kafka" → `KafkaError`, `Topic`

### API Calls (IO read/write)
- "call the API", "request from", "POST to", "GET from"
  → `IO (Either HttpError Response)`
- "webhook", "notify", "callback"
  → `IO (Either HttpError ())`

### Specific API Systems
- "GraphQL" → `GraphQLError`, structured queries
- "Airtable API" → `AirtableError`
- "Slack", "email", "SMS" → notification, `IO (Either NotifyError ())`

### File System (IO)
- "read file", "load from disk", "open"
  → `IO (Either FileError content)`
- "write file", "save to disk", "export to CSV/JSON"
  → `IO (Either FileError ())`

### Time / Clock (IO read)
- "current time", "now", "today", "timestamp"
  → `IO Time` (reading the clock is IO)

### Random / Non-deterministic (IO)
- "random", "generate ID", "UUID"
  → `IO value`

### Logging / Audit (IO write)
- "log", "audit", "record that", "track"
  → `IO ()` (side effect)

## Pure Function Signals

These phrases indicate the operation is pure (no IO):

- "calculate", "compute", "derive", "determine"
- "transform", "convert", "map", "translate"
- "validate", "check", "verify" (when checking data, not calling external)
- "combine", "merge", "aggregate", "sum", "average"
- "filter", "select" (when operating on in-memory data)
- "format", "render", "display" (when producing a string, not printing)
- "parse", "extract", "decode" (from in-memory data)
- "sort", "rank", "order", "prioritize"

## The Boundary Rule

When a spec describes a pipeline, the IO operations should be at the
edges and pure functions in the middle:

```
"Read project data from Airtable,       ← IO (fetch)
 calculate the priority score,          ← pure
 and store the result in DynamoDB"      ← IO (store)
```

Becomes:

```idris
fetchProject : ProjectId -> IO (Either AirtableError ProjectData)  -- IO edge
calculateScore : ProjectData -> Score                               -- pure core
storeResult : ProjectId -> Score -> IO (Either DynamoError ())      -- IO edge

pipeline : ProjectId -> IO (Either AppError ())
pipeline pid = do
  project <- fetchProject pid
  let score = calculateScore project
  storeResult pid score
```

## Ambiguous Cases

Some phrases could be pure or IO depending on context:

- **"look up"** — from a local map? Pure. From a database? IO.
  → Default to IO if the source isn't specified.
  → Use a typed hole if unclear: `lookupValue key = ?is_this_a_db_lookup`

- **"check if exists"** — in a list? Pure. In a database? IO.
  → Same rule: default to IO, hole if ambiguous.

- **"get the project's deadline"** — from an in-memory record? Pure.
  From an API? IO.
  → If the spec has already fetched the project, it's pure field access.
  → If not, it's IO.

- **"send"** — almost always IO. Even "send to the next step" in a
  pipeline should be modeled as a function call (pure) unless it
  crosses a system boundary.

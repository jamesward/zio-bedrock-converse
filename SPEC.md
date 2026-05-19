# zio-bedrock-converse

A Scala 3 / ZIO library for Amazon Bedrock's [Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html),
authenticated with Bedrock [API keys](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys.html) (bearer tokens) — no SigV4.

## Goals

1. **Two single-turn APIs side-by-side.**
   - **`Bedrock.converse`** — low-level: caller sends `RequestConfig`, observes `ContentBlock.ToolUse`, drives the round-trip. Wire shape is exposed.
   - **`Bedrock.request`** — high-level: caller bundles handlers in a `NamedTuple`, gets a typed result via an exhaustive `.fold`. Wire shape is hidden.
2. **Tool calls are typed Scala functions.** Any function `I => A` (pure) or `I => ZIO[R, E, A]` (effectful) becomes a `Bedrock.ToolHandler` via `f.asHandler("description")`. `Schema[I]`, `Schema[A]`, and (for effectful) `Schema[E]` are captured at the call site.
3. **Builder/service split.** Both `Bedrock.converse(...)` and `Bedrock.request(...)` return pure-data builders. Their terminals require `Bedrock.Client` in the ZIO env. `Bedrock.Client` is the only thing that touches the wire — it's provided as a `ZLayer`, and is a `trait` so test mocks plug in directly.
4. **Clean public surface; wire protocol is internal.** End-user types never expose `DynamicValue`, `Option`, `JsonSchema`, or any other wire-format detail. Optionality on public types uses `T | Null`; the internal wire uses `Option`. Tool input arriving as JSON from the model is wrapped in an opaque `Bedrock.ToolInput` whose only public method is `as[I: Schema]: Either[String, I]`.
5. **Errors are explicit.** Wire/protocol failures live in the ZIO error channel as `Bedrock.Error`. For `Bedrock.request`, handler `ZIO.fail(e)` propagates as `e` in the same channel — typed as the union of every registered handler's `E`. The compiler shows you exactly which error types you must address.
6. **The multi-turn loop is deferred.** When `Bedrock.loop` returns, it'll reuse `ToolHandler` exactly as-is and use the captured `Schema[E]` to wire-encode tool failures back to the model.
7. **Mockable.** `BedrockMock(behaviors: MockBehavior*)` (in `src/test`) provides a scripted, deterministic `Bedrock.Client` for unit tests.

## Non-goals

- SigV4 — only Bedrock API key bearer auth is supported.
- `ConverseStream` (SSE / event-stream variant).
- `additionalModelRequestFields` / `additionalModelResponseFields` escape hatches.
- Guardrails, prompt management.
- Multi-modal request inputs (image, document, video). Wire types model them; the public API surfaces only text + tool blocks.
- Multi-turn dispatch — deferred to `Bedrock.loop`.

## Authentication & configuration

Bedrock API keys are sent as `Authorization: Bearer ${AWS_BEARER_TOKEN_BEDROCK}`. The target model is configured on the **client**, not on each request.

| Env var                     | Used by `live` | Required | Default     |
| --------------------------- | -------------- | -------- | ----------- |
| `AWS_BEARER_TOKEN_BEDROCK`  | yes            | yes      | —           |
| `BEDROCK_MODEL_ID`          | yes            | yes      | —           |
| `AWS_REGION`                | yes            | no       | `us-east-1` |

Endpoint: `https://bedrock-runtime.${region}.amazonaws.com`. Model IDs go into the path literally — AWS rejects `%3A`-encoded colons.

## Public API surface

Everything lives under one top-level object. The two halves are separated by responsibility.

### Shared

```scala
package com.jamesward.zio_bedrock_converse

object Bedrock:

  // Opaque IDs
  opaque type ModelId   = String
  opaque type ApiKey    = String
  opaque type ToolName  = String
  opaque type ToolUseId = String

  enum Region(val code: String): … (37 values)
  object Region: def fromCode(code: String): Option[Region]

  enum Role         derives Schema: User | Assistant
  enum StopReason   derives Schema: EndTurn | ToolUse | …

  case class InferenceConfig(maxTokens: Int|Null, temperature: Double|Null, topP: Double|Null, stopSequences: List[String])
  case class TokenUsage(...)
  case class Metrics(latencyMs: Long)

  /** Service trait. Owns the HTTP wire (auth + region + model). */
  trait Client:
    def modelId: ModelId
    private[zio_bedrock_converse] def send(req: Wire.ConverseRequest): IO[Error, Wire.ConverseResponse]

  object Client:
    def layer(apiKey: ApiKey, region: Region, modelId: ModelId): ZLayer[zio.http.Client, Nothing, Client]
    val  live:                                                   ZLayer[zio.http.Client, Error, Client]

  /** Wire envelope returned by every successful round-trip. The `output`
    * type is selected by the terminal: `Output` for `.asResponse`, the
    * structured-output type for `.asResponse[T]`, the fold's `R` for
    * `Bedrock.request#fold`. */
  case class Result[+T](
    output:     T,
    stopReason: StopReason,
    usage:      TokenUsage,
    metrics:    Metrics,
  )
```

### Low-level: `Bedrock.converse`

```scala
  case class RequestConfig(
    messages:        List[Message],
    system:          String | Null          = null,
    inferenceConfig: InferenceConfig | Null = null,
    toolConfig:      ToolConfig | Null      = null,
  )
  object RequestConfig:
    def apply(prompt: String): RequestConfig

  case class Message(role: Role, content: List[ContentBlock]):
    def text: String
  object Message:
    def user(text: String):      Message
    def assistant(text: String): Message

  enum ContentBlock:
    case Text(text: String)
    case ToolUse(toolUseId: ToolUseId, name: ToolName, input: ToolInput)
    case ToolResult(toolUseId: ToolUseId, content: List[ToolResultBlock], status: ToolResultStatus | Null = null)

  case class Output(message: Message):
    def text: String

  case class ToolConfig(tools: List[Tool[?]], toolChoice: ToolChoice = ToolChoice.Auto)
  enum ToolChoice: case Auto | Any | Tool(name: ToolName)

  /** Spec only: name, description, Schema[I]. No handler. */
  final class Tool[I] private (val name: ToolName, val description: String, /* erased */ inputSchema: Schema[?])
  object Tool:
    def apply[I: Schema](name: ToolName, description: String): Tool[I]

  /** `.asTool` derives the tool name from the function reference. */
  extension [I: Schema, A](inline f: I => A)
    inline def asTool(description: String): Tool[I]

  /** Opaque accessor over wire JSON. */
  final class ToolInput private:
    def as[I: Schema]: Either[String, I]
  object ToolInput:
    def from[A: Schema](value: A): ToolInput

  enum ToolResultBlock:
    case Text(text: String)
    case Json(value: ToolInput)
  object ToolResultBlock:
    def json[A: Schema](value: A): ToolResultBlock

  enum ToolResultStatus: case Success | Error

  /** Pure-data builder. */
  final class Request private (cfg: RequestConfig):
    def text:                              ZIO[Client, Error, String]
    def asResponse:                        ZIO[Client, Error, Result[Output]]
    def as[T <: Matchable: Schema]:        ZIO[Client, Error, T]
    def asResponse[T <: Matchable: Schema]: ZIO[Client, Error, Result[T]]

  def converse(cfg: RequestConfig): Request
  def converse(prompt: String):     Request
```

### High-level: `Bedrock.request`

```scala
  /** Tool spec + bound handler + per-tool schemas. The tool name comes
    * from the surrounding NamedTuple key when the handler is bundled
    * into a request — not stored here. */
  final class ToolHandler[-I, -R, +E <: Matchable, +A <: Matchable] private (
    val description: String,
    /* erased */     inputSchema:  Schema[?],
    /* erased */     errorSchema:  Schema[?],
    /* erased */     outputSchema: Schema[?],
    /* erased */     handler:      I => ZIO[R, E, A],
  )

  object ToolHandler:
    def apply[I: Schema, R, E <: Matchable: Schema, A <: Matchable: Schema](
      f: I => ZIO[R, E, A], description: String,
    ): ToolHandler[I, R, E, A]
    def fromPure[I: Schema, A <: Matchable: Schema](
      f: I => A, description: String,
    ): ToolHandler[I, Any, Nothing, A]

  /** `.asHandler` extensions. Effectful overload requires `Schema[E]`. */
  extension [I: Schema, A <: Matchable: Schema](f: I => A)
    def asHandler(description: String): ToolHandler[I, Any, Nothing, A]
  extension [I: Schema, R, E <: Matchable: Schema, A <: Matchable: Schema](f: I => ZIO[R, E, A])
    def asHandler(description: String): ToolHandler[I, R, E, A]

  /** Opt-in declaration that the model can produce a final reply.
    * Without one, `toolChoice = Any` forces the model to dispatch a tool. */
  sealed trait ModelResponseTool[+A <: Matchable]
  object ModelResponseTool:
    case object text extends ModelResponseTool[String]
    final class Structured[+A <: Matchable] private (val description: String, …)
        extends ModelResponseTool[A]
    def apply[A <: Matchable: Schema](description: String): Structured[A]

  /** Builder returned by `Bedrock.request`. */
  final class TooledRequest[NT <: NamedTuple.AnyNamedTuple] private:
    def system        (s: String):           TooledRequest[NT]
    def inferenceConfig(c: InferenceConfig): TooledRequest[NT]

    /** Exhaustive fold. Each tool's typed output flows into the matching
      * NamedTuple-keyed function, all unifying into `R`. */
    inline def fold[R <: Matchable](
      cases: NamedTuple.NamedTuple[
        NamedTuple.Names[NT],
        FoldFns[NamedTuple.DropNames[NT], R],
      ],
    ): ZIO[
      Client & EnvOf[NamedTuple.DropNames[NT]],
      Error | ErrorsOf[NamedTuple.DropNames[NT]],
      Result[R],
    ]

  /** Build a single-turn high-level request.
    *
    * Compile-time enforcement:
    *   - `NamedTuple.DropNames[NT] <:< NonEmptyTuple` — non-empty.
    *   - `AllTools[NamedTuple.DropNames[NT]]` — every element is a
    *     `ToolHandler[…]` or a `ModelResponseTool[…]`.
    *   - At most one of `ModelResponseTool.text` / `ModelResponseTool[A]`. */
  inline def request[NT <: NamedTuple.AnyNamedTuple](
    prompt: String,
    tools:  NT,
  )(using
    inline ev:       NamedTuple.DropNames[NT] <:< NonEmptyTuple,
    inline allTools: AllTools[NamedTuple.DropNames[NT]],
  ): TooledRequest[NT]
```

### Multi-turn: `Bedrock.loop`

```scala
  /** Multi-turn agentic loop. Only ToolHandlers allowed (no ModelResponseTool).
    * The terminal determines the reply shape. */
  final class LoopRequest[NT <: NamedTuple.AnyNamedTuple]:
    def system(s: String):              LoopRequest[NT]
    def inferenceConfig(c: InferenceConfig): LoopRequest[NT]
    def maxIterations(n: Int):          LoopRequest[NT]

    def text:                              ZIO[Client & EnvOf[Hs], Error, String]
    def asResponse:                        ZIO[Client & EnvOf[Hs], Error, Result[Output]]
    def as[T <: Matchable: Schema]:        ZIO[Client & EnvOf[Hs], Error, T]
    def asResponse[T <: Matchable: Schema]: ZIO[Client & EnvOf[Hs], Error, Result[T]]

  /** Compile-time: NonEmptyTuple, AllTools[Hs], no ModelResponseTool. */
  inline def loop[NT <: NamedTuple.AnyNamedTuple](
    prompt: String,
    tools:  NT,
  )(using
    inline ev:       NamedTuple.DropNames[NT] <:< NonEmptyTuple,
    inline allTools: AllTools[NamedTuple.DropNames[NT]],
  ): LoopRequest[NT]
```

### Type-level helpers (high-level)

Match types deriving the dependent positions of `fold`'s signature:

```scala
type EnvOf[Hs <: Tuple] <: Any = Hs match
  case ToolHandler[?, r, ?, ?] *: rest => r & EnvOf[rest]
  case _                       *: rest => EnvOf[rest]
  case EmptyTuple                       => Any

type ErrorsOf[Hs <: Tuple] <: Matchable = Hs match
  case ToolHandler[?, ?, e, ?] *: rest => e | ErrorsOf[rest]
  case _                       *: rest => ErrorsOf[rest]
  case EmptyTuple                       => Nothing

type OutputOf[T] <: Matchable = T match
  case ToolHandler[?, ?, ?, a]         => a
  case ModelResponseTool.Structured[a] => a
  case ModelResponseTool.text.type     => String

type FoldFns[Hs <: Tuple, R] <: Tuple = Hs match
  case h *: rest  => (OutputOf[h] => R) *: FoldFns[rest, R]
  case EmptyTuple => EmptyTuple
```

Inductive-given evidence:

```scala
sealed trait AllTools[Hs <: Tuple]
object AllTools:
  given AllTools[EmptyTuple]
  given consHandler[I, R, E <: Matchable, A <: Matchable, Tail <: Tuple]
    (using AllTools[Tail])                : AllTools[ToolHandler[I, R, E, A] *: Tail]
  given consText[Tail <: Tuple]
    (using AllTools[Tail])                : AllTools[ModelResponseTool.text.type *: Tail]
  given consStructured[A <: Matchable, Tail <: Tuple]
    (using AllTools[Tail])                : AllTools[ModelResponseTool.Structured[A] *: Tail]
```

## Errors

```scala
sealed trait Error extends Throwable
object Error:
  // Bedrock HTTP status codes
  final case class Validation        (message: String)                                 extends Error  // 400
  final case class AccessDenied      (message: String)                                 extends Error  // 403
  final case class ResourceNotFound  (message: String)                                 extends Error  // 404
  final case class ModelTimeout      (message: String)                                 extends Error  // 408
  final case class ModelErr          (message: String, originalStatusCode: Int|Null)   extends Error  // 424
  final case class Throttling        (message: String)                                 extends Error  // 429
  final case class InternalServer    (message: String)                                 extends Error  // 500
  final case class ServiceUnavailable(message: String)                                 extends Error  // 503
  final case class Unexpected        (status: Status, body: String)                    extends Error
  final case class Transport         (cause: Throwable)                                extends Error
  // Config
  final case class MissingApiKey()                                                     extends Error
  final case class MissingModelId()                                                    extends Error
  // Decode failure for ModelResponseTool[A] / .as[T]
  final case class StructuredDecode  (responseText: String, message: String)           extends Error
  // High-level (Bedrock.request) only
  final case class UnknownTool       (name: ToolName)                                  extends Error
  final case class InvalidToolInput  (name: ToolName, message: String)                 extends Error
  final case class UnexpectedReply   (description: String)                             extends Error

  extension [R, A](zio: ZIO[R, Error, A])
    /** Retries Throttling / InternalServer / ServiceUnavailable / ModelTimeout up to 2x with backoff. */
    def retryOnRetryable: ZIO[R, Error, A]
```

For `Bedrock.request`, the ZIO error channel is `Error | ErrorsOf[Hs]`. Handler ZIO failures propagate as the union; defects (`ZIO.die`) propagate as defects.

## Dispatch model

`Bedrock.converse(cfg)` does exactly one HTTP round-trip. It does not dispatch tools — it returns the wire response with `ContentBlock.ToolUse` blocks visible. The caller drives the round-trip themselves.

`Bedrock.request(prompt, tools)` does exactly one HTTP round-trip and dispatches at most one of the registered tools or reply tool:

```
┌─ Bedrock.request(prompt, namedTupleOfTools).fold(cases) ────────────────┐
│                                                                          │
│   build wire request:                                                    │
│     toolConfig.tools = each registered ToolHandler advertised by         │
│       its NamedTuple key, with Schema[I] → JsonSchema                    │
│     toolChoice       = ModelResponseTool registered ? Auto : Any         │
│     outputConfig     = ModelResponseTool[A] registered ?                 │
│                          json_schema(Schema[A]) : none                   │
│                                                                          │
│   send → wire response                                                   │
│                                                                          │
│   if response has ContentBlock.ToolUse:                                  │
│     lookup name in handler registry                                      │
│       missing  → fail Error.UnknownTool                                  │
│     decode input via Schema[I]                                           │
│       fail     → fail Error.InvalidToolInput                             │
│     run handler                                                          │
│       success  → apply fold case for this name → Result.Ok(R)            │
│       fail(e)  → fail e (typed via ErrorsOf[Hs])                         │
│                                                                          │
│   else if a ModelResponseTool is registered:                             │
│     ModelResponseTool.text → take text content, call fold's reply        │
│                                case with String                          │
│     ModelResponseTool[A]   → decode text via Schema[A]                   │
│                                fail → Error.StructuredDecode             │
│                                ok   → call fold's reply case with A      │
│                                                                          │
│   else: fail Error.UnexpectedReply                                       │
└──────────────────────────────────────────────────────────────────────────┘
```

`.as[T]` and `.asResponse[T]` (low-level) thread an `outputConfig.textFormat = json_schema` derived from `Schema[T]`. The `withStrictObjects` helper recursively sets `additionalProperties: false` on every object schema — Bedrock rejects schemas that don't.

## Loop dispatch model

`Bedrock.loop(prompt, tools)` drives a bounded multi-turn loop:

- Build wire request: `toolConfig.tools` from NamedTuple keys, `toolChoice = Auto`, `outputConfig` from terminal (`.as[T]` sets `json_schema`).
- Loop (bounded by `maxIterations`, default 10):
  - Send → wire response.
  - If `tool_use` blocks present: dispatch each tool. Unknown tool / invalid input / handler failure → encode as error `tool_result` and feed back. Success → encode output and feed back. Append assistant + user turns. Loop.
  - Else (model replied): return text or decode structured output via terminal.
- If iterations exceed `maxIterations`: fail `Error.MaxIterations`.

Key differences from `Bedrock.request`:
- **Multi-turn**: tool results are sent back; model is re-invoked.
- **Handler errors absorbed**: `ZIO.fail(e)` encoded via `Schema[E]`, fed back as `tool_result.status = Error`.
- **No `ModelResponseTool` in user API**: reply shape from terminal.
- **ZIO error channel**: only `Bedrock.Error` (wire/protocol + `MaxIterations`).
- **Debug logging**: `ZIO.logDebug` at each iteration. Set log level to `DEBUG` to see.

## Mock layer

Lives in `src/test/scala/.../BedrockMock.scala` — not in the production jar.

```scala
object BedrockMock:
  sealed trait MockBehavior
  object MockBehavior:
    case class Reply(text: String)                          extends MockBehavior
    case class ReplyJson[T: Schema](value: T)               extends MockBehavior
    case class CallTool[I: Schema](toolName: ToolName, input: I) extends MockBehavior
    case class Fail(error: Bedrock.Error)                   extends MockBehavior

  def apply(behaviors: MockBehavior*): ULayer[Bedrock.Client]
```

`BedrockMock(...)` builds a `Bedrock.Client` whose `send` consumes one behavior per call from a scripted queue. Both the low-level `Bedrock.converse` and the high-level `Bedrock.request` are tested against this mock.

## Test layout

`SharedSpec` declares two scenario lists:

- **`bedrockScenarios`** — exercises `Bedrock.converse`. Run against both mock and live.
- **`bedrockRequestScenarios`** — exercises `Bedrock.request`. Run against the mock only (the failure / unknown-tool / unexpected-reply paths require deterministic responses that the live model can't be trusted to produce).

```
BedrockMockSpec        — all scenarios (converse + request + loop)        vs mock
BedrockIntegrationSpec — converse + request (live-friendly) + loop-live   vs live
```

```scala
trait BedrockScenario:
  def name:       String
  def run:        ZIO[Bedrock.Client, Any, TestResult]
  def mockScript: List[BedrockMock.MockBehavior]
```

High-level scenarios cover:

1. Pure handler dispatched, fold returns mapped output.
2. Effectful handler succeeds, fold returns mapped output.
3. Effectful handler fails — typed `E` propagates in the ZIO error channel.
4. `ModelResponseTool.text` registered — text reply routes to fold's reply case.
5. `ModelResponseTool[Forecast]` registered — JSON decoded via Schema, routed to fold.
6. Model invokes an unregistered tool name → `Bedrock.Error.UnknownTool`.
7. Text reply with no `ModelResponseTool` registered → `Bedrock.Error.UnexpectedReply`.

Low-level scenarios cover text-only, `.asResponse` envelope, structured output, and a manual tool-dispatch round-trip.

## File layout

```
src/main/scala/com/jamesward/zio_bedrock_converse/
  Bedrock.scala              — top-level object: Client, Request, RequestConfig,
                                Result, Output, Tool, ToolConfig, ToolChoice,
                                ToolInput, ToolResultBlock, ToolResultStatus,
                                ContentBlock, Message, Error, opaque IDs, Region,
                                ToolHandler, ModelResponseTool, TooledRequest,
                                AllTools, EnvOf, ErrorsOf, OutputOf, FoldFns,
                                CountText, CountStructured, request().
  internal/
    Codecs.scala             — Schema helpers (nullable, codecConfig, Schema[DynamicValue])
    Wire.scala               — wire-format types
    Http.scala               — buildClient + private HttpClient impl
    Tools.scala              — toWire (single-turn translation only)
    ToolMacros.scala         — `.asTool` macro (function-name extraction for low-level Tool)

src/test/scala/com/jamesward/zio_bedrock_converse/
  BedrockMock.scala          — scripted Bedrock.Client + MockBehavior ADT
  SharedSpec.scala           — bedrockScenarios + bedrockRequestScenarios
  BedrockMockSpec.scala
  BedrockIntegrationSpec.scala
```

The `internal` package is never imported by users. Wire types live there and use `Option` for optional fields (simpler `Schema` derivation); the public API uses `T | Null` (better ergonomics — no `Some(…)` wrapping).

## JSON encoding strategy

- `zio-schema-derivation` for type derivation on wire types.
- `zio-schema-json` `JsonCodec.schemaBasedBinaryCodec` in both directions.
- `Configuration(explicitEmptyCollections = (false, false), explicitNulls = (false, false))` — drop nulls and empty collections from the wire.
- Public `T | Null` fields → `Schema[T | Null]` derived via `Schema.Optional(Schema[T]).transform(...)`. Reverse uses `eq null` to dodge strict-equality friction.
- Field-name unions (e.g. `ContentBlock`) → `@noDiscriminator` enum + single-field case wrapper.
- Tagged unions (only `outputConfig.textFormat`) → `@discriminatorName("type")` + `@caseName(...)`.
- Tool input JSON shape comes from `zio.http.endpoint.openapi.JsonSchema.fromZSchema(schema, Inline)`, recursively patched with `additionalProperties: false` on every `Object` (Bedrock requirement).
- Tool-use input arrives from the model as a JSON object — internally held as `DynamicValue` annotated `@directDynamicMapping` (so the codec treats it as natural JSON, not as the structured `DynamicValue` ADT). On the public surface it is wrapped in `Bedrock.ToolInput`; `DynamicValue` is **never** exposed in any public signature.

## Examples

### Plain text

```scala
Bedrock
  .converse(RequestConfig("Hello!"))
  .text
  .debug
  .provide(Client.default, Bedrock.Client.live)
```

### High-level tools

```scala
case class WeatherInput(city: String) derives Schema
case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema
case class Forecast(city: String, summary: String) derives Schema

def get_weather(in: WeatherInput): WeatherOutput =
  WeatherOutput(64, "foggy")

val tools = (
  weather = get_weather.asHandler("Get the current weather (F + conditions) for a US city."),
  reply   = ModelResponseTool[Forecast]("Summarise as a one-line Forecast."),
)

val program: ZIO[Bedrock.Client, Bedrock.Error, Bedrock.Result[Forecast]] =
  Bedrock.request("Weather of Denver?", tools).fold[Forecast]:
    (weather = (w: WeatherOutput) => Forecast("Denver", s"${w.temperatureF}°F"),
     reply   = (f: Forecast)      => f)
```

The `fold[Forecast]:` colon-block syntax avoids the awkward double parens that would otherwise be needed to disambiguate a NamedTuple literal from named-arg syntax.

### Effectful handler with typed error

```scala
case class PopErr(message: String) derives Schema           // Schema[E] required
trait PopulationService:
  def populationOf(city: String): IO[PopErr, Int]

def get_population(in: PopulationInput): ZIO[PopulationService, PopErr, Int] =
  ZIO.serviceWithZIO[PopulationService](_.populationOf(in.city))

val tools = (
  population = get_population.asHandler("Get the population of a city."),
)

val program: ZIO[
  Bedrock.Client & PopulationService,
  Bedrock.Error | PopErr,                  // ← typed E in the union
  Bedrock.Result[String],
] =
  Bedrock.request("Population of Berlin?", tools).fold[String]:
    (population = (p: Int) => s"$p people")
```

### Low-level manual tool round-trip

```scala
val tool = get_weather.asTool("…")          // Tool[WeatherInput] — spec only

val initial = RequestConfig(
  messages   = List(Message.user("Weather in SF?")),
  toolConfig = ToolConfig(tools = List(tool)),
)

Bedrock.converse(initial).asResponse.flatMap: first =>
  first.output.message.content.collectFirst {
    case ContentBlock.ToolUse(id, _, input) => (id, input)
  } match
    case Some((id, input)) =>
      val answer = input.as[WeatherInput].fold(
        err => WeatherOutput(0, s"decode failed: $err"),
        get_weather,
      )
      Bedrock.converse(initial.copy(
        messages = initial.messages
          :+ first.output.message
          :+ Message(Role.User, List(ContentBlock.ToolResult(
            toolUseId = id,
            content   = List(ToolResultBlock.json(answer)),
          ))),
      )).text

    case None =>
      ZIO.succeed(first.output.text)
```

### Structured output (low-level)

```scala
Bedrock
  .converse(RequestConfig("One-sentence Seattle forecast. Fill in city and summary."))
  .as[Forecast]
// : ZIO[Bedrock.Client, Bedrock.Error, Forecast]
```

### Mock test

```scala
import BedrockMock.MockBehavior.*

val tools = (
  weather = get_weather.asHandler("…"),
  reply   = ModelResponseTool.text,
)

val test = Bedrock.request("Hello", tools).fold[String]:
  (weather = (w: WeatherOutput) => s"weather: $w",
   reply   = (s: String)        => s"reply: $s")
.provideLayer(BedrockMock(Reply("Hi there!")))
```

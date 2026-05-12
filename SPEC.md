# zio-bedrock-converse

A Scala 3 / ZIO library for Amazon Bedrock's [Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html),
authenticated with Bedrock [API keys](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys.html) (bearer tokens) — no SigV4.

## Goals

1. **Tool calls are typed Scala functions.** Any function `I => O` (pure) or `I => ZIO[R, E, O]` (effectful) becomes a tool via `f.asTool("description")`. `Schema[I]` and `Schema[O]` are captured at the call site; the tool's name is derived from the function reference at compile time via a Scala 3 macro.
2. **Multi-turn tool dispatch is built in.** `converse(req, tool1, tool2, …)` runs the model, executes any tool calls, feeds results back, and repeats until the model returns a final answer.
3. **Variance composes `R` and `E` automatically.** `Tool[-I, +O, -R, +E]` lets the compiler intersect each tool's environment and union each tool's error — no tuple syntax, no macros at the call site (only inside `.asTool`).
4. **Clean public surface; wire protocol is internal.** End-user types never expose `DynamicValue`, `Option`, `JsonSchema`, or any other wire-format detail. Optionality on public types uses `T | Null`; the internal wire uses `Option`.
5. **Mockable.** `BedrockConverseMock(behaviors: MockBehavior*)` (in `src/test`) provides a scripted, deterministic service for unit tests; the same scenario specs run against `mock` (unit) and `live` (integration).

## Non-goals

- SigV4 — only Bedrock API key bearer auth is supported.
- `ConverseStream` (SSE / event-stream variant).
- `additionalModelRequestFields` / `additionalModelResponseFields` escape hatches.
- Guardrails, prompt management.
- Multi-modal request inputs (image, document, video). Wire types model them; the public API is text-only for now.

## Authentication & configuration

Bedrock API keys are sent as `Authorization: Bearer ${AWS_BEARER_TOKEN_BEDROCK}`. The target model is configured on the **service**, not on each request.

| Env var                     | Used by `live` | Required | Default     |
| --------------------------- | -------------- | -------- | ----------- |
| `AWS_BEARER_TOKEN_BEDROCK`  | yes            | yes      | —           |
| `BEDROCK_MODEL_ID`          | yes            | yes      | —           |
| `AWS_REGION`                | yes            | no       | `us-east-1` |

Endpoint: `https://bedrock-runtime.${region}.amazonaws.com`. Model IDs go into the path literally — AWS rejects `%3A`-encoded colons.

## Public API surface

```scala
package com.jamesward.zio_bedrock_converse

class BedrockConverse:
  /** Build an invoker for `req` with the supplied tools. Choose the
    * output shape via `.text`, `.asResponse`, `.as[T]`, or
    * `.asResponse[T]`. */
  def converse[R, E](req: ConverseRequest, tools: Tool[?, ?, R, E]*):
      ConverseInvoker[R, E]

object BedrockConverse:
  // Opaque IDs
  opaque type ModelId   = String
  opaque type ApiKey    = String
  opaque type ToolName  = String
  opaque type ToolUseId = String

  // 37 AWS regions exposed as enum values
  enum Region(val code: String): …
  object Region:
    def fromCode(code: String): Option[Region]

  // Layers
  def layer(apiKey: ApiKey, region: Region, modelId: ModelId): ZLayer[Client, Nothing, BedrockConverse]
  val live: ZLayer[Client, ConverseError, BedrockConverse]

  // .asTool extension methods (one per pure/effectful function shape)
  extension [I, O](inline f: I => O)
    inline def asTool(description: String)(using inline Schema[I], inline Schema[O]):
        Tool[I, O, Any, Nothing]

  extension [I, O, R, E](inline f: I => ZIO[R, E, O])
    inline def asTool(description: String)(using inline Schema[I], inline Schema[O]):
        Tool[I, O, R, E]

  // Top-level accessor: returns an invoker that requires `BedrockConverse`
  // in its environment.
  def converse[R, E](req: ConverseRequest, tools: Tool[?, ?, R, E]*):
      ConverseInvoker[BedrockConverse & R, E]
```

> `BedrockConverseMock` lives in `src/test` — it isn't part of the production JAR. See **Mock layer**.

### `ConverseInvoker`

```scala
final class ConverseInvoker[R, E]:
  /** Final assistant text, joined across the model's content blocks. */
  def text: ZIO[R, ConverseError | E, String]

  /** Full response, with the assistant turn's text content blocks in
    * `output: ConverseOutput`. */
  def asResponse: ZIO[R, ConverseError | E, ConverseResponse[ConverseOutput]]

  /** Structured output: the model is told to produce JSON conforming to
    * `Schema[T]`, and the final text block is decoded into a `T`. */
  def as[T: Schema]: ZIO[R, ConverseError | E, T]

  /** Structured output with the full response envelope. */
  def asResponse[T: Schema]: ZIO[R, ConverseError | E, ConverseResponse[T]]
```

Each of `.text`, `.asResponse`, `.as[T]`, `.asResponse[T]` drives the
**same** multi-turn tool-dispatch loop — the difference is what comes
out at the end. `.text` is sugar for `.asResponse.map(_.output.text)`;
`.as[T]` is sugar for `.asResponse[T].map(_.output)`.

### Request / Response

```scala
/** A content block in a [[Message]]. Text-only for now; multimodal
  * shapes will be added later as additional cases. */
enum ContentBlock:
  case Text(text: String)

/** Mirrors AWS's wire `{"role": …, "content": [...]}` shape. */
case class Message(role: Role, content: List[ContentBlock]):
  /** Joined text from every `Text` content block. */
  def text: String

object Message:
  def user(text: String):      Message
  def assistant(text: String): Message

case class ConverseRequest(
  messages:        List[Message],
  system:          String | Null            = null,
  inferenceConfig: InferenceConfig | Null   = null,
)
object ConverseRequest:
  /** Single-message convenience: `ConverseRequest("hello")`. */
  def apply(prompt: String): ConverseRequest

/** Default `output` shape used by `.asResponse` (i.e. when the caller
  * isn't asking for structured output). Mirrors AWS's wire
  * `output.message` envelope. */
case class ConverseOutput(message: Message):
  /** Joined text from the assistant message's text content blocks. */
  def text: String

/** Full response from a converse call, parameterised over the `output`
  * type. `output` is a `ConverseOutput` for `.asResponse`, the
  * structured-output type `T` for `.asResponse[T]`. */
case class ConverseResponse[+T](
  output:     T,
  stopReason: StopReason,
  usage:      TokenUsage,
  metrics:    ConverseMetrics,
)

case class InferenceConfig(
  maxTokens:     Int    | Null = null,
  temperature:   Double | Null = null,
  topP:          Double | Null = null,
  stopSequences: List[String]  = Nil,
)
```

`ConverseRequest` deliberately has **no tool configuration field** — tools are passed alongside the request to `converse(req, tools…)`.

### Tool

```scala
final class Tool[-I, +O, -R, +E]

object Tool:
  /** Explicit-name constructors. Public callers usually go through the
    * `.asTool` extension methods, which derive the tool name from the
    * function reference at compile time. */
  def makePure[I: Schema, O: Schema](
    name:        ToolName,
    description: String,
    f:           I => O,
  ): Tool[I, O, Any, Nothing]

  def makeZIO[I: Schema, O: Schema, R, E](
    name:        ToolName,
    description: String,
    f:           I => ZIO[R, E, O],
  ): Tool[I, O, R, E]

// Extension methods on the BedrockConverse object derive the name from
// the function literal at the call site:
def get_weather(in: WeatherInput): WeatherOutput = …
val weatherTool = get_weather.asTool("Get the current weather for a city.")
// weatherTool.name == ToolName("get_weather")
```

Name extraction is a Scala 3 macro (`internal.ToolMacros`) that walks the
AST of the function reference and pulls the method name out of the
eta-expanded closure. Recognised shapes:

- bare reference:           `get_weather`            (eta-expanded)
- explicit eta-expansion:   `get_weather _`
- lambda calling a method:  `(x: I) => get_weather(x)`

Anonymous closures with no obvious method name fail at compile time with
a message pointing the caller at `Tool.makePure` / `Tool.makeZIO`.

#### Variance composes `R` and `E`

With `Tool[-I, +O, -R, +E]`, calling `converse(req, tool1, tool2)` where
the tools have different `R` / `E`:

```scala
val pop:   Tool[?, ?, PopulationService, PopErr]     = …
val clock: Tool[?, ?, ClockService,      ParseError] = …
converse(req, pop, clock)
// inferred R = PopulationService & ClockService
// inferred E = PopErr | ParseError
// effect    : ZIO[PopulationService & ClockService & BedrockConverse,
//                 PopErr | ParseError | ConverseError,
//                 ConverseResponse]
```

This is the same trick `zio-http`'s `Routes[-Env, +Err]` uses for
`Routes ++ Routes`: contravariant `R` pins to the intersection,
covariant `E` pins to the union, no tuple wrapping at the call site.

`Schema[I]` and `Schema[O]` are captured by `.asTool` (via the macro)
or by `Tool.makePure` / `Tool.makeZIO`. Internally a `Tool` carries:

```scala
final class Tool[-I, +O, -R, +E] private (
  val name:        ToolName,
  val description: String,
  // internal — never appear in any public signature:
  /* erased */ inputSchema:  Schema[?],
  /* erased */ outputSchema: Schema[?],
  /* erased */ handler:      Any => ZIO[R, E, Any],
)
```

The erased fields preserve variance (none of `I` or `O` survives at the
value level). The handler is invoked through the dispatcher, which feeds
it an `inputSchema`-decoded value, so the casts are safe in practice.

## Tool-dispatch loop

```
┌─ converse(req, tools…).{text|asResponse|as[T]|asResponse[T]} ───────┐
│                                                                       │
│  build Wire.ConverseRequest with toolConfig from `tools`              │
│                                                                       │
│  ╭─loop ─────────────────────────────────────────────────────────╮    │
│  │  POST /model/${modelId}/converse                               │    │
│  │  parse Wire.ConverseResponse                                   │    │
│  │                                                                │    │
│  │  stopReason == ToolUse?                                        │    │
│  │     yes →  for each ContentBlock.ToolUse:                      │    │
│  │              look up Tool by name (else fail UnknownTool)      │    │
│  │              decode input via tool.inputSchema                 │    │
│  │                  (decode failure ⇒ InvalidToolInput)           │    │
│  │              run tool.handler(input)                           │    │
│  │                  (ZIO failure ⇒ propagate E to caller)         │    │
│  │              encode output via tool.outputSchema               │    │
│  │            append assistant turn + user-toolResult turn        │    │
│  │            loop                                                │    │
│  │     no  →  collect Text content, return ConverseResponse       │    │
│  ╰────────────────────────────────────────────────────────────────╯    │
└───────────────────────────────────────────────────────────────────────┘
```

`.as[T]` and `.asResponse[T]` wrap the same loop with `outputConfig.textFormat = json_schema`. Once the model stops calling tools, the final text block is decoded through `Schema[T]`.

## Errors

```scala
sealed trait ConverseError extends Throwable
object ConverseError:
  // Bedrock HTTP status codes
  final case class Validation        (message: String)                                 extends ConverseError  // 400
  final case class AccessDenied      (message: String)                                 extends ConverseError  // 403
  final case class ResourceNotFound  (message: String)                                 extends ConverseError  // 404
  final case class ModelTimeout      (message: String)                                 extends ConverseError  // 408
  final case class ModelErr          (message: String, originalStatusCode: Int|Null)   extends ConverseError  // 424
  final case class Throttling        (message: String)                                 extends ConverseError  // 429
  final case class InternalServer    (message: String)                                 extends ConverseError  // 500
  final case class ServiceUnavailable(message: String)                                 extends ConverseError  // 503
  final case class Unexpected        (status: Status, body: String)                    extends ConverseError
  final case class Transport         (cause: Throwable)                                extends ConverseError

  // Config
  final case class MissingApiKey()                                                     extends ConverseError
  final case class MissingModelId()                                                    extends ConverseError

  // Structured output
  final case class StructuredDecode  (responseText: String, message: String)           extends ConverseError

  // Tool dispatch
  final case class UnknownTool       (name: ToolName)                                  extends ConverseError
  final case class InvalidToolInput  (name: ToolName, message: String)                 extends ConverseError

  extension [R, A](zio: ZIO[R, ConverseError, A])
    /** Retries Throttling / InternalServer / ServiceUnavailable / ModelTimeout up to 2x with backoff. */
    def retryOnRetryable: ZIO[R, ConverseError, A]
```

Tool ZIO failures propagate as the tool's `E` (unioned with `ConverseError`). They do **not** get sent back to the model as `toolResult.status = error`.

## Mock layer

Lives in `src/test/scala/.../BedrockConverseMock.scala` — not in the production jar.

```scala
object BedrockConverseMock:
  sealed trait MockBehavior
  object MockBehavior:
    case class Reply(text: String) extends MockBehavior
    case class ReplyJson[T: Schema](value: T)                          extends MockBehavior
    case class CallTool[I: Schema](toolName: ToolName, input: I)       extends MockBehavior
    case class Fail(error: ConverseError)                              extends MockBehavior

  def apply(behaviors: MockBehavior*): ULayer[BedrockConverse]
```

`BedrockConverseMock(...)` builds a `BedrockConverse` whose responses come from a scripted queue rather than HTTP. Each `converse` round consumes one behavior. `CallTool` produces a `tool_use` wire response — the dispatcher then runs the matching tool, sends the result back, and consumes the next behavior for the follow-up turn.

This makes the tool-dispatch loop testable without credentials. The `[T: Schema]` / `[I: Schema]` context bounds on the case classes capture the schema as a public `val` on the case via `summon`, so the responder can encode the payload at dispatch time.

## Test layout

A single `SharedSpec` declares each scenario once. `MockSpec` runs every scenario against `BedrockConverse.mock(scenario.mockScript*)` (deterministic); `IntegrationSpec` runs the same scenarios against `BedrockConverse.layer(apiKey, region, modelId)` (gated on `AWS_BEARER_TOKEN_BEDROCK`).

```scala
trait Scenario:
  def name:       String
  def run:        ZIO[BedrockConverse, Any, TestResult]
  def mockScript: List[MockBehavior]
```

Scenarios cover:

1. Text-only request.
2. Single tool call (weather lookup).
3. Multiple tools (model picks one).
4. Tool ZIO failure → propagates as the tool's `E`.
5. Model invokes an unregistered tool → `ConverseError.UnknownTool`.
6. Structured output (no tools).
7. Structured output + tool call.

## File layout

```
src/main/scala/com/jamesward/zio_bedrock_converse/
  BedrockConverse.scala            — public API: class + service object
  internal/
    Codecs.scala                   — Schema helpers (nullable, codecConfig, DynamicValue)
    Wire.scala                     — wire-format types (parameterised by T = DynamicValue)
    Http.scala                     — buildService + HTTP `Sender`
    Tools.scala                    — multi-turn tool dispatch loop
    ToolMacros.scala               — `.asTool` macro implementations (name extraction)

src/test/scala/com/jamesward/zio_bedrock_converse/
  BedrockConverseMock.scala        — test-only mock layer + MockBehavior ADT
  SharedSpec.scala                 — scenario definitions
  MockSpec.scala                   — runs scenarios against `BedrockConverseMock`
  IntegrationSpec.scala            — runs scenarios against `BedrockConverse.live`
```

The `internal` package is never imported by users. Wire types live there and use `Option` for optional fields (simpler `Schema` derivation); the public API uses `T | Null` (better ergonomics — no `Some(…)` wrapping).

## JSON encoding strategy

- `zio-schema-derivation` for type derivation on wire types.
- `zio-schema-json` `JsonCodec.schemaBasedBinaryCodec` in both directions.
- `Configuration(explicitEmptyCollections = (false, false), explicitNulls = (false, false))` — drop nulls and empty collections from the wire.
- Public `T | Null` fields → `Schema[T | Null]` derived via `Schema.Optional(Schema[T]).transform(...)`. Reverse uses `eq null` to dodge strict-equality friction.
- Field-name unions (e.g. `ContentBlock`) → `@noDiscriminator` enum + single-field case wrapper.
- Tagged unions (only `outputConfig.textFormat`) → `@discriminatorName("type")` + `@caseName(...)`.
- Tool input/output JSON shape comes from `zio.http.endpoint.openapi.JsonSchema.fromZSchema(schema, Inline)`.
- Tool-use input arrives from the model as JSON object — internally held as `DynamicValue` annotated `@directDynamicMapping` (so the codec treats it as natural JSON, not as the structured `DynamicValue` ADT). `DynamicValue` is **never** exposed in any public signature.

## Examples

### Text

```scala
import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.*
import zio.*
import zio.http.Client

object Hello extends ZIOAppDefault:
  def run =
    BedrockConverse
      .converse(ConverseRequest("Hello!"))
      .text
      .debug
      .provide(Client.default, BedrockConverse.live)
```

### Tool calls

```scala
case class WeatherInput(city: String) derives Schema
case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema

def get_weather(in: WeatherInput): WeatherOutput =
  WeatherOutput(temperatureF = 64, conditions = "foggy")

val weatherTool = get_weather.asTool(
  "Get the current weather (F + conditions) for a US city."
)
// Tool[WeatherInput, WeatherOutput, Any, Nothing]
// name = ToolName("get_weather")   ← derived from the method name

BedrockConverse.converse(
  ConverseRequest("What is the weather in San Francisco?"),
  weatherTool,
).text
// : ZIO[BedrockConverse, ConverseError, String]
```

Multiple tools — `R` intersects, `E` unions, automatically:

```scala
case class PopulationInput(city: String, country: String) derives Schema

def get_population(in: PopulationInput): ZIO[PopulationService, PopErr, Int] = …

val popTool = get_population.asTool("Lookup population.")

BedrockConverse.converse(ConverseRequest("…"), weatherTool, popTool).text
// : ZIO[BedrockConverse & PopulationService,
//       ConverseError | Nothing | PopErr,
//       String]
```

If you want metadata too:

```scala
val resp: ZIO[BedrockConverse, ConverseError, ConverseResponse[ConverseOutput]] =
  BedrockConverse.converse(ConverseRequest("Hello")).asResponse
// resp.output.message.role     ← Role.Assistant
// resp.output.message.content  ← List[ContentBlock]
// resp.stopReason / resp.usage / resp.metrics
```

### Structured output

```scala
case class Forecast(city: String, summary: String) derives Schema

BedrockConverse
  .converse(ConverseRequest("One-sentence Seattle forecast. Fill in city and summary."))
  .as[Forecast]
// : ZIO[BedrockConverse, ConverseError, Forecast]
```

`.asResponse[Forecast]` returns the full envelope:

```scala
val resp: ZIO[BedrockConverse, ConverseError, ConverseResponse[Forecast]] =
  BedrockConverse.converse(ConverseRequest("…")).asResponse[Forecast]
// resp.output      ← Forecast(...)
// resp.stopReason / resp.usage / resp.metrics
```

Tools work with structured output too:

```scala
BedrockConverse.converse(
  ConverseRequest("Use the weather tool, then summarise."),
  weatherTool,
).as[Forecast]
```

### Mock tests

```scala
import BedrockConverseMock.MockBehavior.*

val program: ZIO[BedrockConverse, ConverseError, String] =
  BedrockConverse.converse(ConverseRequest("Weather in SF?"), weatherTool).text

val test = program.provideLayer(BedrockConverseMock(
  CallTool(ToolName("get_weather"), WeatherInput("San Francisco")),
  Reply("It is 64F and foggy in San Francisco."),
))
```

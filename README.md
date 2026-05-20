# zio-bedrock-converse

[![javadocs.dev](https://www.javadocs.dev/com.jamesward/zio-bedrock-converse_3/badge.svg)](https://www.javadocs.dev/com.jamesward/zio-bedrock-converse_3/latest)

A Scala 3 / ZIO library for Amazon Bedrock's [Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html),
authenticated with Bedrock [API keys](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys.html) (bearer tokens — no SigV4).

- Typed end-to-end. No `DynamicValue` in the public API.
- Tool input / output / error JSON Schemas derive from `zio.schema.Schema`.
- Built on ZIO HTTP's `Client`.
- Three APIs:
  - **`Bedrock.converse`** — low-level. You drive the wire, including
    manual tool dispatch.
  - **`Bedrock.request`** — high-level single-turn. Bundle handlers in a
    NamedTuple, fold over the typed outcome. Tool errors flow through
    ZIO's error channel as a typed union.
  - **`Bedrock.loop`** — multi-turn agentic. Same handler NamedTuple,
    but the framework dispatches tools and feeds results back
    automatically. Terminals (`.text`, `.as[T]`) mirror `Bedrock.converse`.

## Install

```scala
libraryDependencies += "com.jamesward" %% "zio-bedrock-converse" % "<version>"
```

## Configure

`Bedrock.Client.live` reads three environment variables:

| Var                         | Required | Default     |
| --------------------------- | -------- | ----------- |
| `AWS_BEARER_TOKEN_BEDROCK`  | yes      | —           |
| `BEDROCK_MODEL_ID`          | yes      | —           |
| `AWS_REGION`                | no       | `us-east-1` |

For example:
1. [Create a Bedrock Bearer token](https://us-east-1.console.aws.amazon.com/bedrock/home?region=us-east-1#/api-keys/long-term/create)
2. Set the auth token: `export AWS_BEARER_TOKEN_BEDROCK=YOUR_TOKEN`
3. Set the model: `export BEDROCK_MODEL_ID=us.anthropic.claude-sonnet-4-5-20250929-v1:0`

Or construct the layer explicitly:

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*

Bedrock.Client.layer(
  ApiKey("…"),
  Region.UsWest2,
  ModelId("us.anthropic.claude-sonnet-4-5-20250929-v1:0"),
)
```

## Basic inference (`Bedrock.converse`)

Five terminals, one prompt each. Every example below assumes
`Client.default` and `Bedrock.Client.live` in scope.

```scala
case class Food(name: String, region: String) derives Schema

// Plain text.
Bedrock.converse("say hello").text

// Structured output: the model is told to produce JSON conforming to
// Schema[Food], and the text reply is decoded into a Food.
Bedrock.converse("Favorite food").as[Food]

// Full envelope: stopReason, usage, metrics, plus the assistant message.
Bedrock.converse("tell a one-line joke").asResponse

// Structured output + envelope.
Bedrock.converse("Worst food").asResponse[Food]

// Streaming: each emitted String is a text delta from the model.
Bedrock.converse("Write a poem about Scala").textStream
  .runForeach(Console.print(_).orDie)
```


`RequestConfig(prompt: String)` is a single-message convenience —
`Bedrock.converse("hi")` is just `Bedrock.converse(RequestConfig("hi"))`.
For full control over messages, system prompt, and inference config,
pass a `RequestConfig` directly.

A structured-output decode failure surfaces as
`Bedrock.Error.StructuredDecode(responseText, message)`.

## Defining tools

Tool handlers are bundled in a `NamedTuple`. The key becomes the tool
name advertised to the model; input/output/error types must have
`Schema` instances.

```scala
val tools = (
  // Effectful: ZIO[R, E, A]. Schema[E] is required so loop can wire-encode
  // failures back to the model.
  randomLetters = ToolHandler(
    (n: Int) => Random.nextIntBounded(26).replicateZIO(n)
      .map(_.map(i => ('a' + i).toChar).mkString),
    "generate n random letters",
  ),
  // Pure: I => A. No environment, no errors.
  reverse = ToolHandler.fromPure(
    (s: String) => s.reverse,
    "reverse a string",
  ),
)
```

The same `tools` NamedTuple drives both `Bedrock.loop` and
`Bedrock.request`.

For input/output classes you can `derives Schema` and field
descriptions propagate to the JSON Schema sent to the model.

## Multi-turn agentic loop (`Bedrock.loop`)

The framework dispatches tools and feeds results back to the model
until the model produces a final reply (or `maxIterations` is hit,
default 10). Handler errors are encoded via `Schema[E]` and fed back
to the model as `tool_result.status = Error` — they don't surface in
the ZIO error channel.

```scala
// Final text reply.
Bedrock.loop("generate 8 random letters", tools).text

// Final reply parsed as Food.
Bedrock.loop("generate 8 random letters; suggest a similar food name", tools)
  .as[Food]

// Multiple tool calls per loop, streaming the final reply's text.
Bedrock.loop("display 8 random letters and its reverse", tools)
  .textStream.runForeach(Console.print(_).orDie)
```

Configuration:

```scala
Bedrock.loop("…", tools)
  .system("You are concise.")
  .inferenceConfig(InferenceConfig(maxTokens = 500))
  .maxIterations(5)
  .text
```

Debug logging is built in at `ZIO.logDebug` level — set your ZIO log
level to `DEBUG` to see each iteration's tool dispatches and replies.

## Single-turn with tools (`Bedrock.request`)

Same `tools` NamedTuple, but the framework runs the tool exactly once
and hands the typed output to a `.fold` whose keys mirror the tool
keys. Use this when you want the model's tool call to be the answer
(no follow-up turn).

```scala
Bedrock.request("generate a 16 character random string", tools).fold[Unit]:
  (
    randomLetters = s => println(s"tool result: $s"),
    reverse       = s => println("should not happen"),
  )
```

The `.fold` is exhaustive: every key in `tools` needs a function from
its tool's typed output to a unified result type. Tool failures
propagate through the ZIO error channel as a typed union of every
handler's `E`.

To let the model reply with text or structured JSON instead of
dispatching a tool, register a `ModelResponseTool` in the NamedTuple:

```scala
case class Forecast(city: String, summary: String) derives Schema

val tools = (
  randomLetters = ToolHandler(…),
  reverse       = ToolHandler.fromPure(…),
  reply         = ModelResponseTool[Forecast]("Summarise the answer."),
)

Bedrock.request("…", tools).fold[Forecast]:
  (
    randomLetters = s => Forecast("?", s),
    reverse       = s => Forecast("?", s),
    reply         = f => f,
  )
```

## Low-level tools (`Bedrock.converse`)

When you want full control — drive the round-trip yourself, decide on
the fly whether to send the result back, build custom message
sequences. This is the same wire shape `Bedrock.request` uses
internally.

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client
import zio.schema.{Schema, derived}

case class WeatherInput(city: String) derives Schema
case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema

def get_weather(in: WeatherInput): WeatherOutput =
  WeatherOutput(temperatureF = 64, conditions = "foggy")

object Weather extends ZIOAppDefault:
  def run =
    val tool: Tool[WeatherInput] =
      get_weather.asTool("Get the current weather (F + conditions) for a US city.")
    // tool.name == ToolName("get_weather")  (derived from the function reference)

    val initial = RequestConfig(
      messages   = List(Message.user("What's the weather in San Francisco?")),
      toolConfig = ToolConfig(tools = List(tool)),
    )

    val program = Bedrock.converse(initial).asResponse.flatMap: first =>
      val toolCall = first.output.message.content.collectFirst:
        case ContentBlock.ToolUse(id, name, input) => (id, name, input)

      toolCall match
        case Some((toolUseId, _, input)) =>
          val answer = input.as[WeatherInput].fold(
            err => WeatherOutput(0, s"input decode failed: $err"),
            get_weather,
          )
          val followup = initial.copy(
            messages = initial.messages
              :+ first.output.message
              :+ Message(Role.User, List(ContentBlock.ToolResult(
                toolUseId = toolUseId,
                content   = List(ToolResultBlock.json(answer)),
              ))),
          )
          Bedrock.converse(followup).text
        case None =>
          ZIO.succeed(first.output.text)

    program.debug("answer").provide(Client.default, Bedrock.Client.live)
```

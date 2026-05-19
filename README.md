# zio-bedrock-converse

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

## Example: plain text

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client

object Hello extends ZIOAppDefault:
  def run =
    Bedrock.converse(RequestConfig(
      messages        = List(Message.user("Give me a one-sentence summary of the Converse API.")),
      system          = "You are concise.",
      inferenceConfig = InferenceConfig(maxTokens = 200, temperature = 0.3),
    ))
    .text
    .debug("answer")
    .provide(Client.default, Bedrock.Client.live)
```

`RequestConfig.apply(prompt: String)` is a single-message convenience —
`Bedrock.converse("hi")` is just `Bedrock.converse(RequestConfig("hi"))`.

## Example: high-level tools (`Bedrock.request`)

The high-level API takes a `NamedTuple` of registered tools — handlers
that the framework dispatches automatically, plus an optional
`ModelResponseTool` if you want the model to be able to reply with text
or structured JSON. The terminal `.fold` is exhaustive: every key in the
NamedTuple gets a function from its tool's typed output to a unified
result `R`.

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client
import zio.schema.{Schema, derived}

case class WeatherInput(city: String) derives Schema
case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema
case class PopulationInput(city: String) derives Schema
case class PopErr(message: String) derives Schema             // typed errors need Schema
case class Forecast(city: String, summary: String) derives Schema

trait PopulationService:
  def populationOf(city: String): IO[PopErr, Int]

def get_weather(in: WeatherInput): WeatherOutput =
  WeatherOutput(64, "foggy")

def get_population(in: PopulationInput): ZIO[PopulationService, PopErr, Int] =
  ZIO.serviceWithZIO[PopulationService](_.populationOf(in.city))

object Tools extends ZIOAppDefault:
  def run =
    val tools = (
      weather    = get_weather.asHandler("Get the current weather for a US city."),
      population = get_population.asHandler("Get the population of a city."),
      reply      = ModelResponseTool[Forecast]("Summarise the answer as a Forecast."),
    )

    val program: ZIO[
      Bedrock.Client & PopulationService,
      Bedrock.Error | PopErr,
      Bedrock.Result[Forecast],
    ] =
      Bedrock.request("Weather and population of Denver?", tools).fold[Forecast]:
        (weather    = (w: WeatherOutput) => Forecast("Denver", s"${w.temperatureF}°F"),
         population = (p: Int)           => Forecast("Denver", s"$p people"),
         reply      = (f: Forecast)      => f)

    program
      .map(_.output)
      .debug("forecast")
      .provide(Client.default, Bedrock.Client.live, populationServiceLayer)
```

## Example: multi-turn agentic loop (`Bedrock.loop`)

Same handler NamedTuple as `Bedrock.request`, but the framework drives
the loop: dispatches tools, feeds results back to the model, repeats
until the model produces a final reply or `maxIterations` is hit.

Handler errors are encoded via `Schema[E]` and fed back to the model as
`tool_result.status = Error` — the model can self-correct. They never
surface in the ZIO error channel.

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client
import zio.schema.{Schema, derived}

case class WeatherInput(city: String) derives Schema
case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema
case class Forecast(city: String, summary: String) derives Schema

def get_weather(in: WeatherInput): WeatherOutput =
  WeatherOutput(64, "foggy")

object AgentLoop extends ZIOAppDefault:
  def run =
    val tools = (
      weather = ToolHandler.fromPure(get_weather, "Get the current weather for a US city."),
    )

    // Text reply
    Bedrock.loop("What is the weather in Denver?", tools)
      .text
      .debug("answer")
      .provide(Client.default, Bedrock.Client.live)

    // Or structured reply
    Bedrock.loop("Weather of Denver? Respond as a Forecast.", tools)
      .as[Forecast]
      .debug("forecast")
      .provide(Client.default, Bedrock.Client.live)
```

Configuration:

```scala
Bedrock.loop("…", tools)
  .system("You are concise.")
  .inferenceConfig(InferenceConfig(maxTokens = 500))
  .maxIterations(5)    // default is 10
  .text
```

Debug logging is built in at `ZIO.logDebug` level — set your ZIO log
level to `DEBUG` to see each iteration's tool dispatches and replies.

## Example: low-level tools (`Bedrock.converse`)

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

What the macros / typed accessors give you:
- `get_weather.asTool("…")` derives `ToolName("get_weather")` at compile
  time from the function reference and captures `Schema[WeatherInput]`.
- `input.as[WeatherInput]` decodes the model's JSON input through
  `Schema[WeatherInput]` — `DynamicValue` never enters the public API.
- `ToolResultBlock.json(answer)` encodes the typed answer through
  `Schema[WeatherOutput]`.

## Example: structured output (low-level)

Ask the model to produce JSON conforming to a `Schema[T]` and get a `T`
back, parsed for you.

```scala
import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client
import zio.schema.{Schema, derived}

case class Forecast(city: String, summary: String) derives Schema

object StructuredForecast extends ZIOAppDefault:
  def run =
    Bedrock
      .converse("Give a one-sentence weather forecast for Seattle. Fill in city and summary.")
      .as[Forecast]
      .debug("forecast")
      .provide(Client.default, Bedrock.Client.live)
```

What happens behind the scenes:
1. `Schema[Forecast]` is converted to a JSON Schema document.
2. The request's `outputConfig.textFormat = { type: "json_schema", … }` is set.
3. The model's text response is parsed back through `Schema[Forecast]` into a `Forecast`.

A decode failure surfaces as `Bedrock.Error.StructuredDecode(responseText, message)`.

`.asResponse[Forecast]` returns the same parsed `Forecast` along with the
full response envelope (`stopReason`, `usage`, `metrics`). The high-level
equivalent is registering `ModelResponseTool[Forecast]` with `Bedrock.request`.

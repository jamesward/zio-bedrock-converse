# zio-bedrock-converse

A Scala 3 / ZIO library for Amazon Bedrock's [Converse API](https://docs.aws.amazon.com/bedrock/latest/userguide/conversation-inference.html),
authenticated with Bedrock [API keys](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys.html) (bearer tokens — no SigV4).

- Typed end-to-end. No `DynamicValue` in the public API.
- Tool inputs and structured outputs derive their JSON Schema from `zio.schema.Schema`.
- Built on ZIO HTTP's `Client`.

## Install

```scala
libraryDependencies += "com.jamesward" %% "zio-bedrock-converse" % "<version>"
```

## Configure

`BedrockConverse.live` reads three environment variables:

| Var                         | Required | Default     |
| --------------------------- | -------- | ----------- |
| `AWS_BEARER_TOKEN_BEDROCK`  | yes      | —           |
| `BEDROCK_MODEL_ID`          | yes      | —           |
| `AWS_REGION`                | no       | `us-east-1` |

Or construct the service explicitly:

```scala
BedrockConverse.layer(
  ApiKey("…"),
  Region.UsWest2,
  ModelId("us.anthropic.claude-sonnet-4-5-20250929-v1:0"),
)
```

## Example: plain text

```scala
import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.*
import zio.*
import zio.http.Client

object Hello extends ZIOAppDefault:
  def run =
    val req = ConverseRequest.text(
      prompt          = "Give me a one-sentence summary of the Converse API.",
      system          = "You are concise.",
      inferenceConfig = InferenceConfig(maxTokens = 200, temperature = 0.3),
    )
    BedrockConverse
      .converse[Unit](req)
      .map(_.output.message.content.collect { case ContentBlock.Text(t) => t }.mkString)
      .debug("answer")
      .provide(Client.default, BedrockConverse.live)
```

`ConverseRequest.text` takes raw `String`s for `system` (defaults to `null`, omitted from the wire) and an `InferenceConfig` whose own fields drop out when unset. The request's type parameter is `Unit` because no tools are configured.

## Example: tools

Define a single ADT covering every tool's input *and* every tool's result payload. The `@noDiscriminator` annotation makes each variant encode as just its fields (no case tag), matching the wire shape Bedrock expects.

```scala
import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.*
import zio.*
import zio.http.Client
import zio.schema.Schema
import zio.schema.annotation.noDiscriminator
import zio.schema.derived

/** Standalone case class describing the tool's input shape — used by
  * `Tool.derive` to generate the JSON Schema the model sees. */
case class WeatherInput(location: String) derives Schema

/** The conversation's JSON vocabulary. `Q` matches the shape of
  * `WeatherInput`; `A` is what we send back as the tool result. */
@noDiscriminator
enum WeatherTool derives Schema:
  case Q(location: String)
  case A(temperatureF: Int, conditions: String)

object Weather extends ZIOAppDefault:
  def run =
    // The model is told about `get_weather`. The input JSON-Schema is
    // generated from `Schema[WeatherInput]`.
    val tool = Tool.derive[WeatherInput](
      name        = ToolName("get_weather"),
      description = "Get the current weather for a US city (F + conditions).",
      strict      = true,
    )

    val initial = ConverseRequest[WeatherTool](
      messages   = List(Message(Role.User, List(ContentBlock.Text(
        "What's the weather in San Francisco?"
      )))),
      toolConfig = ToolConfig(tools = List(tool), toolChoice = ToolChoice.auto),
    )

    val program = for
      first <- BedrockConverse.converse[WeatherTool](initial)

      // The assistant turn either holds a tool call we need to dispatch…
      toolUse = first.output.message.content.collectFirst:
        case ContentBlock.ToolUse(t) => t

      finalAnswer <- toolUse match
        case Some(tu) =>
          // …in which case we run the tool, send the typed result back,
          // and get the next assistant turn.
          val answer: WeatherTool = tu.input match
            case WeatherTool.Q(loc) =>
              // Replace with a real call to a weather service.
              WeatherTool.A(temperatureF = 64, conditions = "foggy")
            case _ => WeatherTool.A(0, "unknown")

          val followup = initial.copy(
            messages = initial.messages
              :+ first.output.message
              :+ Message(Role.User, List(ContentBlock.ToolResult(
                ToolResultContent(
                  toolUseId = tu.toolUseId,
                  content   = List(ToolResultBlock.json(answer)),
                ),
              ))),
          )
          BedrockConverse.converse[WeatherTool](followup)
            .map(_.output.message.content.collect { case ContentBlock.Text(t) => t }.mkString)

        case None =>
          // Model answered without using a tool.
          ZIO.succeed(first.output.message.content.collect { case ContentBlock.Text(t) => t }.mkString)
    yield finalAnswer

    program.debug("answer").provide(Client.default, BedrockConverse.live)
```

The two type parameters worth knowing:
- `ConverseRequest[WeatherTool]` — the conversation's typed vocabulary.
- `Tool.derive[WeatherInput]` — captures `Schema[WeatherInput]` at construction; the wire codec turns it into JSON Schema at request time.

## Example: structured output

Ask the model to produce JSON conforming to a `Schema[T]` and get a `T` back, parsed for you.

```scala
import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.*
import zio.*
import zio.http.Client
import zio.schema.Schema
import zio.schema.derived

case class Forecast(city: String, summary: String) derives Schema

object StructuredForecast extends ZIOAppDefault:
  def run =
    val req = ConverseRequest.text(
      "Give a one-sentence weather forecast for Seattle. Fill in city and summary."
    )
    BedrockConverse
      .converseStructured[Unit, Forecast](req)
      .debug("forecast")
      .provide(Client.default, BedrockConverse.live)
```

What happens behind the scenes:
1. `Schema[Forecast]` is converted to a JSON Schema document.
2. The request's `outputConfig.textFormat = { type: "json_schema", … }` is set.
3. The model's text response is parsed back through `Schema[Forecast]` into a `Forecast`.

A decode failure surfaces as `ConverseError.StructuredDecode(responseText, message)`.

## Errors

```scala
sealed trait ConverseError extends Throwable
object ConverseError:
  case class Validation         (message: String)        extends ConverseError  // 400
  case class AccessDenied       (message: String)        extends ConverseError  // 403
  case class ResourceNotFound   (message: String)        extends ConverseError  // 404
  case class ModelTimeout       (message: String)        extends ConverseError  // 408
  case class ModelErr           (message: String, …)     extends ConverseError  // 424
  case class Throttling         (message: String)        extends ConverseError  // 429
  case class InternalServer     (message: String)        extends ConverseError  // 500
  case class ServiceUnavailable (message: String)        extends ConverseError  // 503
  case class Unexpected         (status: Status, …)      extends ConverseError
  case class Transport          (cause: Throwable)       extends ConverseError
  case class MissingApiKey      ()                       extends ConverseError
  case class MissingModelId     ()                       extends ConverseError
  case class StructuredDecode   (responseText: String, …) extends ConverseError

  extension [R, A](zio: ZIO[R, ConverseError, A])
    /** Retries Throttling / InternalServer / ServiceUnavailable / ModelTimeout. */
    def retryOnRetryable: ZIO[R, ConverseError, A]
```

## License

MIT

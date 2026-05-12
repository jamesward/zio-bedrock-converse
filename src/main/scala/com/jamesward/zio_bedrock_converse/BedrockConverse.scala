package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import com.jamesward.zio_bedrock_converse.internal.{Codecs, Http, Tools, Wire}
import zio.*
import zio.direct.*
import zio.http.{Client, Status}
import zio.schema.annotation.caseName
import zio.schema.{DynamicValue, Schema, derived}

/**
 * Service for the Amazon Bedrock Converse API.
 *
 * `converse(req, tools…)` returns a [[BedrockConverse.ConverseInvoker]]
 * — call `.text`, `.asResponse`, `.as[T]`, or `.asResponse[T]` on it
 * to choose the output shape. Each terminal method drives the same
 * multi-turn tool-dispatch loop.
 *
 * The target model is configured at service construction time
 * ([[BedrockConverse.live]] / [[BedrockConverse.layer]]).
 */
class BedrockConverse private[zio_bedrock_converse] (
  private[zio_bedrock_converse] val send: BedrockConverse.Sender,
  val modelId:                            BedrockConverse.ModelId,
):
  import BedrockConverse.*

  /** Build an invoker for `req` with the supplied tools. `R` is intersected
    * and `E` is unioned across every tool via the variance on
    * `Tool[-I, +O, -R, +E]`. */
  def converse[R, E](req: ConverseRequest, tools: Tool[?, ?, R, E]*):
      ConverseInvoker[R, E] =
    new ConverseInvoker[R, E](ZIO.succeed(send), req, tools.toIndexedSeq)

object BedrockConverse:

  // ---------- Internal alias for the wire dispatcher ----------

  private[zio_bedrock_converse] type Sender =
    Wire.ConverseRequest[DynamicValue] =>
      IO[ConverseError, Wire.ConverseResponse[DynamicValue]]

  // ---------- Opaque domain types ----------

  // For each opaque-type wrapping `String` we expose `.unwrap` rather than
  // the more obvious `.value`, because `java.lang.String` already has a
  // private `value` field; INSIDE this scope (where the opaque types are
  // transparent) `.value` resolves to that private field and won't compile.

  opaque type ModelId = String
  object ModelId:
    def apply(s: String): ModelId = s
    extension (m: ModelId) def unwrap: String = m

  opaque type ApiKey = String
  object ApiKey:
    def apply(s: String): ApiKey = s
    extension (k: ApiKey) def unwrap: String = k

  opaque type ToolName = String
  object ToolName:
    def apply(s: String): ToolName = s
    extension (t: ToolName) def unwrap: String = t
    given Schema[ToolName] = Schema.primitive[String].transform(ToolName(_), identity)

  opaque type ToolUseId = String
  object ToolUseId:
    def apply(s: String): ToolUseId = s
    extension (t: ToolUseId) def unwrap: String = t
    given Schema[ToolUseId] = Schema.primitive[String].transform(ToolUseId(_), identity)

  /** All AWS commercial regions, plus GovCloud and China partitions. */
  enum Region(val code: String):
    case UsEast1       extends Region("us-east-1")
    case UsEast2       extends Region("us-east-2")
    case UsWest1       extends Region("us-west-1")
    case UsWest2       extends Region("us-west-2")
    case CaCentral1    extends Region("ca-central-1")
    case CaWest1       extends Region("ca-west-1")
    case MxCentral1    extends Region("mx-central-1")
    case SaEast1       extends Region("sa-east-1")
    case EuCentral1    extends Region("eu-central-1")
    case EuCentral2    extends Region("eu-central-2")
    case EuWest1       extends Region("eu-west-1")
    case EuWest2       extends Region("eu-west-2")
    case EuWest3       extends Region("eu-west-3")
    case EuNorth1      extends Region("eu-north-1")
    case EuSouth1      extends Region("eu-south-1")
    case EuSouth2      extends Region("eu-south-2")
    case AfSouth1      extends Region("af-south-1")
    case MeSouth1      extends Region("me-south-1")
    case MeCentral1    extends Region("me-central-1")
    case IlCentral1    extends Region("il-central-1")
    case ApEast1       extends Region("ap-east-1")
    case ApEast2       extends Region("ap-east-2")
    case ApSouth1      extends Region("ap-south-1")
    case ApSouth2      extends Region("ap-south-2")
    case ApNortheast1  extends Region("ap-northeast-1")
    case ApNortheast2  extends Region("ap-northeast-2")
    case ApNortheast3  extends Region("ap-northeast-3")
    case ApSoutheast1  extends Region("ap-southeast-1")
    case ApSoutheast2  extends Region("ap-southeast-2")
    case ApSoutheast3  extends Region("ap-southeast-3")
    case ApSoutheast4  extends Region("ap-southeast-4")
    case ApSoutheast5  extends Region("ap-southeast-5")
    case ApSoutheast7  extends Region("ap-southeast-7")
    case UsGovEast1    extends Region("us-gov-east-1")
    case UsGovWest1    extends Region("us-gov-west-1")
    case CnNorth1      extends Region("cn-north-1")
    case CnNorthwest1  extends Region("cn-northwest-1")

  object Region:
    def fromCode(code: String): Option[Region] = values.find(_.code == code)

  // ---------- Strict equality ----------

  given CanEqual[ModelId, ModelId]     = CanEqual.derived
  given CanEqual[Region, Region]       = CanEqual.derived
  given CanEqual[ApiKey, ApiKey]       = CanEqual.derived
  given CanEqual[ToolName, ToolName]   = CanEqual.derived
  given CanEqual[ToolUseId, ToolUseId] = CanEqual.derived
  given CanEqual[Status, Status]       = CanEqual.derived

  // ---------- Shared public enums ----------

  enum Role derives Schema:
    @caseName("user")      case User
    @caseName("assistant") case Assistant

  given CanEqual[Role, Role] = CanEqual.derived

  enum StopReason derives Schema:
    @caseName("end_turn")                      case EndTurn
    @caseName("tool_use")                      case ToolUse
    @caseName("max_tokens")                    case MaxTokens
    @caseName("stop_sequence")                 case StopSequence
    @caseName("guardrail_intervened")          case GuardrailIntervened
    @caseName("content_filtered")              case ContentFiltered
    @caseName("malformed_model_output")        case MalformedModelOutput
    @caseName("malformed_tool_use")            case MalformedToolUse
    @caseName("model_context_window_exceeded") case ModelContextWindowExceeded

  given CanEqual[StopReason, StopReason] = CanEqual.derived

  // ---------- Inference / metrics ----------

  case class InferenceConfig(
    maxTokens:     Int    | Null = null,
    temperature:   Double | Null = null,
    topP:          Double | Null = null,
    stopSequences: List[String]  = Nil,
  ) derives Schema

  case class TokenUsage(
    inputTokens:           Int,
    outputTokens:          Int,
    totalTokens:           Int,
    cacheReadInputTokens:  Int | Null = null,
    cacheWriteInputTokens: Int | Null = null,
  ) derives Schema

  case class ConverseMetrics(latencyMs: Long) derives Schema

  // ---------- Public Message / Request / Response ----------

  /** A content block in a [[Message]]. Text-only for now; multimodal
    * shapes will be added later as additional cases. */
  enum ContentBlock:
    case Text(text: String)

  /** A user or assistant message in a conversation. Mirrors AWS's wire
    * `{"role": …, "content": [...]}` shape. */
  case class Message(role: Role, content: List[ContentBlock]):
    /** Joined text from every `Text` content block. */
    def text: String = content.collect { case ContentBlock.Text(t) => t }.mkString

  object Message:
    def user(text: String):      Message = Message(Role.User,      List(ContentBlock.Text(text)))
    def assistant(text: String): Message = Message(Role.Assistant, List(ContentBlock.Text(text)))

  /** A request the user constructs and hands to `converse`. Tools are
    * passed alongside the request to `converse(req, tools…)` — the
    * request itself doesn't carry them. */
  case class ConverseRequest(
    messages:        List[Message],
    system:          String | Null              = null,
    inferenceConfig: InferenceConfig | Null     = null,
  )

  object ConverseRequest:
    def apply(prompt: String): ConverseRequest =
      ConverseRequest(messages = List(Message.user(prompt)))

  /** The default `output` shape used by `.asResponse` (when the caller
    * isn't asking for structured output). Mirrors AWS's wire
    * `output.message` envelope. */
  case class ConverseOutput(message: Message):
    /** Joined text from the assistant message's text content blocks. */
    def text: String = message.text

  /** Full response from a converse call, parameterised over the `output`
    * type. `output` is a `ConverseOutput` for `.asResponse`, the
    * structured-output type `T` for `.asResponse[T]`. */
  case class ConverseResponse[+T](
    output:     T,
    stopReason: StopReason,
    usage:      TokenUsage,
    metrics:    ConverseMetrics,
  )

  // ---------- Tool ----------
  // todo: redesign this
  //   tool enum: ToolPure, ToolZIO

  /** A tool the model can call.
    *
    * `[-I, +O, -R, +E]` lets the compiler intersect each tool's `R` and
    * union each tool's `E` automatically when several are passed to
    * `converse(req)(t1, t2, …)`. `Schema[I]` and `Schema[O]` are captured
    * at construction by `Tool.fromFunction`; the wire codec uses them at
    * dispatch time and they never leak into the public type. */
  final class Tool[-I, +O, -R, +E] private[zio_bedrock_converse] (
    val name:        ToolName,
    val description: String,
    private[zio_bedrock_converse] val inputSchema:  Schema[?],
    private[zio_bedrock_converse] val outputSchema: Schema[?],
    private[zio_bedrock_converse] val handler:      I => ZIO[R, E, Any],
  )

  object Tool:
    /** Build a `Tool` with an explicit name from an effectful function.
      * Public callers usually go through the `.asTool` extension methods. */
    def makeZIO[I: Schema, O: Schema, R, E](
      name:        ToolName,
      description: String,
      f:           I => ZIO[R, E, O],
    ): Tool[I, O, R, E] =
      new Tool[I, O, R, E](
        name        = name,
        description = description,
        inputSchema  = summon[Schema[I]],
        outputSchema = summon[Schema[O]],
        handler      = (any: Any) => f(any.asInstanceOf[I]).asInstanceOf[ZIO[R, E, Any]],
      )

    /** Build a `Tool` with an explicit name from a pure function. */
    def makePure[I: Schema, O: Schema](
      name:        ToolName,
      description: String,
      f:           I => O,
    ): Tool[I, O, Any, Nothing] =
      makeZIO(name, description, (i: I) => ZIO.succeed(f(i)))

  /** Pure-function `.asTool` extension. The tool name is derived from
    * the function reference at compile time — pass a method (`weather`)
    * or eta-expansion (`weather _`). For effectful tools, the
    * `I => ZIO[R, E, O]` overload below is selected automatically. */
  extension [I, O](inline f: I => O)
    inline def asTool(description: String)(using inline si: Schema[I], inline so: Schema[O]):
        Tool[I, O, Any, Nothing] =
      ${ com.jamesward.zio_bedrock_converse.internal.ToolMacros.asToolPure[I, O]('f, 'description, 'si, 'so) }

  /** Effectful-function `.asTool` extension. */
  extension [I, O, R, E](inline f: I => ZIO[R, E, O])
    inline def asTool(description: String)(using inline si: Schema[I], inline so: Schema[O]):
        Tool[I, O, R, E] =
      ${ com.jamesward.zio_bedrock_converse.internal.ToolMacros.asToolZIO[I, O, R, E]('f, 'description, 'si, 'so) }

  // ---------- Errors ----------

  sealed trait ConverseError extends Throwable

  object ConverseError:
    final case class Validation         (message: String)                                       extends ConverseError
    final case class AccessDenied       (message: String)                                       extends ConverseError
    final case class ResourceNotFound   (message: String)                                       extends ConverseError
    final case class ModelTimeout       (message: String)                                       extends ConverseError
    final case class ModelErr           (message: String, originalStatusCode: Int | Null)        extends ConverseError
    final case class Throttling         (message: String)                                       extends ConverseError
    final case class InternalServer     (message: String)                                       extends ConverseError
    final case class ServiceUnavailable (message: String)                                       extends ConverseError
    final case class Unexpected         (status: Status, body: String)                          extends ConverseError
    final case class Transport          (cause: Throwable)                                      extends ConverseError
    final case class MissingApiKey()                                                            extends ConverseError
    final case class MissingModelId()                                                           extends ConverseError
    final case class StructuredDecode   (responseText: String, message: String)                 extends ConverseError
    /** Model invoked a tool name not registered with this `converse` call. */
    final case class UnknownTool        (name: ToolName)                                        extends ConverseError
    /** Tool's input JSON didn't decode into the tool's declared input type. */
    final case class InvalidToolInput   (name: ToolName, message: String)                       extends ConverseError

    private[zio_bedrock_converse] def fromStatus(status: Status, body: String): ConverseError =
      status.code match
        case 400 => Validation(body)
        case 403 => AccessDenied(body)
        case 404 => ResourceNotFound(body)
        case 408 => ModelTimeout(body)
        case 424 => ModelErr(body, null)
        case 429 => Throttling(body)
        case 500 => InternalServer(body)
        case 503 => ServiceUnavailable(body)
        case _   => Unexpected(status, body)

    extension [R, A](zio: ZIO[R, ConverseError, A])
      def retryOnRetryable: ZIO[R, ConverseError, A] =
        zio.retry(
          Schedule.recurWhile[ConverseError]:
            case _: (Throttling | InternalServer | ServiceUnavailable | ModelTimeout) => true
            case _                                                                    => false
          && Schedule.exponential(500.millis)
          && Schedule.recurs(2)
        )

  // ---------- Layers ----------

  /** Build a `BedrockConverse` from an API key, region, and target model. */
  def layer(apiKey: ApiKey, region: Region, modelId: ModelId): ZLayer[Client, Nothing, BedrockConverse] =
    ZLayer.fromZIO:
      ZIO.serviceWith[Client](Http.buildService(apiKey, region, modelId, _))

  /** Reads `AWS_BEARER_TOKEN_BEDROCK`, `BEDROCK_MODEL_ID` (both required),
    * and `AWS_REGION` (defaults to `us-east-1`). */
  val live: ZLayer[Client, ConverseError, BedrockConverse] =
    ZLayer.fromZIO:
      defer:
        val apiKey =
          ZIO.systemWith(_.env("AWS_BEARER_TOKEN_BEDROCK"))
            .orDie
            .someOrFail(ConverseError.MissingApiKey()).run
        val modelId =
          ZIO.systemWith(_.env("BEDROCK_MODEL_ID"))
            .orDie
            .someOrFail(ConverseError.MissingModelId()).run
        val regionCode =
          ZIO.systemWith(_.env("AWS_REGION")).orDie.map(_.getOrElse(Region.UsEast1.code)).run
        val region = Region.fromCode(regionCode).getOrElse(Region.UsEast1)
        val client = ZIO.serviceWith[Client](identity).run
        Http.buildService(ApiKey(apiKey), region, ModelId(modelId), client)

  // (`BedrockConverseMock` lives in `src/test` — see test sources.)

  // ---------- ConverseInvoker ----------

  /** Builder produced by `converse(req, tools…)`. Choose the output shape
    * with one of the terminal methods — each drives the same multi-turn
    * tool-dispatch loop, the difference is what gets returned. */
  final class ConverseInvoker[R, E] private[zio_bedrock_converse] (
    resolve: ZIO[R, Nothing, Sender],
    req:     ConverseRequest,
    tools:   IndexedSeq[Tool[?, ?, R, E]],
  ):
    /** Final assistant text, joined across the model's last content blocks. */
    def text: ZIO[R, ConverseError | E, String] =
      asResponse.map(_.output.text)

    /** Full response, with the assistant turn in `output.message`. */
    def asResponse: ZIO[R, ConverseError | E, ConverseResponse[ConverseOutput]] =
      resolve.flatMap(send => Tools.runLoop[R, E](send, req, tools, None)).map: wire =>
        val publicContent = wire.output.message.content.collect:
          case Wire.ContentBlock.Text(t) => ContentBlock.Text(t)
        ConverseResponse(
          output     = ConverseOutput(Message(wire.output.message.role, publicContent)),
          stopReason = wire.stopReason,
          usage      = wire.usage,
          metrics    = wire.metrics,
        )

    /** Structured output: the model is told to produce JSON matching
      * `Schema[T]`, and the final text block is decoded into a `T`. */
    def as[T: Schema]: ZIO[R, ConverseError | E, T] =
      asResponse[T].map(_.output)

    /** Structured output with the full response envelope. */
    def asResponse[T: Schema]: ZIO[R, ConverseError | E, ConverseResponse[T]] =
      val outputJsonSchema = zio.http.endpoint.openapi.JsonSchema.fromZSchema(
        summon[Schema[T]],
        zio.http.endpoint.openapi.JsonSchema.SchemaRef(
          zio.http.endpoint.openapi.JsonSchema.SchemaSpec.JsonSchema,
          zio.http.endpoint.openapi.JsonSchema.SchemaStyle.Inline,
        ),
      ).toJson
      val outCfg = Wire.OutputConfig(Wire.TextFormat.JsonSchema(
        Wire.JsonSchemaStructure(Wire.JsonSchemaSpec(
          schema = outputJsonSchema,
          name   = "structured_output",
        )),
      ))
      val codec = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[T](Codecs.codecConfig)
      resolve.flatMap(send => Tools.runLoop[R, E](send, req, tools, Some(outCfg))).flatMap: wire =>
        val text = wire.output.message.content.collectFirst:
          case Wire.ContentBlock.Text(t) => t
        text match
          case None =>
            ZIO.fail(ConverseError.StructuredDecode("", "no text block in response"))
          case Some(t) =>
            codec.decode(Chunk.fromArray(t.getBytes(java.nio.charset.StandardCharsets.UTF_8))) match
              case Right(value) => ZIO.succeed(ConverseResponse(value, wire.stopReason, wire.usage, wire.metrics))
              case Left(err)    => ZIO.fail(ConverseError.StructuredDecode(t, err.message))

  // ---------- Accessors ----------

  /** Top-level accessor: returns an invoker that requires `BedrockConverse`
    * in its environment. */
  def converse[R, E](req: ConverseRequest, tools: Tool[?, ?, R, E]*):
      ConverseInvoker[BedrockConverse & R, E] =
    new ConverseInvoker[BedrockConverse & R, E](
      ZIO.serviceWith[BedrockConverse](_.send),
      req,
      tools.toIndexedSeq.asInstanceOf[IndexedSeq[Tool[?, ?, BedrockConverse & R, E]]],
    )

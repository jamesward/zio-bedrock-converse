package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import com.jamesward.zio_bedrock_converse.internal.{Codecs, Http, Tools, Wire}
import zio.*
import zio.direct.*
import zio.http.{Client as HClient, Status}
import zio.schema.annotation.caseName
import zio.schema.{DynamicValue, Schema, derived}

/**
 * Top-level entrypoint for Amazon Bedrock's Converse API.
 *
 *   - [[Bedrock.Client]] — the service trait. Owns the HTTP wire (auth +
 *     region + model). Provided as a `ZLayer` and consumed from the env
 *     by `Bedrock.Request` terminals.
 *   - [[Bedrock.Request]] — pure-data builder returned by
 *     `Bedrock.converse(...)`. `.text`, `.asResponse`, `.as[T]`, and
 *     `.asResponse[T]` shape the output and require `Bedrock.Client` in
 *     the env.
 *
 * The multi-turn tool-dispatch loop is set aside for now and will return
 * as `Bedrock.loop` once its design is settled.
 */
object Bedrock:

  // ---------- Opaque domain types ----------

  // Each opaque type wrapping `String` exposes `.unwrap` rather than
  // `.value`, because `java.lang.String` already has a private `value`
  // field; INSIDE this scope the opaque type is transparent and `.value`
  // would resolve to that private field and fail to compile.

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

  case class Metrics(latencyMs: Long) derives Schema

  // ---------- Public Message / RequestConfig / Result ----------

  /** Typed accessor for tool input data exchanged with the model.
    * Hides the wire-level `DynamicValue` representation. Construct one
    * via [[ToolInput.from]]; decode one via [[as]]. */
  final class ToolInput private[zio_bedrock_converse] (
    private[zio_bedrock_converse] val raw: DynamicValue,
  ):
    /** Decode this input into the typed `I` via its `Schema`. */
    def as[I: Schema]: Either[String, I] = summon[Schema[I]].fromDynamic(raw)

  object ToolInput:
    /** Wrap a typed value (encoded via its `Schema`) as a `ToolInput`. */
    def from[A: Schema](value: A): ToolInput =
      new ToolInput(summon[Schema[A]].toDynamic(value))

  /** The server-reported status of a tool result block. */
  enum ToolResultStatus:
    case Success
    case Error

  given CanEqual[ToolResultStatus, ToolResultStatus] = CanEqual.derived

  /** A block within a [[ContentBlock.ToolResult]]. The wire shape also
    * supports `Image` / `Document` / `Video` payloads — those are
    * deferred from the public API for now. */
  enum ToolResultBlock:
    case Text(text: String)
    case Json(value: ToolInput)

  object ToolResultBlock:
    /** Build a JSON result block from a typed value via its `Schema`. */
    def json[A: Schema](value: A): ToolResultBlock = Json(ToolInput.from(value))

  /** A content block in a [[Message]].
    *
    *  - [[Text]]       — plain assistant text or user prompt.
    *  - [[ToolUse]]    — model's call into a registered tool. Surface
    *                     when the response's `stopReason` is `ToolUse`.
    *  - [[ToolResult]] — caller's reply with the tool's output. Send
    *                     this back in a follow-up `RequestConfig` so the
    *                     model can continue.
    *
    * The wire format additionally carries `Image` / `Document` / `Video`
    * / `CachePoint` / `ReasoningContent` blocks; those are filtered out
    * at the public boundary in this slice. */
  enum ContentBlock:
    case Text(text: String)
    case ToolUse(
      toolUseId: ToolUseId,
      name:      ToolName,
      input:     ToolInput,
    )
    case ToolResult(
      toolUseId: ToolUseId,
      content:   List[ToolResultBlock],
      status:    ToolResultStatus | Null = null,
    )

  /** A user or assistant message in a conversation. Mirrors AWS's wire
    * `{"role": …, "content": [...]}` shape. */
  case class Message(role: Role, content: List[ContentBlock]):
    /** Joined text from every `Text` content block. */
    def text: String = content.collect { case ContentBlock.Text(t) => t }.mkString

  object Message:
    def user(text: String):      Message = Message(Role.User,      List(ContentBlock.Text(text)))
    def assistant(text: String): Message = Message(Role.Assistant, List(ContentBlock.Text(text)))

  /** The user-facing request configuration handed to `Bedrock.converse`. */
  case class RequestConfig(
    messages:        List[Message],
    system:          String | Null          = null,
    inferenceConfig: InferenceConfig | Null = null,
    toolConfig:      ToolConfig | Null      = null,
  )

  object RequestConfig:
    def apply(prompt: String): RequestConfig =
      RequestConfig(messages = List(Message.user(prompt)))

  /** Default `output` shape used by `.asResponse`. Mirrors AWS's wire
    * `output.message` envelope. */
  case class Output(message: Message):
    /** Joined text from the assistant message's text content blocks. */
    def text: String = message.text

  /** Full response from a converse call, parameterised over the `output`
    * type. `output` is an `Output` for `.asResponse`, the
    * structured-output type `T` for `.asResponse[T]`. Wire/protocol
    * failures arrive as `Bedrock.Error` in the ZIO error channel. */
  case class Result[+T](
    output:     T,
    stopReason: StopReason,
    usage:      TokenUsage,
    metrics:    Metrics,
  )

  // ---------- Tool ----------

  /** A tool spec advertised to the model. Just the name, description,
    * and `Schema[I]` of the tool's input — no handler. The handler-bearing
    * companion (`ToolHandler`) will return when `Bedrock.loop` is
    * reintroduced; for now, callers run tool calls themselves and
    * construct `ContentBlock.ToolResult` with the result. */
  final class Tool[I] private[zio_bedrock_converse] (
    val name:        ToolName,
    val description: String,
    private[zio_bedrock_converse] val inputSchema: Schema[?],
  )

  object Tool:
    /** Build a `Tool` with an explicit name. Public callers usually go
      * through the `.asTool` extension method, which derives the tool
      * name from the function reference at compile time. */
    def apply[I: Schema](name: ToolName, description: String): Tool[I] =
      new Tool[I](name, description, summon[Schema[I]])

  /** `.asTool` extension. Works on any `I => A` (pure) or `I => ZIO[R, E, A]`
    * (effectful) — the function body is discarded; only the function's
    * name is captured at compile time via the macro. */
  extension [I: Schema, A](inline f: I => A)
    inline def asTool(description: String): Tool[I] =
      ${ com.jamesward.zio_bedrock_converse.internal.ToolMacros.asTool[I]('f, 'description) }

  /** Which tool the model is allowed to call. */
  enum ToolChoice:
    /** The model decides whether and which tool to call (default). */
    case Auto
    /** The model must call exactly one of the registered tools. */
    case Any
    /** The model must call this specific tool. */
    case Tool(name: ToolName)

  given CanEqual[ToolChoice, ToolChoice] = CanEqual.derived

  /** Tool configuration attached to a [[RequestConfig]]. */
  case class ToolConfig(
    tools:      List[Tool[?]],
    toolChoice: ToolChoice = ToolChoice.Auto,
  )

  // ────────────────────────────────────────────────────────────────────
  // High-level: ToolHandler + ModelResponseTool
  // ────────────────────────────────────────────────────────────────────

  /** A tool handler bound to a typed function. Used by the high-level
    * `Bedrock.request` flow.
    *
    * The handler-bearing companion of [[Tool]]: where `Tool[I]` is just
    * a spec advertised to the model, `ToolHandler[I, R, E, A]` carries
    * the function the framework runs when the model dispatches it.
    *
    * The tool's *name* is **not** stored here — it comes from the
    * `NamedTuple` key when handlers are bundled into `Bedrock.request`.
    *
    * Type parameters `[-I, -R, +E <: Matchable, +A <: Matchable]`:
    *  - `I` — input passed to the handler.
    *  - `R` — environment the handler needs.
    *  - `E` — error the handler can fail with (must have a `Schema`).
    *  - `A` — output the handler produces (must have a `Schema`). */
  final class ToolHandler[-I, -R, +E <: Matchable, +A <: Matchable] private[zio_bedrock_converse] (
    val description: String,
    private[zio_bedrock_converse] val inputSchema:  Schema[?],
    private[zio_bedrock_converse] val errorSchema:  Schema[?],
    private[zio_bedrock_converse] val outputSchema: Schema[?],
    private[zio_bedrock_converse] val handler:      I => ZIO[R, E, A],
  )

  object ToolHandler:

    /** Build a `ToolHandler` from an effectful function. `Schema[E]` is
      * required so future `Bedrock.loop` support can wire-encode the
      * error when feeding it back to the model. */
    def apply[I: Schema, R, E <: Matchable: Schema, A <: Matchable: Schema](
      f:           I => ZIO[R, E, A],
      description: String,
    ): ToolHandler[I, R, E, A] =
      new ToolHandler[I, R, E, A](
        description,
        summon[Schema[I]],
        summon[Schema[E]],
        summon[Schema[A]],
        f,
      )

    /** Build a `ToolHandler` from a pure function. `E = Nothing` so no
      * `Schema[E]` is required; we use the no-op `Schema[Nothing]` from
      * `internal.Codecs.given_Schema_Nothing` internally. */
    def fromPure[I: Schema, A <: Matchable: Schema](
      f:           I => A,
      description: String,
    ): ToolHandler[I, Any, Nothing, A] =
      new ToolHandler[I, Any, Nothing, A](
        description,
        summon[Schema[I]],
        summon[Schema[Nothing]],
        summon[Schema[A]],
        (i: I) => ZIO.succeed(f(i)),
      )

  /** Pure-function `.asHandler` extension. */
  extension [I: Schema, A <: Matchable: Schema](f: I => A)
    def asHandler(description: String): ToolHandler[I, Any, Nothing, A] =
      ToolHandler.fromPure(f, description)

  /** Effectful-function `.asHandler` extension. Requires `Schema[E]`. */
  extension [I: Schema, R, E <: Matchable: Schema, A <: Matchable: Schema](f: I => ZIO[R, E, A])
    def asHandler(description: String): ToolHandler[I, R, E, A] =
      ToolHandler(f, description)

  /** Opt-in declaration that the model can produce a final reply (text
    * or structured JSON) instead of dispatching a tool. Registering one
    * of these in a `Bedrock.request` switches the wire `toolChoice` from
    * `Any` (forced dispatch) to `Auto`.
    *
    * `text` and `Structured[A]` are mutually exclusive — registering
    * both is rejected at compile time by `Bedrock.request`. */
  sealed trait ModelResponseTool[+A <: Matchable]

  object ModelResponseTool:
    /** Free-form text reply. The model decides when to use it; no
      * description is needed (the model already knows it can write
      * text). */
    case object text extends ModelResponseTool[String]

    /** Structured reply — the model must produce JSON conforming to
      * `Schema[A]`. The framework configures the wire
      * `outputConfig.textFormat = json_schema(Schema[A])`. */
    final class Structured[+A <: Matchable] private[zio_bedrock_converse] (
      val description: String,
      private[zio_bedrock_converse] val outputSchema: Schema[?],
    ) extends ModelResponseTool[A]

    /** Build a structured-reply tool from a description and a `Schema`. */
    def apply[A <: Matchable: Schema](description: String): Structured[A] =
      new Structured[A](description, summon[Schema[A]])

  // ────────────────────────────────────────────────────────────────────
  // Type-level evidence + match types for the high-level `Bedrock.request`
  // ────────────────────────────────────────────────────────────────────

  /** Compile-time witness that every element of the tuple `Hs` is one of
    * `ToolHandler[?, ?, ?, ? <: Matchable]` or `ModelResponseTool[? <: Matchable]`.
    * Built inductively so error messages name the offending element. */
  sealed trait AllTools[Hs <: Tuple]
  object AllTools:
    given empty: AllTools[EmptyTuple] = new AllTools[EmptyTuple] {}

    given consHandler[I, R, E <: Matchable, A <: Matchable, Tail <: Tuple](
      using AllTools[Tail],
    ): AllTools[ToolHandler[I, R, E, A] *: Tail] =
      new AllTools[ToolHandler[I, R, E, A] *: Tail] {}

    given consText[Tail <: Tuple](using AllTools[Tail])
        : AllTools[ModelResponseTool.text.type *: Tail] =
      new AllTools[ModelResponseTool.text.type *: Tail] {}

    given consStructured[A <: Matchable, Tail <: Tuple](using AllTools[Tail])
        : AllTools[ModelResponseTool.Structured[A] *: Tail] =
      new AllTools[ModelResponseTool.Structured[A] *: Tail] {}

  /** Aggregated environment requirement of every `ToolHandler` in `Hs`.
    * `ModelResponseTool` cases contribute `Any`. Upper-bounded by `Any`
    * so signatures referencing `EnvOf[Hs]` typecheck even when `Hs` is
    * abstract. */
  type EnvOf[Hs <: Tuple] <: Any = Hs match
    case ToolHandler[?, r, ?, ?] *: rest => r & EnvOf[rest]
    case _                       *: rest => EnvOf[rest]
    case EmptyTuple                       => Any

  /** Aggregated typed-error union of every `ToolHandler` in `Hs`.
    * `ModelResponseTool` cases contribute nothing. */
  type ErrorsOf[Hs <: Tuple] <: Matchable = Hs match
    case ToolHandler[?, ?, e, ?] *: rest => e | ErrorsOf[rest]
    case _                       *: rest => ErrorsOf[rest]
    case EmptyTuple                       => Nothing

  /** Per-element output type for the fold. */
  type OutputOf[T] <: Matchable = T match
    case ToolHandler[?, ?, ?, a]         => a
    case ModelResponseTool.Structured[a] => a
    case ModelResponseTool.text.type     => String

  /** Tuple of per-tool fold functions. Each function takes the matching
    * tool's `OutputOf[H]` and produces the unified `R`. */
  type FoldFns[Hs <: Tuple, R] <: Tuple = Hs match
    case h *: rest  => (OutputOf[h] => R) *: FoldFns[rest, R]
    case EmptyTuple => EmptyTuple

  /** Count of `ModelResponseTool.text` occurrences in `Hs`. */
  type CountText[Hs <: Tuple] <: Int = Hs match
    case ModelResponseTool.text.type *: rest =>
      scala.compiletime.ops.int.+[1, CountText[rest]]
    case _ *: rest =>
      CountText[rest]
    case EmptyTuple => 0

  /** Count of `ModelResponseTool.Structured[?]` occurrences in `Hs`. */
  type CountStructured[Hs <: Tuple] <: Int = Hs match
    case ModelResponseTool.Structured[?] *: rest =>
      scala.compiletime.ops.int.+[1, CountStructured[rest]]
    case _ *: rest =>
      CountStructured[rest]
    case EmptyTuple => 0


  // ---------- Errors ----------

  sealed trait Error extends Throwable:
    /** Best-effort human-readable summary, surfaced as the throwable's
      * `getMessage` so test frameworks and logs print something useful. */
    def errorMessage: String
    override def getMessage: String = errorMessage

  object Error:
    final case class Validation         (message: String)                                extends Error:
      def errorMessage = s"Bedrock 400 Validation: $message"
    final case class AccessDenied       (message: String)                                extends Error:
      def errorMessage = s"Bedrock 403 AccessDenied: $message"
    final case class ResourceNotFound   (message: String)                                extends Error:
      def errorMessage = s"Bedrock 404 ResourceNotFound: $message"
    final case class ModelTimeout       (message: String)                                extends Error:
      def errorMessage = s"Bedrock 408 ModelTimeout: $message"
    final case class ModelErr           (message: String, originalStatusCode: Int | Null) extends Error:
      def errorMessage = s"Bedrock 424 ModelErr (orig=$originalStatusCode): $message"
    final case class Throttling         (message: String)                                extends Error:
      def errorMessage = s"Bedrock 429 Throttling: $message"
    final case class InternalServer     (message: String)                                extends Error:
      def errorMessage = s"Bedrock 500 InternalServer: $message"
    final case class ServiceUnavailable (message: String)                                extends Error:
      def errorMessage = s"Bedrock 503 ServiceUnavailable: $message"
    final case class Unexpected         (status: Status, body: String)                   extends Error:
      def errorMessage = s"Bedrock unexpected ${status.code}: $body"
    final case class Transport          (cause: Throwable)                               extends Error:
      def errorMessage = s"Transport error: ${cause.getMessage}"
    final case class MissingApiKey()                                                     extends Error:
      def errorMessage = "Missing API key (set AWS_BEARER_TOKEN_BEDROCK)"
    final case class MissingModelId()                                                    extends Error:
      def errorMessage = "Missing model ID (set BEDROCK_MODEL_ID)"
    final case class StructuredDecode   (responseText: String, message: String)          extends Error:
      def errorMessage = s"Structured-output decode failed: $message (response text: $responseText)"
    /** Model invoked a tool name that wasn't registered with the request. */
    final case class UnknownTool        (name: ToolName)                                 extends Error:
      def errorMessage = s"Model invoked unknown tool: $name"
    /** Model invoked a registered tool but its input JSON didn't decode against `Schema[I]`. */
    final case class InvalidToolInput   (name: ToolName, message: String)                extends Error:
      def errorMessage = s"Invalid input for tool $name: $message"
    /** Model produced a response that doesn't fit the registered tools. For
      * example, free-form text when no `ModelResponseTool` is registered, or
      * neither a recognised `tool_use` block nor a usable text reply. */
    final case class UnexpectedReply    (description: String)                            extends Error:
      def errorMessage = s"Unexpected model reply: $description"

    private[zio_bedrock_converse] def fromStatus(status: Status, body: String): Error =
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

    extension [R, A](zio: ZIO[R, Error, A])
      def retryOnRetryable: ZIO[R, Error, A] =
        zio.retry(
          Schedule.recurWhile[Error]:
            case _: (Throttling | InternalServer | ServiceUnavailable | ModelTimeout) => true
            case _                                                                    => false
          && Schedule.exponential(500.millis)
          && Schedule.recurs(2)
        )

  // ---------- Client (trait) ----------

  /** The Bedrock service. Owns the HTTP wire (auth + region + model).
    * Two impls live in this package: the live HTTP client (built by
    * `Client.live` / `Client.layer`) and the test-only mock. */
  trait Client:
    /** The model this client is configured to call. */
    def modelId: ModelId

    /** Single-turn round-trip on the wire. Internal — used by
      * `Bedrock.Request` terminals. */
    private[zio_bedrock_converse] def send(req: Wire.ConverseRequest): IO[Error, Wire.ConverseResponse]

  object Client:

    /** Build a `Client` from an explicit API key, region, and model. */
    def layer(apiKey: ApiKey, region: Region, modelId: ModelId): ZLayer[HClient, Nothing, Client] =
      ZLayer.fromZIO:
        ZIO.serviceWith[HClient](Http.buildClient(apiKey, region, modelId, _))

    /** Reads `AWS_BEARER_TOKEN_BEDROCK`, `BEDROCK_MODEL_ID` (both
      * required), and `AWS_REGION` (defaults to `us-east-1`). */
    val live: ZLayer[HClient, Error, Client] =
      ZLayer.fromZIO:
        defer:
          val apiKey =
            ZIO.systemWith(_.env("AWS_BEARER_TOKEN_BEDROCK"))
              .orDie
              .someOrFail(Error.MissingApiKey()).run
          val modelId =
            ZIO.systemWith(_.env("BEDROCK_MODEL_ID"))
              .orDie
              .someOrFail(Error.MissingModelId()).run
          val regionCode =
            ZIO.systemWith(_.env("AWS_REGION")).orDie.map(_.getOrElse(Region.UsEast1.code)).run
          val region = Region.fromCode(regionCode).getOrElse(Region.UsEast1)
          val client = ZIO.serviceWith[HClient](identity).run
          Http.buildClient(ApiKey(apiKey), region, ModelId(modelId), client)

  // ---------- Request (the data builder) ----------

  /** Builder/value returned by `Bedrock.converse`. Pure data; the
    * terminals pull a `Client` from the env. */
  final class Request private[zio_bedrock_converse] (
    private[zio_bedrock_converse] val cfg: RequestConfig,
  ):
    /** Final assistant text, joined across the model's last content blocks. */
    def text: ZIO[Client, Error, String] =
      asResponse.map(_.output.text)

    /** Full response, with the assistant turn in `output.message`. */
    def asResponse: ZIO[Client, Error, Result[Output]] =
      ZIO.serviceWithZIO[Client]: client =>
        client.send(Tools.toWire(cfg, outputConfig = None)).map: wire =>
          val publicContent = wire.output.message.content.collect:
            case Wire.ContentBlock.Text(t)        => ContentBlock.Text(t)
            case Wire.ContentBlock.ToolUse(tu)    => fromWireToolUse(tu)
            case Wire.ContentBlock.ToolResult(tr) => fromWireToolResult(tr)
          Result(
            output     = Output(Message(wire.output.message.role, publicContent)),
            stopReason = wire.stopReason,
            usage      = wire.usage,
            metrics    = wire.metrics,
          )

    /** Structured output: the model is told to produce JSON matching
      * `Schema[T]`, and the final text block is decoded into a `T`. */
    def as[T <: Matchable: Schema]: ZIO[Client, Error, T] =
      asResponse[T].map(_.output)

    /** Structured output with the full response envelope. */
    def asResponse[T <: Matchable: Schema]: ZIO[Client, Error, Result[T]] =
      val rawJsonSchema = zio.http.endpoint.openapi.JsonSchema.fromZSchema(
        summon[Schema[T]],
        zio.http.endpoint.openapi.JsonSchema.SchemaRef(
          zio.http.endpoint.openapi.JsonSchema.SchemaSpec.JsonSchema,
          zio.http.endpoint.openapi.JsonSchema.SchemaStyle.Inline,
        ),
      )
      // Bedrock requires every object schema in the structured-output
      // schema document to set `additionalProperties: false`.
      val outputJsonSchema = withStrictObjects(rawJsonSchema).toJson
      val outCfg = Wire.OutputConfig(Wire.TextFormat.JsonSchema(
        Wire.JsonSchemaStructure(Wire.JsonSchemaSpec(
          schema = outputJsonSchema,
          name   = "structured_output",
        )),
      ))
      val codec = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[T](Codecs.codecConfig)
      ZIO.serviceWithZIO[Client]: client =>
        client.send(Tools.toWire(cfg, outputConfig = Some(outCfg))).flatMap: wire =>
          val text = wire.output.message.content.collectFirst:
            case Wire.ContentBlock.Text(t) => t
          text match
            case None =>
              ZIO.fail(Error.StructuredDecode("", "no text block in response"))
            case Some(t) =>
              codec.decode(Chunk.fromArray(t.getBytes(java.nio.charset.StandardCharsets.UTF_8))) match
                case Right(value) => ZIO.succeed(Result(value, wire.stopReason, wire.usage, wire.metrics))
                case Left(err)    => ZIO.fail(Error.StructuredDecode(t, err.message))

  // ---------- Top-level builders ----------

  /** Build a `Request` from an explicit configuration. */
  def converse(cfg: RequestConfig): Request =
    new Request(cfg)

  /** Single-prompt convenience. */
  def converse(prompt: String): Request =
    new Request(RequestConfig(prompt))

  // ────────────────────────────────────────────────────────────────────
  // High-level: TooledRequest + Bedrock.request(prompt, NamedTuple)
  // ────────────────────────────────────────────────────────────────────

  /** Single-turn high-level request. Carries the prompt + system +
    * inferenceConfig and the pre-bundled handler / reply-tool registry.
    *
    * Construct via `Bedrock.request(prompt, namedTuple)`. The terminal
    * is `.fold(...)` — a NamedTuple of per-tool functions whose keys
    * must match those of the registered tools. */
  final class TooledRequest[NT <: NamedTuple.AnyNamedTuple] @scala.annotation.publicInBinary private[zio_bedrock_converse] (
    private[zio_bedrock_converse] val prompt:    String,
    private[zio_bedrock_converse] val systemMsg: String | Null,
    private[zio_bedrock_converse] val infCfg:    InferenceConfig | Null,
    private[zio_bedrock_converse] val handlers:  Map[ToolName, ToolHandler[?, ?, ? <: Matchable, ? <: Matchable]],
    private[zio_bedrock_converse] val replyTool: Option[(ToolName, ModelResponseTool[? <: Matchable])],
  ):
    /** Set or replace the system message. */
    def system(s: String): TooledRequest[NT] =
      new TooledRequest[NT](prompt, s, infCfg, handlers, replyTool)

    /** Set or replace the inference configuration. */
    def inferenceConfig(c: InferenceConfig): TooledRequest[NT] =
      new TooledRequest[NT](prompt, systemMsg, c, handlers, replyTool)

    /** Send the request and dispatch the response.
      *
      *  - If the model dispatches a registered tool: decode its input,
      *    run the handler, apply the matching `cases` function to its
      *    output. Handler `ZIO.fail(e)` propagates as `e` in the ZIO
      *    error channel (typed as `ErrorsOf[Hs]`).
      *  - If the model produces a reply (only possible when a
      *    `ModelResponseTool` is registered): apply the matching `cases`
      *    function. Decode failure for `ModelResponseTool[A]` →
      *    `Bedrock.Error.StructuredDecode`.
      *  - If neither (model misbehaves): `Bedrock.Error.UnexpectedReply`.
      *
      * The `cases` argument is a `NamedTuple` whose keys must be the
      * same as the registered tools' keys, and whose value at each key
      * is a function from the tool's typed output to the unified `R`.
      *
      * The ZIO error channel `Error | ErrorsOf[Hs]` carries every
      * possible failure: `Bedrock.Error` for wire/protocol problems,
      * the union of registered handler error types for handler
      * failures. Forces explicit handling — there's no `Outcome.*`
      * sum where a tool failure could be silently ignored. */
    inline def fold[R <: Matchable](
      cases: NamedTuple.NamedTuple[
        NamedTuple.Names[NT],
        FoldFns[NamedTuple.DropNames[NT], R],
      ],
    ): ZIO[
      Client & EnvOf[NamedTuple.DropNames[NT]],
      Error | ErrorsOf[NamedTuple.DropNames[NT]],
      Result[R],
    ] =
      val foldByName: Map[ToolName, Any => R] = TooledRequest.foldCasesToMap[NT, R](cases)
      foldImpl[R](foldByName)
        .asInstanceOf[
          ZIO[
            Client & EnvOf[NamedTuple.DropNames[NT]],
            Error | ErrorsOf[NamedTuple.DropNames[NT]],
            Result[R],
          ]
        ]

    private def foldImpl[R <: Matchable](
      foldByName: Map[ToolName, Any => R],
    ): ZIO[Client, Any, Result[R]] =
      ZIO.serviceWithZIO[Client]: client =>
        val wireReq = buildWireRequest()
        client.send(wireReq).flatMap: wire =>
          dispatch[R](wire, foldByName)

    /** Build the wire request from the registered handlers + reply
      * tool. Each handler becomes a wire `ToolDef.ToolSpec` whose name
      * is the registered key. `toolChoice` defaults to `Any` (forced
      * dispatch) when no reply tool is present, `Auto` when one is. */
    private def buildWireRequest(): Wire.ConverseRequest =
      val wireToolDefs: List[Wire.ToolDef] = handlers.toList.map: (name, h) =>
        Wire.ToolDef.ToolSpec(Wire.ToolSpecData(
          name        = name,
          description = Some(h.description),
          strict      = None,
          schema      = h.inputSchema,
        ))
      val wireToolConfig: Option[Wire.ToolConfig] =
        if wireToolDefs.isEmpty then None
        else Some(Wire.ToolConfig(
          tools      = wireToolDefs,
          toolChoice = Some(replyTool match
            case Some(_) => Wire.ToolChoice.Auto(Wire.EmptyObject())
            case None    => Wire.ToolChoice.Any(Wire.EmptyObject())),
        ))
      val wireOutputConfig: Option[Wire.OutputConfig] = replyTool match
        case Some((_, m: ModelResponseTool.Structured[?])) =>
          val rawJsonSchema = zio.http.endpoint.openapi.JsonSchema.fromZSchema(
            m.outputSchema.asInstanceOf[Schema[Any]],
            zio.http.endpoint.openapi.JsonSchema.SchemaRef(
              zio.http.endpoint.openapi.JsonSchema.SchemaSpec.JsonSchema,
              zio.http.endpoint.openapi.JsonSchema.SchemaStyle.Inline,
            ),
          )
          val outputJsonSchema = withStrictObjects(rawJsonSchema).toJson
          Some(Wire.OutputConfig(Wire.TextFormat.JsonSchema(
            Wire.JsonSchemaStructure(Wire.JsonSchemaSpec(
              schema = outputJsonSchema,
              name   = "structured_output",
            )),
          )))
        case _ => None
      val wireSystem: List[Wire.SystemContentBlock] = systemMsg match
        case null => Nil
        case s    => List(Wire.SystemContentBlock.Text(s))
      val wireInference: Option[InferenceConfig] =
        if infCfg.asInstanceOf[AnyRef] eq null then None
        else Some(infCfg.asInstanceOf[InferenceConfig])
      Wire.ConverseRequest(
        messages        = List(Wire.WireMessage(
          role    = Role.User,
          content = List(Wire.ContentBlock.Text(prompt)),
        )),
        system          = wireSystem,
        inferenceConfig = wireInference,
        toolConfig      = wireToolConfig,
        outputConfig    = wireOutputConfig,
      )

    /** Parse the wire response, dispatch to a handler or the reply
      * tool, surface failures via the ZIO error channel. */
    private def dispatch[R <: Matchable](
      wire:       Wire.ConverseResponse,
      foldByName: Map[ToolName, Any => R],
    ): ZIO[Any, Any, Result[R]] =
      val toolUseOpt = wire.output.message.content.collectFirst:
        case Wire.ContentBlock.ToolUse(tu) => tu
      toolUseOpt match
        case Some(tu) =>
          handlers.get(tu.name) match
            case None =>
              ZIO.fail(Error.UnknownTool(tu.name))
            case Some(handler) =>
              handler.inputSchema
                .asInstanceOf[Schema[Any]]
                .fromDynamic(tu.input) match
                  case Left(err) =>
                    ZIO.fail(Error.InvalidToolInput(tu.name, err))
                  case Right(typedInput) =>
                    val handlerErased = handler.handler.asInstanceOf[Any => ZIO[Any, Any, Any]]
                    handlerErased(typedInput).map: a =>
                      val fn = foldByName.getOrElse(
                        tu.name,
                        throw new IllegalStateException(s"No fold case for ${tu.name}"),
                      )
                      Result(
                        output     = fn(a),
                        stopReason = wire.stopReason,
                        usage      = wire.usage,
                        metrics    = wire.metrics,
                      )
        case None =>
          // No tool dispatch: must have come from the reply tool path.
          replyTool match
            case None =>
              ZIO.fail(Error.UnexpectedReply(
                "model returned no tool_use and no ModelResponseTool was registered",
              ))
            case Some((replyName, tool)) =>
              val textOpt = wire.output.message.content.collectFirst:
                case Wire.ContentBlock.Text(t) => t
              textOpt match
                case None =>
                  ZIO.fail(Error.UnexpectedReply(
                    "expected a text reply but the response had no text block",
                  ))
                case Some(text) =>
                  tool match
                    case _: ModelResponseTool.text.type =>
                      val fn = foldByName(replyName)
                      ZIO.succeed(Result(
                        output     = fn(text),
                        stopReason = wire.stopReason,
                        usage      = wire.usage,
                        metrics    = wire.metrics,
                      ))
                    case s: ModelResponseTool.Structured[?] =>
                      val codec = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[Any](
                        Codecs.codecConfig,
                      )(using s.outputSchema.asInstanceOf[Schema[Any]])
                      codec.decode(Chunk.fromArray(
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      )) match
                        case Right(value) =>
                          val fn = foldByName(replyName)
                          ZIO.succeed(Result(
                            output     = fn(value),
                            stopReason = wire.stopReason,
                            usage      = wire.usage,
                            metrics    = wire.metrics,
                          ))
                        case Left(err) =>
                          ZIO.fail(Error.StructuredDecode(text, err.message))

  object TooledRequest:
    /** Inline conversion of a user-supplied `cases: NamedTuple` into a
      * runtime `Map[ToolName, Any => R]` keyed by the NamedTuple's
      * compile-time names. */
    private[zio_bedrock_converse] inline def foldCasesToMap[
      NT <: NamedTuple.AnyNamedTuple,
      R <: Matchable,
    ](
      cases: NamedTuple.NamedTuple[
        NamedTuple.Names[NT],
        FoldFns[NamedTuple.DropNames[NT], R],
      ],
    ): Map[ToolName, Any => R] =
      val namesTup = compiletime.constValueTuple[NamedTuple.Names[NT]]
      val nameList = namesTup.toList.asInstanceOf[List[String]]
      val valuesList = cases.asInstanceOf[Tuple].toList.asInstanceOf[List[Any => R]]
      nameList.iterator.zip(valuesList.iterator).map: (n, f) =>
        (ToolName(n), f)
      .toMap

    /** Inline construction of a `TooledRequest` from a compile-time
      * NamedTuple of tools. Walks the names + values in parallel,
      * separating `ToolHandler`s into the registry and identifying the
      * (at most one) reply tool. */
    private[zio_bedrock_converse] inline def fromTools[NT <: NamedTuple.AnyNamedTuple](
      prompt: String,
      tools:  NT,
    ): TooledRequest[NT] =
      val namesTup = compiletime.constValueTuple[NamedTuple.Names[NT]]
      val nameList = namesTup.toList.asInstanceOf[List[String]]
      val valuesList = tools.asInstanceOf[Tuple].toList
      val handlers = scala.collection.mutable.Map.empty[
        ToolName,
        ToolHandler[?, ?, ? <: Matchable, ? <: Matchable],
      ]
      var replyTool: Option[(ToolName, ModelResponseTool[? <: Matchable])] = None
      nameList.iterator.zip(valuesList.iterator).foreach: (n, v) =>
        val tn = ToolName(n)
        v match
          case h: ToolHandler[?, ?, ?, ?] =>
            handlers(tn) = h.asInstanceOf[
              ToolHandler[?, ?, ? <: Matchable, ? <: Matchable]
            ]
          case mrt: ModelResponseTool[?] =>
            replyTool = Some((tn, mrt.asInstanceOf[ModelResponseTool[? <: Matchable]]))
      new TooledRequest[NT](
        prompt    = prompt,
        systemMsg = null,
        infCfg    = null,
        handlers  = handlers.toMap,
        replyTool = replyTool,
      )

  /** Build a single-turn high-level request bound to the given prompt
    * and a `NamedTuple` of registered tools.
    *
    * Compile-time enforcement:
    *   - The tuple is non-empty.
    *   - Every element is a `ToolHandler[…]` or a `ModelResponseTool[…]`.
    *   - At most one of `ModelResponseTool.text` / `ModelResponseTool[A]`. */
  inline def request[NT <: NamedTuple.AnyNamedTuple](
    prompt: String,
    tools:  NT,
  )(using
    inline ev:        NamedTuple.DropNames[NT] <:< NonEmptyTuple,
    inline allTools:  AllTools[NamedTuple.DropNames[NT]],
  ): TooledRequest[NT] =
    inline val nText       = compiletime.constValue[CountText[NamedTuple.DropNames[NT]]]
    inline val nStructured = compiletime.constValue[CountStructured[NamedTuple.DropNames[NT]]]
    inline if nText + nStructured > 1 then
      compiletime.error(
        "Only one of `ModelResponseTool.text` or `ModelResponseTool[A]` may be registered.",
      )
    else
      TooledRequest.fromTools[NT](prompt, tools)


  // ---------- Wire ↔ public translation helpers (private) ----------

  /** Recursively set `additionalProperties: false` on every object
    * schema. Bedrock rejects structured-output JSON Schema documents
    * that don't pin this explicitly. */
  private def withStrictObjects(
    s: zio.http.endpoint.openapi.JsonSchema,
  ): zio.http.endpoint.openapi.JsonSchema =
    import zio.http.endpoint.openapi.JsonSchema as JS
    s match
      case o: JS.Object =>
        o.copy(
          properties           = o.properties.view.mapValues(withStrictObjects).toMap,
          additionalProperties = Left(false),
        )
      case a: JS.AnnotatedSchema => a.copy(schema = withStrictObjects(a.schema))
      case a: JS.AllOfSchema     => a.copy(allOf  = a.allOf .map(withStrictObjects))
      case a: JS.AnyOfSchema     => a.copy(anyOf  = a.anyOf .map(withStrictObjects))
      case a: JS.OneOfSchema     => a.copy(oneOf  = a.oneOf .map(withStrictObjects))
      case a: JS.ArrayType       => a.copy(items  = a.items .map(withStrictObjects))
      case other                  => other

  private def fromWireToolUse(tu: Wire.ToolUseContent): ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      toolUseId = tu.toolUseId,
      name      = tu.name,
      input     = new ToolInput(tu.input),
    )

  private def fromWireToolResult(tr: Wire.ToolResultContent): ContentBlock.ToolResult =
    ContentBlock.ToolResult(
      toolUseId = tr.toolUseId,
      content   = tr.content.flatMap:
        case Wire.ToolResultBlock.Text(t) => List(ToolResultBlock.Text(t))
        case Wire.ToolResultBlock.Json(j) => List(ToolResultBlock.Json(new ToolInput(j)))
        case _                            => Nil,  // image/document/video deferred
      status    = tr.status match
        case None                                 => null
        case Some(Wire.ToolResultStatus.Success)  => ToolResultStatus.Success
        case Some(Wire.ToolResultStatus.Error)    => ToolResultStatus.Error
      ,
    )

  /** Convert a public `ContentBlock` into its wire counterpart. Used by
    * [[internal.Tools.toWire]] when serialising outbound messages. */
  private[zio_bedrock_converse] def toWireContentBlock(cb: ContentBlock): Wire.ContentBlock =
    cb match
      case ContentBlock.Text(t) => Wire.ContentBlock.Text(t)
      case ContentBlock.ToolUse(id, name, input) =>
        Wire.ContentBlock.ToolUse(Wire.ToolUseContent(
          toolUseId = id,
          name      = name,
          input     = input.raw,
        ))
      case ContentBlock.ToolResult(id, content, status) =>
        Wire.ContentBlock.ToolResult(Wire.ToolResultContent(
          toolUseId = id,
          content   = content.map:
            case ToolResultBlock.Text(t) => Wire.ToolResultBlock.Text(t)
            case ToolResultBlock.Json(v) => Wire.ToolResultBlock.Json(v.raw),
          status    = status match
            case null                       => None
            case ToolResultStatus.Success   => Some(Wire.ToolResultStatus.Success)
            case ToolResultStatus.Error     => Some(Wire.ToolResultStatus.Error)
          ,
        ))

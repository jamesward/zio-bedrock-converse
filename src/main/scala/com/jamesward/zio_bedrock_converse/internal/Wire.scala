package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock.*
import com.jamesward.zio_bedrock_converse.internal.Codecs.{given, *}
import zio.Chunk
import zio.http.endpoint.openapi.JsonSchema
import zio.schema.annotation.{caseName, discriminatorName, noDiscriminator}
import zio.schema.{DynamicValue, Schema, derived}

import java.util.Base64

/**
 * Wire-format types for the Bedrock Converse API.
 *
 * These case classes / enums mirror the JSON shapes AWS speaks. The public
 * `Bedrock` API translates user-facing types into these wire shapes
 * only when an HTTP request is being built.
 *
 * Wire types use `Option[T] = None` for optional fields because Schema
 * derivation is simpler against `Schema.Optional`; the public API still
 * uses `T | Null = null` for ergonomics. Nothing in this file is part of
 * the library's public surface.
 */
private[zio_bedrock_converse] object Wire:

  /** Bedrock requires tool inputSchema to be type: "object". If the
    * schema is a primitive/array, wrap it in an object with a single
    * "value" property. */
  private def ensureObjectSchema(js: JsonSchema): JsonSchema =
    js match
      case _: JsonSchema.Object => js
      case _ => JsonSchema.Object(
        properties           = Map("value" -> js),
        additionalProperties = Left(false),
        required             = Chunk("value"),
      )

  // ---------- Media / content blocks ----------

  enum ImageFormat derives Schema:
    @caseName("png")  case Png
    @caseName("jpeg") case Jpeg
    @caseName("gif")  case Gif
    @caseName("webp") case Webp

  enum DocumentFormat derives Schema:
    @caseName("pdf")  case Pdf
    @caseName("csv")  case Csv
    @caseName("doc")  case Doc
    @caseName("docx") case Docx
    @caseName("xls")  case Xls
    @caseName("xlsx") case Xlsx
    @caseName("html") case Html
    @caseName("txt")  case Txt
    @caseName("md")   case Md

  enum VideoFormat derives Schema:
    @caseName("mkv")      case Mkv
    @caseName("mov")      case Mov
    @caseName("mp4")      case Mp4
    @caseName("webm")     case Webm
    @caseName("three_gp") case ThreeGp
    @caseName("flv")      case Flv
    @caseName("mpeg")     case Mpeg
    @caseName("mpg")      case Mpg
    @caseName("wmv")      case Wmv

  case class S3Location(uri: String, bucketOwner: Option[String] = None) derives Schema

  @noDiscriminator
  enum MediaSource derives Schema:
    case BytesSource(bytes: String)
    case S3LocationSource(s3Location: S3Location)

  object MediaSource:
    def fromBytes(b: Array[Byte]): MediaSource =
      BytesSource(Base64.getEncoder.encodeToString(b))

  case class ImageContent(format: ImageFormat, source: MediaSource) derives Schema
  case class DocumentContent(format: DocumentFormat, name: String, source: MediaSource) derives Schema
  case class VideoContent(format: VideoFormat, source: MediaSource) derives Schema

  // ---------- Tool-use / tool-result payloads ----------

  case class ToolUseContent(
    toolUseId: ToolUseId,
    name:      ToolName,
    input:     DynamicValue,
  ) derives Schema

  enum ToolResultStatus derives Schema:
    @caseName("success") case Success
    @caseName("error")   case Error

  given CanEqual[ToolResultStatus, ToolResultStatus] = CanEqual.derived

  @noDiscriminator
  enum ToolResultBlock derives Schema:
    case Text(text: String)
    case Json(json: DynamicValue)
    case Image(image: ImageContent)
    case Document(document: DocumentContent)
    case Video(video: VideoContent)

  case class ToolResultContent(
    toolUseId: ToolUseId,
    content:   List[ToolResultBlock],
    status:    Option[ToolResultStatus] = None,
  ) derives Schema

  enum CachePointType derives Schema:
    @caseName("default") case Default

  case class CachePointContent(`type`: CachePointType) derives Schema

  case class ReasoningTextBlock(
    text:      String,
    signature: Option[String] = None,
  ) derives Schema

  case class ReasoningContentBlock(
    reasoningText:   Option[ReasoningTextBlock] = None,
    redactedContent: Option[String]             = None,
  ) derives Schema

  @noDiscriminator
  enum ContentBlock derives Schema:
    case Text(text: String)
    case Image(image: ImageContent)
    case Document(document: DocumentContent)
    case Video(video: VideoContent)
    case ToolUse(toolUse: ToolUseContent)
    case ToolResult(toolResult: ToolResultContent)
    case CachePoint(cachePoint: CachePointContent)
    case ReasoningContent(reasoningContent: ReasoningContentBlock)

  @noDiscriminator
  enum SystemContentBlock derives Schema:
    case Text(text: String)
    case CachePoint(cachePoint: CachePointContent)

  case class WireMessage(role: Role, content: List[ContentBlock]) derives Schema

  // ---------- Tool config (request side) ----------

  final class ToolSpecData private[zio_bedrock_converse] (
    val name:        ToolName,
    val description: Option[String],
    val strict:      Option[Boolean],
    private[zio_bedrock_converse] val schemaI: Schema[?],
  ):
    override def toString: String = s"ToolSpecData($name, $description, $strict)"

  object ToolSpecData:
    def apply(
      name:        ToolName,
      description: Option[String],
      strict:      Option[Boolean],
      schema:      Schema[?],
    ): ToolSpecData = new ToolSpecData(name, description, strict, schema)

    private case class Wire(
      name:        ToolName,
      description: Option[String]  = None,
      inputSchema: InputSchema,
      strict:      Option[Boolean] = None,
    ) derives Schema

    given Schema[ToolSpecData] =
      summon[Schema[Wire]].transform[ToolSpecData](
        w => new ToolSpecData(w.name, w.description, w.strict, Schema[Unit]),
        t => Wire(
          name        = t.name,
          description = t.description,
          inputSchema = InputSchema(ensureObjectSchema(JsonSchema.fromZSchema(
            t.schemaI,
            JsonSchema.SchemaRef(JsonSchema.SchemaSpec.JsonSchema, JsonSchema.SchemaStyle.Inline),
          ))),
          strict      = t.strict,
        ),
      )

  @noDiscriminator
  enum ToolDef derives Schema:
    case ToolSpec(toolSpec: ToolSpecData)
    case CachePoint(cachePoint: CachePointContent)

  case class EmptyObject() derives Schema
  case class ToolChoiceTool(name: ToolName) derives Schema

  @noDiscriminator
  enum ToolChoice derives Schema:
    case Auto(auto: EmptyObject)
    case Any(any:   EmptyObject)
    case Tool(tool: ToolChoiceTool)

  object ToolChoice:
    val auto: ToolChoice = Auto(EmptyObject())
    val any:  ToolChoice = Any(EmptyObject())

  case class ToolConfig(
    tools:      List[ToolDef],
    toolChoice: Option[ToolChoice] = None,
  ) derives Schema

  // ---------- Structured output ----------

  case class JsonSchemaSpec(
    schema:      String,
    name:        String,
    description: Option[String] = None,
  ) derives Schema

  case class JsonSchemaStructure(jsonSchema: JsonSchemaSpec) derives Schema

  @discriminatorName("type")
  enum TextFormat derives Schema:
    @caseName("json_schema") case JsonSchema(structure: JsonSchemaStructure)

  case class OutputConfig(textFormat: TextFormat) derives Schema

  // ---------- Wire request / response ----------

  case class ConverseRequest(
    messages:        List[WireMessage],
    system:          List[SystemContentBlock]   = Nil,
    inferenceConfig: Option[InferenceConfig]    = None,
    toolConfig:      Option[ToolConfig]         = None,
    outputConfig:    Option[OutputConfig]       = None,
    requestMetadata: Map[String, String]        = Map.empty,
    additionalModelResponseFieldPaths: List[String] = Nil,
  ) derives Schema

  case class ConverseOutput(message: WireMessage) derives Schema

  case class ConverseResponse(
    output:     ConverseOutput,
    stopReason: StopReason,
    usage:      TokenUsage,
    metrics:    Metrics,
  ) derives Schema

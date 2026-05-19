package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.schema.Schema

private[zio_bedrock_converse] object TooledRequestImpl:

  def foldImpl[R <: Matchable](
    tr:         TooledRequest[?],
    foldByName: Map[ToolName, Any => R],
  ): ZIO[Client, Any, Result[R]] =
    ZIO.serviceWithZIO[Client]: client =>
      client.send(buildWireRequest(tr)).flatMap: wire =>
        dispatch[R](tr, wire, foldByName)

  def buildWireRequest(tr: TooledRequest[?]): Wire.ConverseRequest =
    val wireToolDefs: List[Wire.ToolDef] = tr.handlers.toList.map: (name, h) =>
      Wire.ToolDef.ToolSpec(Wire.ToolSpecData(name, Some(h.description), None, h.inputSchema))
    val wireToolConfig: Option[Wire.ToolConfig] =
      if wireToolDefs.isEmpty then None
      else Some(Wire.ToolConfig(
        wireToolDefs,
        Some(tr.replyTool match
          case Some(_) => Wire.ToolChoice.Auto(Wire.EmptyObject())
          case None    => Wire.ToolChoice.Any(Wire.EmptyObject())),
      ))
    val wireOutputConfig: Option[Wire.OutputConfig] = tr.replyTool match
      case Some((_, m: ModelResponseTool.Structured[?])) =>
        val rawJsonSchema = zio.http.endpoint.openapi.JsonSchema.fromZSchema(
          m.outputSchema.asInstanceOf[Schema[Any]],
          zio.http.endpoint.openapi.JsonSchema.SchemaRef(
            zio.http.endpoint.openapi.JsonSchema.SchemaSpec.JsonSchema,
            zio.http.endpoint.openapi.JsonSchema.SchemaStyle.Inline,
          ),
        )
        val outputJsonSchema = Helpers.withStrictObjects(rawJsonSchema).toJson
        Some(Wire.OutputConfig(Wire.TextFormat.JsonSchema(
          Wire.JsonSchemaStructure(Wire.JsonSchemaSpec(outputJsonSchema, "structured_output")),
        )))
      case _ => None
    val wireSystem: List[Wire.SystemContentBlock] = tr.systemMsg match
      case null => Nil
      case s    => List(Wire.SystemContentBlock.Text(s))
    val wireInference: Option[InferenceConfig] =
      if tr.infCfg.asInstanceOf[AnyRef] eq null then None else Some(tr.infCfg.asInstanceOf[InferenceConfig])
    Wire.ConverseRequest(
      messages        = List(Wire.WireMessage(Role.User, List(Wire.ContentBlock.Text(tr.prompt)))),
      system          = wireSystem,
      inferenceConfig = wireInference,
      toolConfig      = wireToolConfig,
      outputConfig    = wireOutputConfig,
    )

  def dispatch[R <: Matchable](
    tr:         TooledRequest[?],
    wire:       Wire.ConverseResponse,
    foldByName: Map[ToolName, Any => R],
  ): ZIO[Any, Any, Result[R]] =
    val toolUseOpt = wire.output.message.content.collectFirst:
      case Wire.ContentBlock.ToolUse(tu) => tu
    toolUseOpt match
      case Some(tu) =>
        tr.handlers.get(tu.name) match
          case None => ZIO.fail(Error.UnknownTool(tu.name))
          case Some(handler) =>
            handler.inputSchema.asInstanceOf[Schema[Any]].fromDynamic(unwrapIfPrimitive(handler.inputSchema, tu.input)) match
              case Left(err) => ZIO.fail(Error.InvalidToolInput(tu.name, err))
              case Right(typedInput) =>
                val handlerErased = handler.handler.asInstanceOf[Any => ZIO[Any, Any, Any]]
                handlerErased(typedInput).map: a =>
                  val fn = foldByName.getOrElse(tu.name, throw new IllegalStateException(s"No fold case for ${tu.name}"))
                  Result(fn(a), wire.stopReason, wire.usage, wire.metrics)
      case None =>
        tr.replyTool match
          case None =>
            ZIO.fail(Error.UnexpectedReply("model returned no tool_use and no ModelResponseTool was registered"))
          case Some((replyName, tool)) =>
            val textOpt = wire.output.message.content.collectFirst { case Wire.ContentBlock.Text(t) => t }
            textOpt match
              case None =>
                ZIO.fail(Error.UnexpectedReply("expected a text reply but the response had no text block"))
              case Some(text) =>
                tool match
                  case _: ModelResponseTool.text.type =>
                    ZIO.succeed(Result(foldByName(replyName)(text), wire.stopReason, wire.usage, wire.metrics))
                  case s: ModelResponseTool.Structured[?] =>
                    val codec = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[Any](Codecs.codecConfig)(using s.outputSchema.asInstanceOf[Schema[Any]])
                    codec.decode(Chunk.fromArray(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))) match
                      case Right(value) =>
                        ZIO.succeed(Result(foldByName(replyName)(value), wire.stopReason, wire.usage, wire.metrics))
                      case Left(err) =>
                        ZIO.fail(Error.StructuredDecode(text, err.message))

  private def unwrapIfPrimitive(schema: zio.schema.Schema[?], dv: zio.schema.DynamicValue): zio.schema.DynamicValue =
    schema match
      case _: zio.schema.Schema.Record[?] => dv
      case _ =>
        dv match
          case zio.schema.DynamicValue.Record(_, fields) =>
            fields.collectFirst { case ("value", v) => coercePrimitive(schema, v) }.getOrElse(dv)
          case _ => dv

  private def coercePrimitive(schema: zio.schema.Schema[?], dv: zio.schema.DynamicValue): zio.schema.DynamicValue =
    import zio.schema.{StandardType as ST}
        given CanEqual[ST[?], ST[?]] = CanEqual.derived
    (schema, dv) match
      case (p: zio.schema.Schema.Primitive[?], zio.schema.DynamicValue.Primitive(v: java.math.BigDecimal, _)) =>
        val st = p.standardType.asInstanceOf[ST[?]]
        if st == ST.IntType then zio.schema.DynamicValue.Primitive(v.intValue, ST.IntType)
        else if st == ST.LongType then zio.schema.DynamicValue.Primitive(v.longValue, ST.LongType)
        else if st == ST.DoubleType then zio.schema.DynamicValue.Primitive(v.doubleValue, ST.DoubleType)
        else if st == ST.FloatType then zio.schema.DynamicValue.Primitive(v.floatValue, ST.FloatType)
        else if st == ST.ShortType then zio.schema.DynamicValue.Primitive(v.shortValue, ST.ShortType)
        else dv
      case _ => dv

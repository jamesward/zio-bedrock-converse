package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.schema.{DynamicValue, Schema}
import zio.stream.*

private[zio_bedrock_converse] object LoopImpl:

  def runLoop(
    lr:           LoopRequest[?],
    outputConfig: Option[Wire.OutputConfig],
  ): ZIO[Client, Error, Wire.ConverseResponse] =
    ZIO.serviceWithZIO[Client]: client =>
      val (wireToolConfig, wireSystem, wireInference, initialMessages) = buildBase(lr)

      def step(messages: List[Wire.WireMessage], iterations: Int): ZIO[Any, Error, Wire.ConverseResponse] =
        if iterations > lr.maxIter then ZIO.fail(Error.MaxIterations(iterations - 1))
        else
          val wireReq = Wire.ConverseRequest(messages, wireSystem, wireInference, wireToolConfig, outputConfig)
          client.send(wireReq).flatMap: wire =>
            val toolUses = wire.output.message.content.collect { case Wire.ContentBlock.ToolUse(tu) => tu }
            if toolUses.nonEmpty then
              val toolNames = toolUses.map(_.name).mkString(", ")
              ZIO.logDebug(s"[Bedrock.loop] iteration=$iterations tool_use=[$toolNames]") *>
              ZIO.foreach(toolUses)(dispatchTool(lr, _)).flatMap: results =>
                val toolResultMsg = Wire.WireMessage(Role.User, results.toList.map(Wire.ContentBlock.ToolResult.apply))
                step(messages :+ wire.output.message :+ toolResultMsg, iterations + 1)
            else
              val textPreview = wire.output.message.content.collectFirst { case Wire.ContentBlock.Text(t) => t.take(200) }.getOrElse("<no text>")
              ZIO.logDebug(s"[Bedrock.loop] iteration=$iterations reply=$textPreview") *>
              ZIO.succeed(wire)

      step(initialMessages, 1)

  def runLoopEventStream(
    lr:           LoopRequest[?],
    outputConfig: Option[Wire.OutputConfig],
  ): ZStream[Client, Error, StreamEvent] =
    ZStream.unwrap:
      ZIO.serviceWith[Client]: client =>
        val (wireToolConfig, wireSystem, wireInference, initialMessages) = buildBase(lr)

        def streamStep(messages: List[Wire.WireMessage], iterations: Int): ZStream[Any, Error, StreamEvent] =
          if iterations > lr.maxIter then ZStream.fail(Error.MaxIterations(iterations - 1))
          else
            val wireReq = Wire.ConverseRequest(messages, wireSystem, wireInference, wireToolConfig, outputConfig)
            val eventsStream = client.sendStreamEvents(wireReq)
            ZStream.unwrap:
              for
                toolUsesRef  <- Ref.make(List.empty[(ToolUseId, ToolName)])
                inputBufRef  <- Ref.make(new StringBuilder)
                completedRef <- Ref.make(List.empty[Wire.ToolUseContent])
              yield
                val emitAndCollect = eventsStream.mapZIO: event =>
                  event match
                    case StreamEvent.ToolUseStart(id, name) =>
                      flushAndAdd(toolUsesRef, inputBufRef, completedRef, id, name).as(event)
                    case StreamEvent.ToolUseDelta(_, inputJson) =>
                      inputBufRef.update(_.append(inputJson)).as(event)
                    case StreamEvent.ContentBlockStop(_) =>
                      flushCurrent(toolUsesRef, inputBufRef, completedRef).as(event)
                    case _ => ZIO.succeed(event)

                emitAndCollect ++ ZStream.unwrap:
                  completedRef.get.flatMap: toolUses =>
                    if toolUses.isEmpty then ZIO.succeed(ZStream.empty)
                    else
                      ZIO.logDebug(s"[Bedrock.loop.asStream] iteration=$iterations tool_use=[${toolUses.map(_.name).mkString(", ")}]") *>
                      ZIO.foreach(toolUses)(dispatchTool(lr, _)).map: results =>
                        val assistantMsg = Wire.WireMessage(Role.Assistant, toolUses.map(tu => Wire.ContentBlock.ToolUse(tu)))
                        val toolResultMsg = Wire.WireMessage(Role.User, results.toList.map(Wire.ContentBlock.ToolResult.apply))
                        streamStep(messages :+ assistantMsg :+ toolResultMsg, iterations + 1)

        streamStep(initialMessages, 1)

  def runLoopStream(
    lr:           LoopRequest[?],
    outputConfig: Option[Wire.OutputConfig],
  ): ZStream[Client, Error, String] =
    ZStream.unwrap:
      ZIO.serviceWith[Client]: client =>
        val (wireToolConfig, wireSystem, wireInference, initialMessages) = buildBase(lr)

        def streamStep(messages: List[Wire.WireMessage], iterations: Int): ZStream[Any, Error, String] =
          if iterations > lr.maxIter then ZStream.fail(Error.MaxIterations(iterations - 1))
          else
            val wireReq = Wire.ConverseRequest(messages, wireSystem, wireInference, wireToolConfig, outputConfig)
            val eventsStream = client.sendStreamEvents(wireReq)
            ZStream.unwrap:
              for
                toolUsesRef  <- Ref.make(List.empty[(ToolUseId, ToolName)])
                inputBufRef  <- Ref.make(new StringBuilder)
                completedRef <- Ref.make(List.empty[Wire.ToolUseContent])
              yield
                val textAndCollect = eventsStream.mapZIO:
                  case StreamEvent.TextDelta(text) => ZIO.succeed(Some(text))
                  case StreamEvent.ToolUseStart(id, name) =>
                    flushAndAdd(toolUsesRef, inputBufRef, completedRef, id, name).as(None)
                  case StreamEvent.ToolUseDelta(_, inputJson) =>
                    inputBufRef.update(_.append(inputJson)).as(None)
                  case StreamEvent.ContentBlockStop(_) =>
                    flushCurrent(toolUsesRef, inputBufRef, completedRef).as(None)
                  case _ => ZIO.succeed(None)
                .collect { case Some(text) => text }

                textAndCollect ++ ZStream.unwrap:
                  completedRef.get.flatMap: toolUses =>
                    if toolUses.isEmpty then ZIO.succeed(ZStream.empty)
                    else
                      ZIO.logDebug(s"[Bedrock.loop.stream] iteration=$iterations tool_use=[${toolUses.map(_.name).mkString(", ")}]") *>
                      ZIO.foreach(toolUses)(dispatchTool(lr, _)).map: results =>
                        val assistantMsg = Wire.WireMessage(Role.Assistant, toolUses.map(tu => Wire.ContentBlock.ToolUse(tu)))
                        val toolResultMsg = Wire.WireMessage(Role.User, results.toList.map(Wire.ContentBlock.ToolResult.apply))
                        streamStep(messages :+ assistantMsg :+ toolResultMsg, iterations + 1)

        streamStep(initialMessages, 1)

  // ── shared helpers ──

  private def buildBase(lr: LoopRequest[?]) =
    val wireToolDefs: List[Wire.ToolDef] = lr.handlers.toList.map: (name, h) =>
      Wire.ToolDef.ToolSpec(Wire.ToolSpecData(name, Some(h.description), None, h.inputSchema))
    val wireToolConfig =
      if wireToolDefs.isEmpty then None
      else Some(Wire.ToolConfig(wireToolDefs, Some(Wire.ToolChoice.Auto(Wire.EmptyObject()))))
    val wireSystem: List[Wire.SystemContentBlock] = lr.systemMsg match
      case null => Nil
      case s    => List(Wire.SystemContentBlock.Text(s))
    val wireInference: Option[InferenceConfig] =
      if lr.infCfg.asInstanceOf[AnyRef] eq null then None else Some(lr.infCfg.asInstanceOf[InferenceConfig])
    val initialMessages = List(Wire.WireMessage(Role.User, List(Wire.ContentBlock.Text(lr.prompt))))
    (wireToolConfig, wireSystem, wireInference, initialMessages)

  def dispatchTool(lr: LoopRequest[?], tu: Wire.ToolUseContent): ZIO[Any, Nothing, Wire.ToolResultContent] =
    lr.handlers.get(tu.name) match
      case None =>
        ZIO.logDebug(s"[Bedrock.loop] dispatch unknown tool=${tu.name}") *>
        ZIO.succeed(errorResult(tu.toolUseId, s"Unknown tool: ${tu.name}"))
      case Some(handler) =>
        val unwrapped = unwrapIfPrimitive(handler.inputSchema, tu.input)
        handler.inputSchema.asInstanceOf[Schema[Any]].fromDynamic(unwrapped) match
          case Left(err) =>
            ZIO.logDebug(s"[Bedrock.loop] dispatch tool=${tu.name} invalid_input=$err") *>
            ZIO.succeed(errorResult(tu.toolUseId, s"Invalid input: $err"))
          case Right(typedInput) =>
            ZIO.logDebug(s"[Bedrock.loop] dispatch tool=${tu.name} input=$typedInput") *> {
            val handlerErased = handler.handler.asInstanceOf[Any => ZIO[Any, Any, Any]]
            val outSchema     = handler.outputSchema.asInstanceOf[Schema[Any]]
            val errSchema     = handler.errorSchema.asInstanceOf[Schema[Any]]
            handlerErased(typedInput).foldZIO(
              e =>
                ZIO.logDebug(s"[Bedrock.loop] dispatch tool=${tu.name} handler_error=$e").as:
                  Wire.ToolResultContent(tu.toolUseId, List(Wire.ToolResultBlock.Json(wrapIfPrimitive(errSchema, errSchema.toDynamic(e)))), Some(Wire.ToolResultStatus.Error)),
              a =>
                ZIO.logDebug(s"[Bedrock.loop] dispatch tool=${tu.name} result=$a").as:
                  Wire.ToolResultContent(tu.toolUseId, List(Wire.ToolResultBlock.Json(wrapIfPrimitive(outSchema, outSchema.toDynamic(a)))), Some(Wire.ToolResultStatus.Success)),
            )}

  private def errorResult(toolUseId: ToolUseId, msg: String): Wire.ToolResultContent =
    Wire.ToolResultContent(toolUseId, List(Wire.ToolResultBlock.Text(msg)), Some(Wire.ToolResultStatus.Error))

  /** Flush the last tool's accumulated input buffer into completedRef, then add the new tool. */
  private def flushAndAdd(
    toolUsesRef:  Ref[List[(ToolUseId, ToolName)]],
    inputBufRef:  Ref[StringBuilder],
    completedRef: Ref[List[Wire.ToolUseContent]],
    id: ToolUseId, name: ToolName,
  ): ZIO[Any, Nothing, Unit] =
    (toolUsesRef.get zip inputBufRef.getAndSet(new StringBuilder)).flatMap:
      case (tools, buf) =>
        val flush = tools.lastOption match
          case Some((prevId, prevName)) if buf.nonEmpty =>
            completedRef.update(_ :+ Wire.ToolUseContent(prevId, prevName, parseDynamic(buf.toString)))
          case _ => ZIO.unit
        flush *> toolUsesRef.set(tools :+ (id, name))

  /** Flush the current (last) tool's input buffer on ContentBlockStop. */
  private def flushCurrent(
    toolUsesRef:  Ref[List[(ToolUseId, ToolName)]],
    inputBufRef:  Ref[StringBuilder],
    completedRef: Ref[List[Wire.ToolUseContent]],
  ): ZIO[Any, Nothing, Unit] =
    (toolUsesRef.get zip inputBufRef.getAndSet(new StringBuilder)).flatMap:
      case (tools, buf) =>
        tools.lastOption match
          case Some((id, name)) if buf.nonEmpty =>
            completedRef.update(_ :+ Wire.ToolUseContent(id, name, parseDynamic(buf.toString))) *>
            toolUsesRef.update(_.init)
          case _ => ZIO.unit

  private def parseDynamic(json: String): DynamicValue =
    val dynCodec = zio.schema.codec.JsonCodec.schemaBasedBinaryCodec[DynamicValue](Codecs.codecConfig)
    dynCodec.decode(Chunk.fromArray(json.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
      .getOrElse(DynamicValue.NoneValue)

  /** If the schema is not a record, wrap the DynamicValue in a Record with a "value" field
    * so Bedrock accepts it as a JSON object. */
  private def wrapIfPrimitive(schema: Schema[?], dv: DynamicValue): DynamicValue =
    schema match
      case _: Schema.Record[?] => dv
      case _ => DynamicValue.Record(
        zio.schema.TypeId.Structural,
        scala.collection.immutable.ListMap("value" -> dv),
      )

  /** If the handler's input schema is not a record (i.e. primitive/enum/sequence),
    * the wire wraps it in {"value": ...}. Unwrap the DynamicValue accordingly. */
  private def unwrapIfPrimitive(schema: Schema[?], dv: DynamicValue): DynamicValue =
    schema match
      case _: Schema.Record[?] => dv
      case _ =>
        dv match
          case DynamicValue.Record(_, fields) =>
            fields.collectFirst { case ("value", v) => coercePrimitive(schema, v) }.getOrElse(dv)
          case _ => dv

  /** JSON decodes numbers as bigDecimal. Coerce to the target primitive type. */
  private def coercePrimitive(schema: Schema[?], dv: DynamicValue): DynamicValue =
    import zio.schema.{StandardType as ST}
        given CanEqual[ST[?], ST[?]] = CanEqual.derived
    (schema, dv) match
      case (p: Schema.Primitive[?], DynamicValue.Primitive(v: java.math.BigDecimal, _)) =>
        val st = p.standardType.asInstanceOf[ST[?]]
        if st == ST.IntType then DynamicValue.Primitive(v.intValue, ST.IntType)
        else if st == ST.LongType then DynamicValue.Primitive(v.longValue, ST.LongType)
        else if st == ST.DoubleType then DynamicValue.Primitive(v.doubleValue, ST.DoubleType)
        else if st == ST.FloatType then DynamicValue.Primitive(v.floatValue, ST.FloatType)
        else if st == ST.ShortType then DynamicValue.Primitive(v.shortValue, ST.ShortType)
        else dv
      case _ => dv

package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.{ContentBlock, ConverseError, ConverseRequest, InferenceConfig, Role, StopReason, Tool, ToolName}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.direct.*
import zio.schema.{DynamicValue, Schema}

/**
 * Multi-turn tool-dispatch driver. Translates the user-facing
 * [[ConverseRequest]] into a wire request, sends it via the service's
 * `Sender`, and loops while the model is asking to use tools: each
 * `tool_use` content block is matched to a registered [[Tool]] by name,
 * the input is decoded via the tool's captured `Schema`, the handler runs,
 * the output is encoded back into a `toolResult`, and the loop continues
 * until the model returns a final answer (or a tool fails, propagating
 * `E` to the caller).
 *
 * Returns the final wire response. The public `ConverseInvoker` shapes
 * it into `ConverseOutput`, structured `T`, etc.
 */
private[zio_bedrock_converse] object Tools:

  def runLoop[R, E](
    send:         BedrockConverse.Sender,
    req:          ConverseRequest,
    tools:        IndexedSeq[Tool[?, ?, R, E]],
    outputConfig: Option[Wire.OutputConfig],
  ): ZIO[R, ConverseError | E, Wire.ConverseResponse[DynamicValue]] =
    val initial  = toWire(req, tools, outputConfig)
    val registry = tools.iterator.map(t => t.name -> t).toMap
    loop[R, E](send, initial, registry)

  // ---------- Loop ----------

  private def loop[R, E](
    send:     BedrockConverse.Sender,
    initial:  Wire.ConverseRequest[DynamicValue],
    registry: Map[ToolName, Tool[?, ?, R, E]],
  ): ZIO[R, ConverseError | E, Wire.ConverseResponse[DynamicValue]] =
    def step(req: Wire.ConverseRequest[DynamicValue]): ZIO[R, ConverseError | E, Wire.ConverseResponse[DynamicValue]] =
      defer:
        val resp = send(req).run
        resp.stopReason match
          case StopReason.ToolUse =>
            val toolUses = resp.output.message.content.collect:
              case Wire.ContentBlock.ToolUse(tu) => tu
            val results = ZIO.foreach(toolUses)(invoke(registry, _)).run
            val toolResultMsg = Wire.WireMessage[DynamicValue](
              role    = Role.User,
              content = results.toList.map(Wire.ContentBlock.ToolResult.apply),
            )
            val nextReq = req.copy(
              messages = req.messages :+ resp.output.message :+ toolResultMsg,
            )
            step(nextReq).run
          case _ => resp
    step(initial)

  private def invoke[R, E](
    registry: Map[ToolName, Tool[?, ?, R, E]],
    use:      Wire.ToolUseContent[DynamicValue],
  ): ZIO[R, ConverseError | E, Wire.ToolResultContent[DynamicValue]] =
    registry.get(use.name) match
      case None =>
        ZIO.fail(ConverseError.UnknownTool(use.name))
      case Some(tool) =>
        val inSchema  = tool.inputSchema.asInstanceOf[Schema[Any]]
        val outSchema = tool.outputSchema.asInstanceOf[Schema[Any]]
        val handler   = tool.handler.asInstanceOf[Any => ZIO[R, E, Any]]
        ZIO.fromEither(inSchema.fromDynamic(use.input))
          .mapError(msg => ConverseError.InvalidToolInput(use.name, msg.toString))
          .flatMap: typedInput =>
            handler(typedInput).map: output =>
              Wire.ToolResultContent(
                toolUseId = use.toolUseId,
                content   = List(Wire.ToolResultBlock.Json(outSchema.toDynamic(output))),
              )

  // ---------- Wire translation ----------

  private def toWire[R, E](
    req:          ConverseRequest,
    tools:        IndexedSeq[Tool[?, ?, R, E]],
    outputConfig: Option[Wire.OutputConfig],
  ): Wire.ConverseRequest[DynamicValue] =
    val messages = req.messages.map: m =>
      val wireContent = m.content.map:
        case ContentBlock.Text(t) => Wire.ContentBlock.Text[DynamicValue](t)
      Wire.WireMessage[DynamicValue](role = m.role, content = wireContent)
    val system = req.system match
      case null => Nil
      case s    => List(Wire.SystemContentBlock.Text(s))
    val toolConfig =
      if tools.isEmpty then None
      else Some(Wire.ToolConfig(
        tools = tools.iterator.map: t =>
          Wire.ToolDef.ToolSpec(Wire.ToolSpecData(
            name        = t.name,
            description = Some(t.description),
            strict      = None,
            schema      = t.inputSchema,
          ))
        .toList,
      ))
    val inference =
      if req.inferenceConfig.asInstanceOf[AnyRef] eq null then None
      else Some(req.inferenceConfig.asInstanceOf[InferenceConfig])
    Wire.ConverseRequest[DynamicValue](
      messages        = messages,
      system          = system,
      inferenceConfig = inference,
      toolConfig      = toolConfig,
      outputConfig    = outputConfig,
    )

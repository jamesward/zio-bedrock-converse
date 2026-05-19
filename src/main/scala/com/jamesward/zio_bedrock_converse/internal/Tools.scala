package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.{InferenceConfig, RequestConfig, ToolChoice, ToolConfig}

/**
 * Wire translation for the public [[RequestConfig]].
 *
 * Single-turn only — the multi-turn tool-dispatch loop is set aside in
 * this slice. When the loop is reintroduced as `Bedrock.loop`, the
 * runLoop helper will return alongside it.
 */
private[zio_bedrock_converse] object Tools:

  def toWire(
    cfg:          RequestConfig,
    outputConfig: Option[Wire.OutputConfig],
  ): Wire.ConverseRequest =
    val messages = cfg.messages.map: m =>
      val wireContent = m.content.map(Helpers.toWireContentBlock)
      Wire.WireMessage(role = m.role, content = wireContent)
    val system = cfg.system match
      case null => Nil
      case s    => List(Wire.SystemContentBlock.Text(s))
    val inference =
      if cfg.inferenceConfig.asInstanceOf[AnyRef] eq null then None
      else Some(cfg.inferenceConfig.asInstanceOf[InferenceConfig])
    val tools =
      if cfg.toolConfig.asInstanceOf[AnyRef] eq null then None
      else Some(toWireToolConfig(cfg.toolConfig.asInstanceOf[ToolConfig]))
    Wire.ConverseRequest(
      messages        = messages,
      system          = system,
      inferenceConfig = inference,
      toolConfig      = tools,
      outputConfig    = outputConfig,
    )

  private def toWireToolConfig(tc: ToolConfig): Wire.ToolConfig =
    Wire.ToolConfig(
      tools = tc.tools.map: t =>
        Wire.ToolDef.ToolSpec(Wire.ToolSpecData(
          name        = t.name,
          description = Some(t.description),
          strict      = None,
          schema      = t.inputSchema,
        )),
      toolChoice = Some(tc.toolChoice match
        case ToolChoice.Auto       => Wire.ToolChoice.Auto(Wire.EmptyObject())
        case ToolChoice.Any        => Wire.ToolChoice.Any(Wire.EmptyObject())
        case ToolChoice.Tool(name) => Wire.ToolChoice.Tool(Wire.ToolChoiceTool(name))
      ),
    )

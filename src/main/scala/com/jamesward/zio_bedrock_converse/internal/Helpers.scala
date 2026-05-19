package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.http.endpoint.openapi.{JsonSchema as JS}

private[zio_bedrock_converse] object Helpers:

  def withStrictObjects(s: JS): JS =
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

  def fromWireToolUse(tu: Wire.ToolUseContent): ContentBlock.ToolUse =
    ContentBlock.ToolUse(
      toolUseId = tu.toolUseId,
      name      = tu.name,
      input     = new ToolInput(tu.input),
    )

  def fromWireToolResult(tr: Wire.ToolResultContent): ContentBlock.ToolResult =
    ContentBlock.ToolResult(
      toolUseId = tr.toolUseId,
      content   = tr.content.flatMap:
        case Wire.ToolResultBlock.Text(t) => List(ToolResultBlock.Text(t))
        case Wire.ToolResultBlock.Json(j) => List(ToolResultBlock.Json(new ToolInput(j)))
        case _                            => Nil,
      status    = tr.status match
        case None                                 => null
        case Some(Wire.ToolResultStatus.Success)  => ToolResultStatus.Success
        case Some(Wire.ToolResultStatus.Error)    => ToolResultStatus.Error
      ,
    )

  def toWireContentBlock(cb: ContentBlock): Wire.ContentBlock =
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

  def structuredOutputConfig[T](using schema: zio.schema.Schema[T]): Wire.OutputConfig =
    val rawJsonSchema = JS.fromZSchema(
      schema,
      JS.SchemaRef(JS.SchemaSpec.JsonSchema, JS.SchemaStyle.Inline),
    )
    val outputJsonSchema = withStrictObjects(rawJsonSchema).toJson
    Wire.OutputConfig(Wire.TextFormat.JsonSchema(
      Wire.JsonSchemaStructure(Wire.JsonSchemaSpec(
        schema = outputJsonSchema,
        name   = "structured_output",
      )),
    ))

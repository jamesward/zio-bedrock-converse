package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.Bedrock.*
import com.jamesward.zio_bedrock_converse.internal.{Codecs, Tools, Wire}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.json.*
import zio.json.ast.Json
import zio.schema.{Schema, derived}
import zio.schema.annotation.description
import zio.schema.codec.JsonCodec
import zio.schema.codec.json.schemaJson
import zio.stream.ZStream
import zio.test.*

object DynamicToolSpec extends ZIOSpecDefault:

  @description("A described query object.")
  case class DescribedQuery(
    @description("Text to search for.") query: String,
  ) derives Schema

  private def field(value: Json, name: String): Json =
    value.asObject.flatMap(_.get(name)).getOrElse(throw new AssertionError(s"missing field '$name' in $value"))

  private def encodedToolSpec(tool: Tool[?]): Json =
    val request = RequestConfig(
      messages = List(Message.user("test")),
      toolConfig = ToolConfig(List(tool)),
    )
    val wire = Tools.toWire(request, None)
    val json = JsonCodec.schemaBasedBinaryCodec[Wire.ConverseRequest](Codecs.codecConfig)
      .encode(wire)
      .asString
      .fromJson[Json]
      .toOption
      .get

    val toolConfig = field(json, "toolConfig")
    val tools = field(toolConfig, "tools").asArray.getOrElse(throw new AssertionError("tools was not an array"))
    field(tools.head, "toolSpec")

  private def encodedInputSchema(tool: Tool[?]): Json =
    field(field(encodedToolSpec(tool), "inputSchema"), "json")

  private def loopClient(requests: Ref[List[Wire.ConverseRequest]]): ULayer[Bedrock.Client] =
    ZLayer.fromZIO:
      Ref.make(0).map: calls =>
        new Bedrock.Client:
          val modelId: ModelId = ModelId("dynamic-loop-test")

          def send(request: Wire.ConverseRequest): IO[Bedrock.Error, Wire.ConverseResponse] =
            requests.update(_ :+ request) *> calls.getAndUpdate(_ + 1).map:
              case 0 =>
                Wire.ConverseResponse(
                  Wire.ConverseOutput(Wire.WireMessage(Role.Assistant, List(
                    Wire.ContentBlock.ReasoningContent(Wire.ReasoningContentBlock(
                      reasoningText = Some(Wire.ReasoningTextBlock("thinking", Some("signature-1"))),
                    )),
                    Wire.ContentBlock.ToolUse(Wire.ToolUseContent(
                      ToolUseId("call-1"),
                      ToolName("runtime_search"),
                      summon[Schema[Json]].toDynamic(Json.Obj("query" -> Json.Str("jackson"))),
                    )),
                  ))),
                  StopReason.ToolUse,
                  TokenUsage(10, 2, 12, 3, 4),
                  Metrics(5),
                )
              case _ =>
                Wire.ConverseResponse(
                  Wire.ConverseOutput(Wire.WireMessage(Role.Assistant, List(Wire.ContentBlock.Text("done")))),
                  StopReason.EndTurn,
                  TokenUsage(20, 3, 23, 5, null),
                  Metrics(7),
                )

          def sendStream(request: Wire.ConverseRequest) = ZStream.empty
          def sendStreamEvents(request: Wire.ConverseRequest) = ZStream.empty

  def spec = suite("dynamic tools")(
    test("dynamic name and description are forwarded") {
      val tool = Tool.dynamic(ToolName("runtime_search"), "Runtime-provided description", Json.Obj())
      val spec = encodedToolSpec(tool)
      assertTrue(
        field(spec, "name").asString.contains("runtime_search"),
        field(spec, "description").asString.contains("Runtime-provided description"),
      )
    },
    test("dynamicLoop manages history and aggregates every turn") {
      val schema = Json.Obj(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj("query" -> Json.Obj("type" -> Json.Str("string"))),
        "required" -> Json.Arr(Json.Str("query")),
      )
      for
        requests <- Ref.make(List.empty[Wire.ConverseRequest])
        seen <- Ref.make(Option.empty[(ToolName, Json.Obj)])
        result <- Bedrock.dynamicLoop(
          "search",
          List(Tool.dynamic(ToolName("runtime_search"), "Search", schema)),
        ) { (name, input) =>
          for
            arguments <- ZIO.fromEither(input.asJsonObject)
              .mapError(error => new IllegalArgumentException(error))
            _ <- seen.set(Some(name -> arguments))
          yield DynamicToolResult.text("raw tool result")
        }.maxIterations(5).text.provideLayer(loopClient(requests))
        captured <- requests.get
        invoked <- seen.get
      yield
        val history = captured.lift(1).map(_.messages).getOrElse(Nil)
        val reasoning = history.lift(1).toList.flatMap(_.content).collectFirst:
          case Wire.ContentBlock.ReasoningContent(value) => value
        val assistantId = history.lift(1).toList.flatMap(_.content).collectFirst:
          case Wire.ContentBlock.ToolUse(value) => value.toolUseId
        val resultId = history.lift(2).toList.flatMap(_.content).collectFirst:
          case Wire.ContentBlock.ToolResult(value) => value.toolUseId
        assertTrue(
          result.output == "done",
          result.turns.map(_.stopReason) == List(StopReason.ToolUse, StopReason.EndTurn),
          result.turns.headOption.exists(_.toolNames == List(ToolName("runtime_search"))),
          result.totals.usage.inputTokens == 30,
          result.totals.usage.outputTokens == 5,
          result.totals.usage.totalTokens == 35,
          result.totals.usage.cacheReadInputTokens.asInstanceOf[Int] == 8,
          result.totals.usage.cacheWriteInputTokens.asInstanceOf[Int] == 4,
          result.totals.latencyMs == 12,
          invoked.exists((name, args) => name == ToolName("runtime_search") && args.get("query").flatMap(_.asString).contains("jackson")),
          history.map(_.role) == List(Role.User, Role.Assistant, Role.User),
          reasoning.flatMap(_.reasoningText).exists(block => block.text == "thinking" && block.signature.contains("signature-1")),
          assistantId.contains(ToolUseId("call-1")),
          resultId == assistantId,
        )
    },
    test("dynamic JSON Schema is forwarded verbatim") {
      val rawSchema = Json.Obj(Chunk(
        "type" -> Json.Str("object"),
        "properties" -> Json.Obj(Chunk(
          "query" -> Json.Obj(Chunk(
            "type" -> Json.Str("string"),
            "x-vendor-keyword" -> Json.Bool(true),
          )),
        )),
        "required" -> Json.Arr(Chunk(Json.Str("query"))),
        "additionalProperties" -> Json.Bool(false),
      ))
      val encoded = encodedInputSchema(Tool.dynamic(ToolName("search"), "Search", rawSchema))
      assertTrue(encoded.toJson == rawSchema.toJson)
    },
    test("annotated typed object schema is not wrapped under value") {
      val encoded = encodedInputSchema(Tool[DescribedQuery](ToolName("search"), "Search"))
      val properties = encoded.asObject
        .flatMap(_.get("properties"))
        .flatMap(_.asObject)
        .getOrElse(Json.Obj())
      assertTrue(properties.get("query").nonEmpty, properties.get("value").isEmpty)
    },
    test("ToolInput exposes arbitrary model arguments as Json.Obj") {
      val arguments = Json.Obj(Chunk(
        "query" -> Json.Str("jackson-databind"),
        "limit" -> Json.Num(10),
        "nested" -> Json.Obj(Chunk("enabled" -> Json.Bool(true))),
      ))
      val input = new ToolInput(summon[Schema[Json]].toDynamic(arguments))
      assertTrue(input.asJsonObject.map(_.toJson) == Right(arguments.toJson))
    },
  )

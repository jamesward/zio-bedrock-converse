package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.Bedrock.{Error, Metrics, ModelId, Role, StopReason, TokenUsage, ToolName, ToolUseId}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import com.jamesward.zio_bedrock_converse.internal.{Codecs, Wire}
import zio.*
import zio.schema.Schema
import zio.schema.codec.JsonCodec
import zio.stream.*

import scala.collection.immutable.Queue

/**
 * Test-side mock for `Bedrock.Client`. Scripts the model's behaviour as
 * a queue of [[MockBehavior]]s; each `send` consumes one behavior and
 * builds the matching wire response.
 *
 * Lives in `src/test` so the production jar carries no test-only code.
 */
object BedrockMock:

  /** A scripted model behavior. Each `send` round consumes one. */
  sealed trait MockBehavior

  object MockBehavior:
    /** The model produces a final text response (`stopReason = end_turn`). */
    case class Reply(text: String) extends MockBehavior

    /** The model produces a JSON response that decodes to `value` —
      * used for structured-output tests. */
    case class ReplyJson[T: Schema](value: T) extends MockBehavior:
      val schema: Schema[T] = summon[Schema[T]]

    /** The model invokes a tool. Surfaces `stopReason = tool_use`. */
    case class CallTool[I: Schema](toolName: ToolName, input: I) extends MockBehavior:
      val schema: Schema[I] = summon[Schema[I]]

    /** The model fails the converse call with the given error. */
    case class Fail(error: Error) extends MockBehavior

  /** A `Bedrock.Client` layer whose responses come from `behaviors`. */
  def apply(behaviors: MockBehavior*): ULayer[Bedrock.Client] =
    ZLayer.fromZIO:
      Ref.make(Queue.from(behaviors.toIndexedSeq)).map: ref =>
        new Bedrock.Client:
          val modelId: ModelId = ModelId("mock")
          def send(req: Wire.ConverseRequest): IO[Error, Wire.ConverseResponse] =
            respond(ref)
          def sendStream(req: Wire.ConverseRequest): ZStream[Any, Error, String] =
            ZStream.fromZIO(respond(ref)).map: wire =>
              wire.output.message.content.collect:
                case Wire.ContentBlock.Text(t) => t
              .mkString
            .flatMap(ZStream.fromIterable(_).map(_.toString))
          def sendStreamEvents(req: Wire.ConverseRequest): ZStream[Any, Error, Bedrock.StreamEvent] =
            ZStream.fromZIO(respond(ref)).flatMap: wire =>
              val textEvents = wire.output.message.content.collect:
                case Wire.ContentBlock.Text(t) => Bedrock.StreamEvent.TextDelta(t)
              val stopEvent = Bedrock.StreamEvent.MessageStop(wire.stopReason)
              val metaEvent = Bedrock.StreamEvent.Metadata(wire.usage, wire.metrics)
              ZStream.fromIterable(textEvents :+ stopEvent :+ metaEvent)

  private def respond(ref: Ref[Queue[MockBehavior]]):
      IO[Error, Wire.ConverseResponse] =
    ref.modify:
      case q if q.isEmpty => (None, q)
      case q              =>
        val (head, rest) = q.dequeue
        (Some(head), rest)
    .flatMap:
      case None => ZIO.fail(Error.Unexpected(
        zio.http.Status.InternalServerError,
        "Mock script exhausted: more `send` rounds than scripted behaviors",
      ))
      case Some(MockBehavior.Reply(text)) =>
        ZIO.succeed(wrap(Wire.ContentBlock.Text(text), StopReason.EndTurn))
      case Some(b: MockBehavior.ReplyJson[t]) =>
        val codec = JsonCodec.schemaBasedBinaryCodec[t](Codecs.codecConfig)(using b.schema)
        val json  = new String(codec.encode(b.value).toArray, java.nio.charset.StandardCharsets.UTF_8)
        ZIO.succeed(wrap(Wire.ContentBlock.Text(json), StopReason.EndTurn))
      case Some(b: MockBehavior.CallTool[i]) =>
        val tu = Wire.ToolUseContent(
          toolUseId = ToolUseId(java.util.UUID.randomUUID().toString),
          name      = b.toolName,
          input     = b.schema.toDynamic(b.input),
        )
        ZIO.succeed(wrap(Wire.ContentBlock.ToolUse(tu), StopReason.ToolUse))
      case Some(MockBehavior.Fail(error)) =>
        ZIO.fail(error)

  private def wrap(
    block:      Wire.ContentBlock,
    stopReason: StopReason,
  ): Wire.ConverseResponse =
    Wire.ConverseResponse(
      output     = Wire.ConverseOutput(Wire.WireMessage(
        role    = Role.Assistant,
        content = List(block),
      )),
      stopReason = stopReason,
      usage      = TokenUsage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
      metrics    = Metrics(latencyMs = 0L),
    )

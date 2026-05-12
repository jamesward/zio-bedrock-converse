package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.BedrockConverse.{ConverseError, ConverseMetrics, ModelId, Role, StopReason, TokenUsage, ToolName, ToolUseId}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import com.jamesward.zio_bedrock_converse.internal.{Codecs, Wire}
import zio.*
import zio.schema.codec.JsonCodec
import zio.schema.{DynamicValue, Schema}

import scala.collection.immutable.Queue

/**
 * Test-side mock for `BedrockConverse`. Scripts the model's behaviour as
 * a queue of [[MockBehavior]]s; each round of the converse loop consumes
 * one behavior and builds the matching wire response.
 *
 * Lives in `src/test` so the production jar carries no test-only code.
 */
object BedrockConverseMock:

  /** A scripted model behavior. Each `converse` round consumes one. */
  sealed trait MockBehavior

  object MockBehavior:
    /** The model produces a final text response (`stopReason = end_turn`). */
    case class Reply(text: String) extends MockBehavior

    /** The model produces a JSON response that decodes to `value` —
      * used for `converseStructured`. */
    case class ReplyJson[T: Schema](value: T) extends MockBehavior:
      val schema: Schema[T] = summon[Schema[T]]

    /** The model invokes a tool. The dispatcher runs it, then consumes
      * the next behavior for the follow-up turn. */
    case class CallTool[I: Schema](toolName: ToolName, input: I) extends MockBehavior:
      val schema: Schema[I] = summon[Schema[I]]

    /** The model fails the converse call with the given error. */
    case class Fail(error: ConverseError) extends MockBehavior

  /** A `BedrockConverse` layer whose responses come from `behaviors`. */
  def apply(behaviors: MockBehavior*): ULayer[BedrockConverse] =
    ZLayer.fromZIO:
      Ref.make(Queue.from(behaviors.toIndexedSeq)).map: ref =>
        new BedrockConverse(_ => respond(ref), ModelId("mock"))

  private def respond(ref: Ref[Queue[MockBehavior]]):
      IO[ConverseError, Wire.ConverseResponse[DynamicValue]] =
    ref.modify:
      case q if q.isEmpty => (None, q)
      case q              =>
        val (head, rest) = q.dequeue
        (Some(head), rest)
    .flatMap:
      case None => ZIO.fail(ConverseError.Unexpected(
        zio.http.Status.InternalServerError,
        "Mock script exhausted: more `converse` rounds than scripted behaviors",
      ))
      case Some(MockBehavior.Reply(text)) =>
        ZIO.succeed(wrap(Wire.ContentBlock.Text(text), StopReason.EndTurn))
      case Some(b: MockBehavior.ReplyJson[t]) =>
        val codec = JsonCodec.schemaBasedBinaryCodec[t](Codecs.codecConfig)(using b.schema)
        val json  = new String(codec.encode(b.value).toArray, java.nio.charset.StandardCharsets.UTF_8)
        ZIO.succeed(wrap(Wire.ContentBlock.Text(json), StopReason.EndTurn))
      case Some(b: MockBehavior.CallTool[i]) =>
        val dv = b.schema.toDynamic(b.input)
        val tu = Wire.ToolUseContent[DynamicValue](
          toolUseId = ToolUseId(java.util.UUID.randomUUID().toString),
          name      = b.toolName,
          input     = dv,
        )
        ZIO.succeed(wrap(Wire.ContentBlock.ToolUse(tu), StopReason.ToolUse))
      case Some(MockBehavior.Fail(error)) =>
        ZIO.fail(error)

  private def wrap(
    block:      Wire.ContentBlock[DynamicValue],
    stopReason: StopReason,
  ): Wire.ConverseResponse[DynamicValue] =
    Wire.ConverseResponse(
      output     = Wire.ConverseOutput(Wire.WireMessage[DynamicValue](
        role    = Role.Assistant,
        content = List(block),
      )),
      stopReason = stopReason,
      usage      = TokenUsage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
      metrics    = ConverseMetrics(latencyMs = 0L),
    )

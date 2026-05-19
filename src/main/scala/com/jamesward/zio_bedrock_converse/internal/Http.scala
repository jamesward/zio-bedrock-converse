package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.{ApiKey, Error, ModelId, Region}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec
import zio.stream.*

/**
 * HTTP-level transport. Builds a `Bedrock.Client` whose `send` is rooted
 * at the Bedrock-runtime endpoint and authed with the bearer token.
 */
private[zio_bedrock_converse] object Http:

  /** Build an authed `Bedrock.Client` whose `send` makes real HTTP calls. */
  def buildClient(
    apiKey:  ApiKey,
    region:  Region,
    modelId: ModelId,
    client:  Client,
  ): Bedrock.Client =
    val base = URL.decode(s"https://bedrock-runtime.${region.code}.amazonaws.com").toOption.get
    val authedClient = client
      .url(base)
      .addHeader(Header.Authorization.Bearer(apiKey.unwrap))
      .addHeader(Header.ContentType(MediaType.application.json))
    new HttpClient(modelId, authedClient)

  /** Live HTTP-backed `Bedrock.Client`. Lives here (in `internal`) so it
    * has access to `Wire.*` and `Bedrock.Client#send`'s package-private
    * surface. */
  private final class HttpClient(
    val modelId:     ModelId,
    private val hc:  Client,
  ) extends Bedrock.Client:

    private val reqCodec  = JsonCodec.schemaBasedBinaryCodec[Wire.ConverseRequest](Codecs.codecConfig)
    private val respCodec = JsonCodec.schemaBasedBinaryCodec[Wire.ConverseResponse](Codecs.codecConfig)
    private val path      = s"/model/${pathEncode(modelId.unwrap)}/converse"
    private val streamPath = s"/model/${pathEncode(modelId.unwrap)}/converse-stream"

    def send(req: Wire.ConverseRequest): IO[Error, Wire.ConverseResponse] =
      val body = Body.fromChunk(reqCodec.encode(req))
      ZIO.scoped:
        hc.post(path)(body)
          .mapError(Error.Transport.apply)
          .flatMap: response =>
            val status = response.status
            if status.isSuccess then
              response.body.asChunk
                .mapError(Error.Transport.apply)
                .flatMap: bytes =>
                  ZIO.fromEither(respCodec.decode(bytes))
                    .mapError(de =>
                      Error.Unexpected(status, s"Decode failed: ${de.message}"))
            else
              response.body.asString.orDie.flatMap: text =>
                ZIO.fail(Error.fromStatus(status, text))

    def sendStream(req: Wire.ConverseRequest): ZStream[Any, Error, String] =
      sendStreamEvents(req).collect:
        case Bedrock.StreamEvent.TextDelta(text) => text

    def sendStreamEvents(req: Wire.ConverseRequest): ZStream[Any, Error, Bedrock.StreamEvent] =
      val body = Body.fromChunk(reqCodec.encode(req))
      ZStream.unwrapScoped:
        hc.post(streamPath)(body)
          .mapError(Error.Transport.apply)
          .flatMap: response =>
            if response.status.isSuccess then
              ZIO.succeed:
                response.body.asStream
                  .mapError(Error.Transport.apply)
                  .via(AwsEventStream.parseFrames)
                  .mapConcat(toStreamEvents)
            else
              response.body.asString.orDie.flatMap: text =>
                ZIO.fail(Error.fromStatus(response.status, text))

  /** Model IDs flow into the URL path literally. AWS rejects over-encoded
    * paths (`%3A` instead of `:`); the only character we still escape
    * defensively is space. */
  private def pathEncode(s: String): String = s.replace(" ", "%20")

  /** Extract the text delta from a ConverseStream JSON payload.
    * Returns `Some(text)` for `contentBlockDelta` events with a text
    * delta, `None` for everything else. */
  /** Convert a raw AWS event stream event into zero or more public
    * `Bedrock.StreamEvent`s. */
  private def toStreamEvents(e: AwsEventStream.Event): List[Bedrock.StreamEvent] =
    import Bedrock.StreamEvent.*
    e.eventType match
      case "contentBlockDelta" =>
        // Check for text delta
        extractTextDelta(e.payload) match
          case Some(text) => List(TextDelta(text))
          case None =>
            // Check for reasoningContent delta
            // Shape: {"contentBlockIndex":0,"delta":{"reasoningContent":{"text":"…"}}}
            if e.payload.contains("\"reasoningContent\"") then
              extractJsonString(e.payload, "text") match
                case Some(text) => List(ReasoningDelta(text))
                case None       => Nil
            else
              // Check for toolUse delta (incremental input JSON)
              extractJsonString(e.payload, "input") match
                case Some(input) =>
                  List(ToolUseDelta(Bedrock.ToolUseId(""), input))
                case None => Nil
      case "contentBlockStart" =>
        // {"contentBlockIndex":0,"start":{"toolUse":{"toolUseId":"…","name":"…"}}}
        val toolUseId = extractJsonString(e.payload, "toolUseId")
        val name = extractJsonString(e.payload, "name")
        (toolUseId, name) match
          case (Some(id), Some(n)) =>
            List(ToolUseStart(Bedrock.ToolUseId(id), Bedrock.ToolName(n)))
          case _ => Nil
      case "contentBlockStop" =>
        val idx = extractJsonInt(e.payload, "contentBlockIndex").getOrElse(0)
        List(ContentBlockStop(idx))
      case "messageStop" =>
        val sr = if e.payload.contains("\"end_turn\"") then Bedrock.StopReason.EndTurn
          else if e.payload.contains("\"tool_use\"") then Bedrock.StopReason.ToolUse
          else if e.payload.contains("\"max_tokens\"") then Bedrock.StopReason.MaxTokens
          else Bedrock.StopReason.EndTurn
        List(MessageStop(sr))
      case "metadata" =>
        val inputTokens = extractJsonInt(e.payload, "inputTokens").getOrElse(0)
        val outputTokens = extractJsonInt(e.payload, "outputTokens").getOrElse(0)
        val totalTokens = extractJsonInt(e.payload, "totalTokens").getOrElse(0)
        val latencyMs = extractJsonInt(e.payload, "latencyMs").map(_.toLong).getOrElse(0L)
        List(Metadata(
          Bedrock.TokenUsage(inputTokens, outputTokens, totalTokens),
          Bedrock.Metrics(latencyMs),
        ))
      case _ => Nil

  /** Extract a JSON string value by key (minimal parser). */
  private def extractJsonString(json: String, key: String): Option[String] =
    val marker = s"\"$key\":\""
    val idx = json.indexOf(marker)
    if idx < 0 then None
    else
      val start = idx + marker.length
      val sb = new StringBuilder
      var i = start
      var escaped = false
      while i < json.length do
        val c = json.charAt(i)
        if escaped then
          sb.append(c); escaped = false
        else if c == '\\' then escaped = true
        else if c == '"' then return Some(sb.toString)
        else sb.append(c)
        i += 1
      None

  /** Extract a JSON integer value by key (minimal parser). */
  private def extractJsonInt(json: String, key: String): Option[Int] =
    val marker = s"\"$key\":"
    val idx = json.indexOf(marker)
    if idx < 0 then None
    else
      val start = idx + marker.length
      val sb = new StringBuilder
      var i = start
      while i < json.length && (json.charAt(i).isDigit || json.charAt(i) == '-') do
        sb.append(json.charAt(i))
        i += 1
      if sb.isEmpty then None else scala.util.Try(sb.toString.toInt).toOption

  private def extractTextDelta(json: String): Option[String] =
    // The payload shape for text deltas is:
    //   {"contentBlockIndex":0,"delta":{"text":"…"},"p":"…"}
    if json.contains("\"delta\"") && json.contains("\"text\"") then
      val deltaIdx = json.indexOf("\"delta\"")
      if deltaIdx < 0 then return None
      val marker = "\"text\":\""
      val idx = json.indexOf(marker, deltaIdx)
      if idx >= 0 then
        val start = idx + marker.length
        val sb = new StringBuilder
        var i = start
        var escaped = false
        while i < json.length do
          val c = json.charAt(i)
          if escaped then
            c match
              case '"'  => sb.append('"')
              case '\\' => sb.append('\\')
              case '/'  => sb.append('/')
              case 'n'  => sb.append('\n')
              case 'r'  => sb.append('\r')
              case 't'  => sb.append('\t')
              case _    => sb.append('\\'); sb.append(c)
            escaped = false
          else if c == '\\' then
            escaped = true
          else if c == '"' then
            return Some(sb.toString)
          else
            sb.append(c)
          i += 1
        None
      else None
    else None

  /** AWS Event Stream binary protocol parser, specialized for Bedrock
    * ConverseStream. Parses the binary framing into events with their
    * `:event-type` header and JSON payload. */
  private[zio_bedrock_converse] object AwsEventStream:

    case class Event(eventType: String, payload: String)

    /** ZPipeline that accumulates raw bytes and emits parsed `Event`s. */
    val parseFrames: ZPipeline[Any, Error, Byte, Event] =
      ZPipeline.suspend:
        var buffer = Chunk.empty[Byte]
        ZPipeline.mapChunks[Byte, Event]: incoming =>
          buffer = buffer ++ incoming
          val (events, remaining) = parseAll(buffer)
          buffer = remaining
          events

    /** Parse as many complete frames as possible from the buffer.
      * Returns (parsed events, leftover bytes). */
    def parseAll(data: Chunk[Byte]): (Chunk[Event], Chunk[Byte]) =
      val events = Chunk.newBuilder[Event]
      var pos = 0
      val arr = data.toArray
      while pos + 12 <= arr.length do  // minimum: 4 (total) + 4 (headers) + 4 (prelude CRC)
        val totalLen = readInt(arr, pos)
        if pos + totalLen > arr.length then
          // incomplete frame
          return (events.result(), Chunk.fromArray(arr.drop(pos)))
        val headersLen = readInt(arr, pos + 4)
        // skip prelude CRC (4 bytes at pos+8)
        val headersStart = pos + 12
        val headersEnd = headersStart + headersLen
        val payloadStart = headersEnd
        val payloadEnd = pos + totalLen - 4  // exclude message CRC

        val headers = parseHeaders(arr, headersStart, headersEnd)
        val eventType = headers.getOrElse(":event-type", "unknown")
        val payload = if payloadEnd > payloadStart then
          new String(arr, payloadStart, payloadEnd - payloadStart, java.nio.charset.StandardCharsets.UTF_8)
        else ""

        events += Event(eventType, payload)
        pos += totalLen

      (events.result(), if pos < arr.length then Chunk.fromArray(arr.drop(pos)) else Chunk.empty)

    /** Parse headers from the binary header block. Only handles type 7
      * (String) which is all Bedrock uses. */
    private def parseHeaders(arr: Array[Byte], start: Int, end: Int): Map[String, String] =
      val result = scala.collection.mutable.Map.empty[String, String]
      var pos = start
      while pos < end do
        val nameLen = (arr(pos) & 0xFF)
        pos += 1
        val name = new String(arr, pos, nameLen, java.nio.charset.StandardCharsets.UTF_8)
        pos += nameLen
        val headerType = arr(pos) & 0xFF
        pos += 1
        if headerType == 7 then // String type
          val valueLen = ((arr(pos) & 0xFF) << 8) | (arr(pos + 1) & 0xFF)
          pos += 2
          val value = new String(arr, pos, valueLen, java.nio.charset.StandardCharsets.UTF_8)
          pos += valueLen
          result(name) = value
        else
          // Skip unknown header types — shouldn't happen for Bedrock
          // but be defensive. Most types have a fixed or length-prefixed size.
          headerType match
            case 0 => pos += 1  // bool true
            case 1 => pos += 1  // bool false
            case 2 => pos += 1  // byte
            case 3 => pos += 2  // short
            case 4 => pos += 4  // int
            case 5 => pos += 8  // long
            case 6 => // bytes: 2-byte length + data
              val len = ((arr(pos) & 0xFF) << 8) | (arr(pos + 1) & 0xFF)
              pos += 2 + len
            case 8 => pos += 8  // timestamp
            case 9 => pos += 16 // uuid
            case _ => pos = end // bail
      result.toMap

    private def readInt(arr: Array[Byte], offset: Int): Int =
      ((arr(offset) & 0xFF) << 24) |
      ((arr(offset + 1) & 0xFF) << 16) |
      ((arr(offset + 2) & 0xFF) << 8) |
      (arr(offset + 3) & 0xFF)

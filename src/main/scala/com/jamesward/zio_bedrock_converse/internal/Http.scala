package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.BedrockConverse
import com.jamesward.zio_bedrock_converse.BedrockConverse.{ApiKey, ConverseError, ModelId, Region}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.http.*
import zio.schema.DynamicValue
import zio.schema.codec.JsonCodec

/**
 * HTTP-level transport: builds an authed `Client` rooted at a Bedrock-runtime
 * endpoint and produces a `Sender` function that the [[BedrockConverse]]
 * service uses to round-trip a single wire request.
 */
private[zio_bedrock_converse] object Http:

  /** Build an authed `BedrockConverse` whose `send` makes real HTTP calls. */
  def buildService(
    apiKey:  ApiKey,
    region:  Region,
    modelId: ModelId,
    client:  Client,
  ): BedrockConverse =
    val base = URL.decode(s"https://bedrock-runtime.${region.code}.amazonaws.com").toOption.get
    val authedClient = client
      .url(base)
      .addHeader(Header.Authorization.Bearer(apiKey.unwrap))
      .addHeader(Header.ContentType(MediaType.application.json))
    new BedrockConverse(send(authedClient, modelId), modelId)

  private def send(client: Client, modelId: ModelId): BedrockConverse.Sender =
    val reqCodec  = JsonCodec.schemaBasedBinaryCodec[Wire.ConverseRequest[DynamicValue]](Codecs.codecConfig)
    val respCodec = JsonCodec.schemaBasedBinaryCodec[Wire.ConverseResponse[DynamicValue]](Codecs.codecConfig)
    val path      = s"/model/${pathEncode(modelId.unwrap)}/converse"

    req =>
      val body = Body.fromChunk(reqCodec.encode(req))
      ZIO.scoped:
        client
          .post(path)(body)
          .mapError(ConverseError.Transport.apply)
          .flatMap: response =>
            val status = response.status
            if status.isSuccess then
              response.body.asChunk
                .mapError(ConverseError.Transport.apply)
                .flatMap: bytes =>
                  ZIO.fromEither(respCodec.decode(bytes))
                    .mapError(de =>
                      ConverseError.Unexpected(status, s"Decode failed: ${de.message}"))
            else
              response.body.asString.orDie.flatMap: text =>
                ZIO.fail(ConverseError.fromStatus(status, text))

  /** Model IDs flow into the URL path literally. AWS rejects over-encoded
    * paths (`%3A` instead of `:`); the only character we still escape
    * defensively is space. */
  private def pathEncode(s: String): String = s.replace(" ", "%20")

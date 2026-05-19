package com.jamesward.zio_bedrock_converse.internal

import com.jamesward.zio_bedrock_converse.Bedrock
import com.jamesward.zio_bedrock_converse.Bedrock.{ApiKey, Error, ModelId, Region}
import com.jamesward.zio_bedrock_converse.internal.Codecs.given
import zio.*
import zio.http.*
import zio.schema.codec.JsonCodec

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

  /** Model IDs flow into the URL path literally. AWS rejects over-encoded
    * paths (`%3A` instead of `:`); the only character we still escape
    * defensively is space. */
  private def pathEncode(s: String): String = s.replace(" ", "%20")

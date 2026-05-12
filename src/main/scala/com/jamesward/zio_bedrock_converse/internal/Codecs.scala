package com.jamesward.zio_bedrock_converse.internal

import zio.http.endpoint.openapi.JsonSchema
import zio.schema.annotation.directDynamicMapping
import zio.schema.codec.JsonCodec
import zio.schema.{DynamicValue, Schema, derived}

/**
 * Codec-level helpers shared by the public data types in
 * [[com.jamesward.zio_bedrock_converse.BedrockConverse]].
 *
 * Imported into `BedrockConverse.scala` via `import internal.Codecs.given`
 * so the `derives Schema` macros find `Schema[T | Null]` and
 * `Schema[Nothing]` instances at use sites.
 */
private[zio_bedrock_converse] object Codecs:

  /** A no-op `Schema[Nothing]`. Needed only because covariant enum cases
    * without `T`-typed fields infer `[Nothing]`; the schema is never
    * actually invoked since `Nothing` is uninhabited. Using `Schema.fail`
    * here would cause `@noDiscriminator` enum decoders to spuriously
    * reject otherwise-valid responses, so we delegate to `Unit`. */
  given Schema[Nothing] = Schema.primitive[Unit].asInstanceOf[Schema[Nothing]]

  /** `Schema[DynamicValue]` annotated with `@directDynamicMapping` so that
    * `JsonCodec` encodes / decodes it as natural JSON rather than as the
    * structured `DynamicValue` ADT. Required for tool-use input fields
    * (arbitrary JSON the model produces). */
  given Schema[DynamicValue] = Schema.dynamicValue.annotate(directDynamicMapping())

  /** Round-trip `T | Null` through `Schema.Optional[T]` so the wire codec
    * treats `null` exactly like `Option[T] = None`: the field is dropped on
    * encode and missing fields decode as `null`. `eq null` is used in the
    * reverse direction to dodge strict-equality friction over `Null`. */
  given nullableSchema[T: Schema]: Schema[T | Null] =
    Schema.Optional(summon[Schema[T]]).transform[T | Null](
      {
        case Some(v) => v: T | Null
        case None    => null
      },
      v =>
        if v.asInstanceOf[AnyRef] eq null then None
        else Some(v.asInstanceOf[T]),
    )

  /** Both nullable scalars (`T | Null`) and collection defaults (`Nil`,
    * `Map.empty`) are intended to mean "no value provided" — drop them from
    * the wire payload rather than emitting `null` / `[]` / `{}`. */
  val codecConfig: JsonCodec.Configuration =
    JsonCodec.Configuration(
      explicitEmptyCollections = JsonCodec.ExplicitConfig(encoding = false, decoding = false),
      explicitNulls            = JsonCodec.ExplicitConfig(encoding = false, decoding = false),
    )

  /** Wire envelope for the `inputSchema.json` slot in Bedrock's tool
    * configuration. Stays internal — users build a `ToolSpecData[I]` from a
    * `Schema[I]` and never construct this themselves. */
  case class InputSchema(json: JsonSchema) derives Schema

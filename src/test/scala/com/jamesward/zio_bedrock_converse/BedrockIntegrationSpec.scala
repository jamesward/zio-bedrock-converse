package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.http.Client
import zio.test.*
import zio.test.TestAspect.*

/**
 * Single-turn `Bedrock.converse` scenarios run against the live Bedrock
 * service. Gated by `AWS_BEARER_TOKEN_BEDROCK`; skipped without it.
 *
 * Env overrides:
 *  - `BEDROCK_TEST_MODEL_ID` — defaults to a recent Claude Sonnet
 *    inference profile.
 *  - `AWS_REGION`            — defaults to `us-east-1`.
 */
object BedrockIntegrationSpec extends ZIOSpecDefault:

  private val defaultModelId = "us.anthropic.claude-sonnet-4-5-20250929-v1:0"

  private def envOr(name: String, default: String): String =
    Option(java.lang.System.getenv(name)).getOrElse(default)

  private val testModelId: ModelId = ModelId(envOr("BEDROCK_TEST_MODEL_ID", defaultModelId))
  private val testRegion:  Region  =
    Region.fromCode(envOr("AWS_REGION", Region.UsEast1.code)).getOrElse(Region.UsEast1)
  private val testApiKey:  ApiKey  = ApiKey(envOr("AWS_BEARER_TOKEN_BEDROCK", ""))

  private val testLayer: ZLayer[Client, Nothing, Bedrock.Client] =
    Bedrock.Client.layer(testApiKey, testRegion, testModelId)

  private def asTest(s: SharedSpec.BedrockScenario): Spec[Bedrock.Client, Any] =
    test(s.name)(s.run)

  def spec = suite("Bedrock Integration")(
    (SharedSpec.bedrockScenarios
      ++ SharedSpec.bedrockRequestScenarios
      ++ SharedSpec.bedrockLoopIntegrationScenarios).map(asTest)*
  ).provideSomeShared[Scope](Client.default, testLayer)
    @@ ifEnvSet("AWS_BEARER_TOKEN_BEDROCK")
    @@ withLiveClock
    @@ withLiveSystem
    @@ timeout(90.seconds)
    @@ sequential

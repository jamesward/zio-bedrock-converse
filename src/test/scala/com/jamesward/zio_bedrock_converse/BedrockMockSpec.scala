package com.jamesward.zio_bedrock_converse

import zio.*
import zio.test.*

/**
 * Single-turn `Bedrock.converse` scenarios run against the deterministic
 * mock. No tools, no loop — just text and structured output (plus the
 * tool_use wire-response shape via the mock-only list).
 */
object BedrockMockSpec extends ZIOSpecDefault:

  private def asTest(s: SharedSpec.BedrockScenario): Spec[Any, Any] =
    test(s.name):
      s.run.provideLayer(BedrockMock(s.mockScript*))

  def spec = suite("Bedrock Mock")(
    (SharedSpec.bedrockScenarios
      ++ SharedSpec.bedrockRequestScenarios
      ++ SharedSpec.bedrockRequestMockOnlyScenarios).map(asTest)*
  )

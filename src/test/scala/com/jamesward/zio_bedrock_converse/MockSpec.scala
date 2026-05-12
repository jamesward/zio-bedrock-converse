package com.jamesward.zio_bedrock_converse

import zio.*
import zio.test.*

/**
 * Unit tests: each scenario runs against `BedrockConverseMock(...)`
 * scripted with the scenario's expected model behaviour. Deterministic;
 * runs without credentials.
 */
object MockSpec extends ZIOSpecDefault:

  private def asTest(s: SharedSpec.Scenario): Spec[Any, Any] =
    test(s.name):
      s.run.provideLayer(BedrockConverseMock(s.mockScript*))

  def spec = suite("Mock")(SharedSpec.all.map(asTest)*)

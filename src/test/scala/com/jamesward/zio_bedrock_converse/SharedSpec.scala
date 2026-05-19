package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.Bedrock.*
import zio.*
import zio.direct.*
import zio.schema.{Schema, derived}
import zio.test.{TestResult, ZIOSpecDefault, assertCompletes, assertNever, assertTrue}

/**
 * Scenario definitions shared between mock and integration specs.
 *
 * Single-turn `Bedrock.converse` scenarios only — the multi-turn tool
 * dispatch loop is set aside in this slice and will return as
 * `Bedrock.loop`. Each scenario carries a `mockScript` (the model's
 * scripted reply queue) and a `run` body that asserts against the
 * response.
 */
object SharedSpec:

  // ---------- Shared fixtures ----------

  case class WeatherInput(city: String) derives Schema
  case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema
  case class Forecast(city: String, summary: String) derives Schema

  /** Compile-time-named tool spec for the weather example. */
  val weatherTool: Tool[WeatherInput] =
    Tool[WeatherInput](ToolName("getWeather"), "Get the weather for a US city.")

  // ---------- Bedrock.converse scenarios (single-turn) ----------

  trait BedrockScenario:
    def name: String
    def run: ZIO[Bedrock.Client, Any, TestResult]
    def mockScript: List[BedrockMock.MockBehavior]

  val textOnly: BedrockScenario = new BedrockScenario:
    val name = "text-only request returns assistant text"
    val mockScript = List(BedrockMock.MockBehavior.Reply("hello"))
    def run = defer:
      val text = Bedrock.converse("Say hello.").text.run
      assertTrue(text.nonEmpty)

  val asResponse: BedrockScenario = new BedrockScenario:
    val name = ".asResponse exposes stopReason and usage"
    val mockScript = List(BedrockMock.MockBehavior.Reply("hello"))
    def run = defer:
      val resp = Bedrock.converse("Say hello.").asResponse.run
      assertTrue(
        resp.output.text.toLowerCase.contains("hello"),
        resp.stopReason == StopReason.EndTurn,
      )

  val structuredOutput: BedrockScenario = new BedrockScenario:
    val name = ".as[Forecast] decodes JSON into a typed case class"
    val mockScript = List(
      BedrockMock.MockBehavior.ReplyJson(Forecast(
        city    = "Seattle",
        summary = "Rainy with intermittent drizzle.",
      )),
    )
    def run = defer:
      val forecast = Bedrock.converse(
        "Give a one-sentence weather forecast for Seattle.",
      ).as[Forecast].run
      assertTrue(
        forecast.city.toLowerCase.contains("seattle"),
        forecast.summary.nonEmpty,
      )

  val structuredAsResponse: BedrockScenario = new BedrockScenario:
    val name = ".asResponse[Forecast] exposes typed output + metadata"
    val mockScript = List(
      BedrockMock.MockBehavior.ReplyJson(Forecast(
        city    = "Seattle",
        summary = "Cloudy.",
      )),
    )
    def run = defer:
      val resp = Bedrock.converse("Forecast Seattle.").asResponse[Forecast].run
      assertTrue(
        resp.output.city.toLowerCase.contains("seattle"),
        resp.stopReason == StopReason.EndTurn,
      )

  /** Send a `RequestConfig` with `toolConfig`, observe a `ToolUse`
    * response, run the tool ourselves, send a follow-up `RequestConfig`
    * with the matching `ToolResult`, and observe the final assistant
    * text. */
  val toolCallRoundTrip: BedrockScenario = new BedrockScenario:
    val name = "tool call round-trip: send toolConfig → ToolUse → ToolResult → final text"
    val mockScript = List(
      BedrockMock.MockBehavior.CallTool(ToolName("getWeather"), WeatherInput("San Francisco")),
      BedrockMock.MockBehavior.Reply("It is 64F and foggy in San Francisco."),
    )

    def run =
      val initial = RequestConfig(
        messages   = List(Message.user("What is the weather in San Francisco?")),
        toolConfig = ToolConfig(tools = List(weatherTool)),
      )
      Bedrock.converse(initial).asResponse.flatMap: first =>
        val toolUseOpt = first.output.message.content.collectFirst:
          case ContentBlock.ToolUse(id, name, input) => (id, name, input)
        val (toolUseId, toolName, toolInputBlock) = toolUseOpt
          .getOrElse(throw new AssertionError("expected a ToolUse block"))
        val decodedInput: WeatherInput = toolInputBlock.as[WeatherInput].toOption
          .getOrElse(throw new AssertionError("expected ToolInput to decode as WeatherInput"))

        val answer = WeatherOutput(temperatureF = 64, conditions = "foggy")

        val followup = RequestConfig(
          messages = initial.messages
            :+ first.output.message
            :+ Message(Role.User, List(ContentBlock.ToolResult(
              toolUseId = toolUseId,
              content   = List(ToolResultBlock.json(answer)),
            ))),
          toolConfig = initial.toolConfig,
        )
        Bedrock.converse(followup).text.map: finalText =>
          assertTrue(
            first.stopReason == StopReason.ToolUse,
            toolName == ToolName("getWeather"),
            decodedInput.city == "San Francisco",
            finalText.toLowerCase.contains("foggy"),
          )

  val bedrockScenarios: List[BedrockScenario] = List(
    textOnly,
    asResponse,
    structuredOutput,
    structuredAsResponse,
    toolCallRoundTrip,
  )

  // ---------- Bedrock.request fixtures ----------

  case class PopulationInput(city: String) derives Schema

  /** A typed, schema-derived error for the population tool. Schema is
    * required by `ToolHandler.apply` (forward-compat with `Bedrock.loop`). */
  case class PopErr(message: String) derives Schema

  trait PopulationService:
    def populationOf(city: String): IO[PopErr, Int]

  val populationServiceOk: ULayer[PopulationService] =
    ZLayer.succeed(new PopulationService:
      def populationOf(city: String): IO[PopErr, Int] = ZIO.succeed(880_000),
    )

  val populationServiceFailing: ULayer[PopulationService] =
    ZLayer.succeed(new PopulationService:
      def populationOf(city: String): IO[PopErr, Int] =
        ZIO.fail(PopErr(s"unavailable: $city")),
    )

  def get_population(in: PopulationInput): ZIO[PopulationService, PopErr, Int] =
    ZIO.serviceWithZIO[PopulationService](_.populationOf(in.city))

  def get_weather(in: WeatherInput): WeatherOutput =
    WeatherOutput(64, "foggy")

  // ---------- Bedrock.request scenarios (single-turn high-level) ----------

  /** Pure handler dispatched. No reply tool — `toolChoice = Any` forces
    * dispatch. The fold's `weather` case turns the typed output into a
    * string. */
  val tooledRequestPureHandler: BedrockScenario = new BedrockScenario:
    val name = "request: pure handler dispatched, fold returns the mapped output"
    val mockScript = List(
      BedrockMock.MockBehavior.CallTool(ToolName("weather"), WeatherInput("Denver")),
    )
    def run =
      val tools = (
        weather = ToolHandler.fromPure(get_weather, "Get the weather for a US city."),
      )
      Bedrock.request("Weather of Denver?", tools).fold[String]:
        (weather = (w: WeatherOutput) => s"${w.temperatureF}°F ${w.conditions}")
      .map: r =>
        assertTrue(
          r.output == "64°F foggy",
          r.stopReason == StopReason.ToolUse,
        )

  /** Effectful handler with a `Schema[E]`-derived error type, success
    * path. The fold's `population` case fires with the handler's
    * successful `Int` output. */
  val tooledRequestEffectfulSuccess: BedrockScenario = new BedrockScenario:
    val name = "request: effectful handler succeeds, fold returns mapped Int"
    val mockScript = List(
      BedrockMock.MockBehavior.CallTool(ToolName("population"), PopulationInput("Berlin")),
    )
    def run =
      val tools = (
        population = ToolHandler(get_population, "Get the population of a city."),
      )
      Bedrock.request("Population of Berlin?", tools).fold[String]:
        (population = (p: Int) => s"$p people")
      .provideSomeLayer[Bedrock.Client](populationServiceOk).map: r =>
        assertTrue(r.output == "880000 people")

  /** Effectful handler fails with `ZIO.fail(PopErr)`. Error surfaces in
    * the ZIO error channel as the typed `PopErr` (member of
    * `ErrorsOf[Hs]`); the fold's case never runs. */
  val tooledRequestEffectfulFailure: BedrockScenario = new BedrockScenario:
    val name = "request: effectful handler fails, error propagates as typed E"
    val mockScript = List(
      BedrockMock.MockBehavior.CallTool(ToolName("population"), PopulationInput("Atlantis")),
    )
    def run =
      val tools = (
        population = ToolHandler(get_population, "Get the population of a city."),
      )
      val program = Bedrock.request("Population of Atlantis?", tools).fold[String]:
        (population = (p: Int) => s"$p people")
      .provideSomeLayer[Bedrock.Client](populationServiceFailing)
      program.either.map:
        case Left(e: PopErr)                  => assertTrue(e.message.contains("Atlantis"))
        case Left(other)                      => assertNever(s"expected PopErr, got $other")
        case Right(r)                         => assertNever(s"expected failure, got $r")

  /** `ModelResponseTool.text` registered. Model replies with text; the
    * `reply` case in the fold fires with the raw `String`. */
  val tooledRequestReplyText: BedrockScenario = new BedrockScenario:
    val name = "request: ModelResponseTool.text — text reply routes to fold"
    val mockScript = List(
      BedrockMock.MockBehavior.Reply("64F and foggy in Denver."),
    )
    def run =
      val tools = (
        weather = ToolHandler.fromPure(get_weather, "Get the weather for a US city."),
        reply   = ModelResponseTool.text,
      )
      Bedrock.request("Weather of Denver?", tools).fold[String]:
        (weather = (w: WeatherOutput) => s"weather: ${w.temperatureF}",
         reply   = (s: String)        => s"reply: $s")
      .map: r =>
        assertTrue(r.output.startsWith("reply: "), r.output.contains("foggy"))

  /** `ModelResponseTool[Forecast]` registered. Model replies with JSON;
    * decoded via `Schema[Forecast]` and routed through the `reply`
    * fold case with a typed `Forecast`. */
  val tooledRequestReplyStructured: BedrockScenario = new BedrockScenario:
    val name = "request: ModelResponseTool[Forecast] — structured reply decodes via Schema"
    val mockScript = List(
      BedrockMock.MockBehavior.ReplyJson(Forecast(
        city    = "Denver",
        summary = "Sunny.",
      )),
    )
    def run =
      val tools = (
        weather = ToolHandler.fromPure(get_weather, "Get the weather for a US city."),
        reply   = ModelResponseTool[Forecast]("Summarise as a Forecast."),
      )
      Bedrock.request("Weather of Denver?", tools).fold[Forecast]:
        (weather = (w: WeatherOutput) => Forecast("Denver", s"${w.temperatureF}°F"),
         reply   = (f: Forecast)      => f)
      .map: r =>
        assertTrue(
          r.output.city    == "Denver",
          r.output.summary == "Sunny.",
        )

  /** Model invokes a tool name we didn't register → `Bedrock.Error.UnknownTool`. */
  val tooledRequestUnknownTool: BedrockScenario = new BedrockScenario:
    val name = "request: model invokes an unregistered tool name → UnknownTool"
    val mockScript = List(
      BedrockMock.MockBehavior.CallTool(ToolName("not_registered"), WeatherInput("Denver")),
    )
    def run =
      val tools = (
        weather = ToolHandler.fromPure(get_weather, "Get the weather for a US city."),
      )
      val program = Bedrock.request("Use the weather tool.", tools).fold[String]:
        (weather = (w: WeatherOutput) => "ok")
      program.either.map:
        case Left(e: Bedrock.Error.UnknownTool) => assertTrue(e.name == ToolName("not_registered"))
        case Left(other)                        => assertNever(s"expected UnknownTool, got $other")
        case Right(r)                           => assertNever(s"expected failure, got $r")

  /** Model returns text but no `ModelResponseTool` is registered →
    * `Bedrock.Error.UnexpectedReply`. */
  val tooledRequestUnexpectedReply: BedrockScenario = new BedrockScenario:
    val name = "request: text reply without ModelResponseTool registered → UnexpectedReply"
    val mockScript = List(
      BedrockMock.MockBehavior.Reply("just chatting"),
    )
    def run =
      val tools = (
        weather = ToolHandler.fromPure(get_weather, "Get the weather for a US city."),
      )
      val program = Bedrock.request("Hello.", tools).fold[String]:
        (weather = (w: WeatherOutput) => "ok")
      program.either.map:
        case Left(_: Bedrock.Error.UnexpectedReply) => assertCompletes
        case Left(other)                            => assertNever(s"expected UnexpectedReply, got $other")
        case Right(r)                               => assertNever(s"expected failure, got $r")

  /** Scenarios where the *live* model behaves predictably enough to
    * smoke-test the wire shape: the model dispatches the registered
    * tool(s) successfully, and we observe the dispatch path end-to-end.
    *
    * All `tooledRequest*` scenarios run against the mock too — that's
    * where framework-correctness lives. */
  val bedrockRequestScenarios: List[BedrockScenario] = List(
    tooledRequestPureHandler,
    tooledRequestEffectfulSuccess,
    tooledRequestEffectfulFailure,
  )

  /** Scenarios that depend on the model behaving in ways we can only
    * script with the mock — inventing a tool name (`UnknownTool`),
    * skipping a registered tool to reply with text (`ModelResponseTool.text`),
    * skipping it to produce structured output (`ModelResponseTool[Forecast]`),
    * or returning bare text under `toolChoice = Any` (`UnexpectedReply`). */
  val bedrockRequestMockOnlyScenarios: List[BedrockScenario] = List(
    tooledRequestReplyText,
    tooledRequestReplyStructured,
    tooledRequestUnknownTool,
    tooledRequestUnexpectedReply,
  )

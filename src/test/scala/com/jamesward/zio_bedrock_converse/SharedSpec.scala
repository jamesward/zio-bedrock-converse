package com.jamesward.zio_bedrock_converse

import com.jamesward.zio_bedrock_converse.BedrockConverse.*
import zio.*
import zio.direct.*
import zio.schema.{Schema, derived}
import zio.test.*

/**
 * Spec bodies shared between [[MockSpec]] (deterministic, runs without
 * credentials) and [[IntegrationSpec]] (live, requires
 * `AWS_BEARER_TOKEN_BEDROCK`).
 *
 * Each `case` in [[Scenario]] declares the prompt, the tools, the scripted
 * mock behaviors, and an assertion the test runs against the response.
 */
object SharedSpec:

  // ---------- Tool ADTs used across tests ----------

  case class WeatherInput(city: String) derives Schema
  case class WeatherOutput(temperatureF: Int, conditions: String) derives Schema

  case class PopulationInput(city: String, country: String) derives Schema

  // ---------- Tools ----------

  def get_weather(in: WeatherInput): WeatherOutput =
    WeatherOutput(temperatureF = 64, conditions = "foggy")

  private val getWeather: Tool[WeatherInput, WeatherOutput, Any, Nothing] =
    get_weather.asTool("Get the current weather (temperature in F + conditions) for a US city.")

  case class PopulationOptOut() extends Throwable("population unavailable")

  def get_population(in: PopulationInput): ZIO[Any, PopulationOptOut, Int] =
    if in.city.equalsIgnoreCase("Atlantis") then ZIO.fail(PopulationOptOut())
    else ZIO.succeed(880_000)

  private val getPopulation: Tool[PopulationInput, Int, Any, PopulationOptOut] =
    get_population.asTool("Get the population of a city.")

  // ---------- Scenarios ----------

  /** A single test case parameterised over the layer it'll run against. */
  trait Scenario:
    def name: String
    def run: ZIO[BedrockConverse, Any, TestResult]
    /** The mock script that simulates the model's side of the conversation. */
    def mockScript: List[BedrockConverseMock.MockBehavior]

  // Text only — no tools.
  val textOnly: Scenario = new Scenario:
    val name = "text-only request returns assistant text"
    val mockScript = List(BedrockConverseMock.MockBehavior.Reply("hello"))
    def run = defer:
      val text = BedrockConverse.converse(ConverseRequest("Say hello.")).text.run
      assertTrue(text.nonEmpty)

  // .asResponse exposes stopReason + usage.
  val asResponse: Scenario = new Scenario:
    val name = ".asResponse exposes stopReason and usage"
    val mockScript = List(BedrockConverseMock.MockBehavior.Reply("hello"))
    def run = defer:
      val resp = BedrockConverse.converse(ConverseRequest("Say hello.")).asResponse.run
      assertTrue(
        resp.output.text.nonEmpty,
        resp.stopReason == StopReason.EndTurn,
      )

  // One tool call, then final text.
  val singleToolCall: Scenario = new Scenario:
    val name = "single tool call: weather"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.CallTool(ToolName("get_weather"), WeatherInput("San Francisco")),
      BedrockConverseMock.MockBehavior.Reply("It is 64F and foggy in San Francisco."),
    )
    def run = defer:
      val text = BedrockConverse.converse(
        ConverseRequest("What is the weather in San Francisco?"),
        getWeather,
      ).text.run
      assertTrue(text.toLowerCase.contains("foggy") || text.contains("64"))

  // Two tools, model picks one. Mock scripts which one.
  val multipleTools: Scenario = new Scenario:
    val name = "multiple tools: population picked over weather"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.CallTool(
        ToolName("get_population"),
        PopulationInput("Berlin", "Germany"),
      ),
      BedrockConverseMock.MockBehavior.Reply("Berlin has roughly 880,000 residents."),
    )
    def run = defer:
      val text = BedrockConverse.converse(
        ConverseRequest("How many people live in Berlin, Germany?"),
        getWeather, getPopulation,
      ).text.run
      assertTrue(
        text.toLowerCase.contains("berlin"),
        text.contains("880") || text.toLowerCase.contains("population"),
      )

  // Tool ZIO fails → effect fails with the tool's error.
  val toolFailure: Scenario = new Scenario:
    val name = "tool failure propagates to the caller"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.CallTool(
        ToolName("get_population"),
        PopulationInput("Atlantis", "Atlantean Empire"),
      ),
    )
    def run = BedrockConverse.converse(
      ConverseRequest("How many people live in Atlantis?"),
      getPopulation,
    ).text.either.map:
      case Left(_: PopulationOptOut) => assertCompletes
      case Left(other)               => assertNever(s"expected PopulationOptOut, got $other")
      case Right(r)                  => assertNever(s"expected failure, got $r")

  // Unknown tool name from the model.
  val unknownTool: Scenario = new Scenario:
    val name = "unknown tool name surfaces ConverseError.UnknownTool"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.CallTool(ToolName("not_registered"), WeatherInput("Anywhere")),
    )
    def run = BedrockConverse.converse(
      ConverseRequest("Please use a tool."),
      getWeather,
    ).text.either.map:
      case Left(_: ConverseError.UnknownTool) => assertCompletes
      case Left(other)                        => assertNever(s"expected UnknownTool, got $other")
      case Right(r)                           => assertNever(s"expected failure, got $r")

  // Structured output (no tools).
  case class Forecast(city: String, summary: String) derives Schema

  val structuredOutput: Scenario = new Scenario:
    val name = ".as[Forecast] decodes JSON into a typed case class"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.ReplyJson(Forecast(
        city    = "Seattle",
        summary = "Rainy with intermittent drizzle.",
      )),
    )
    def run = defer:
      val forecast = BedrockConverse.converse(
        ConverseRequest("Give a one-sentence weather forecast for Seattle."),
      ).as[Forecast].run
      assertTrue(
        forecast.city.toLowerCase.contains("seattle"),
        forecast.summary.nonEmpty,
      )

  // Structured response — exposes both the typed value and the metadata.
  val structuredAsResponse: Scenario = new Scenario:
    val name = ".asResponse[Forecast] exposes typed output + metadata"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.ReplyJson(Forecast(
        city    = "Seattle",
        summary = "Cloudy.",
      )),
    )
    def run = defer:
      val resp = BedrockConverse.converse(
        ConverseRequest("Forecast Seattle."),
      ).asResponse[Forecast].run
      assertTrue(
        resp.output.city.toLowerCase.contains("seattle"),
        resp.stopReason == StopReason.EndTurn,
      )

  // Structured output combined with a tool call.
  val structuredWithTool: Scenario = new Scenario:
    val name = "structured output with a tool call"
    val mockScript = List(
      BedrockConverseMock.MockBehavior.CallTool(ToolName("get_weather"), WeatherInput("Seattle")),
      BedrockConverseMock.MockBehavior.ReplyJson(Forecast(
        city    = "Seattle",
        summary = "It is 64F and foggy.",
      )),
    )
    def run = defer:
      val forecast = BedrockConverse.converse(
        ConverseRequest("Use the tool to get Seattle's weather, then summarise."),
        getWeather,
      ).as[Forecast].run
      assertTrue(forecast.city.toLowerCase.contains("seattle"))

  // ---------- Scenario list (used by both specs) ----------

  val all: List[Scenario] = List(
    textOnly,
    asResponse,
    singleToolCall,
    multipleTools,
    toolFailure,
    unknownTool,
    structuredOutput,
    structuredAsResponse,
    structuredWithTool,
  )

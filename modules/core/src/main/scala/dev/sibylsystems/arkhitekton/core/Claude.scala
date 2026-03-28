package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

import scala.concurrent.duration.*

import io.circe.*
import io.circe.parser.parse
import io.circe.syntax.*
import sttp.client4.*
import sttp.client4.http4s.Http4sBackend

// ---------------------------------------------------------------------------
// Backend abstraction
// ---------------------------------------------------------------------------

trait LlmBackend:

  def complete(
    model: String,
    maxTokens: Int,
    system: Option[String],
    messages: List[ClaudeMessage],
    tools: List[Json] = Nil,
  ): IO[String]

// ---------------------------------------------------------------------------
// Anthropic HTTP backend — tool-calling conversation loop
// ---------------------------------------------------------------------------

object AnthropicHttpBackend:

  def apply(apiKey: String): LlmBackend = new LlmBackend:

    private val ApiUrl          = uri"https://api.anthropic.com/v1/messages"
    private val ApiVersion      = "2023-06-01"
    private val MaxRoundTrips   = 5
    private val MaxRateLimitRetries = 5
    private val InitialBackoffMs    = 15000L // 15s — rate limit window is per-minute

    def complete(
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json] = Nil,
    ): IO[String] =
      Http4sBackend.usingDefaultEmberClientBuilder[IO]().use { backend =>
        loop(backend, model, maxTokens, system, messages, tools, roundTrip = 0)
      }

    private def loop(
      backend: Backend[IO],
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json],
      roundTrip: Int,
    ): IO[String] =
      sendWithRetry(backend, model, maxTokens, system, messages, tools, rateLimitRetry = 0).flatMap {
        json => handleResponse(backend, model, maxTokens, system, messages, tools, json, roundTrip)
      }

    private def sendWithRetry(
      backend: Backend[IO],
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json],
      rateLimitRetry: Int,
    ): IO[String] =
      for
        body <- IO(buildBody(model, maxTokens, system, messages, tools))
        request = basicRequest
                    .post(ApiUrl)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ApiVersion)
                    .contentType("application/json")
                    .body(body)
                    .response(asString)
        response <- request.send(backend)
        json <- response.body match
                  case Right(j) => IO.pure(j)
                  case Left(e) if isRateLimitError(e) && rateLimitRetry < MaxRateLimitRetries =>
                    val retryAfter = extractRetryAfter(e)
                    val backoff    = retryAfter.getOrElse(InitialBackoffMs * (1L << rateLimitRetry))
                    val waitSecs   = f"${backoff / 1000.0}%.1f"
                    IO.println(s"    [rate-limit] retry ${rateLimitRetry + 1}/$MaxRateLimitRetries, waiting ${waitSecs}s...") *>
                      IO.sleep(backoff.millis) *>
                      sendWithRetry(backend, model, maxTokens, system, messages, tools, rateLimitRetry + 1)
                  case Left(e) => IO.raiseError(new RuntimeException(s"API error: $e"))
      yield json

    private def isRateLimitError(body: String): Boolean =
      body.contains("rate_limit_error") || body.contains("429")

    private def extractRetryAfter(body: String): Option[Long] =
      // Try to extract retry-after from the error JSON (Anthropic sometimes includes it)
      parse(body).toOption.flatMap(_.hcursor.downField("error").get[Double]("retry_after").toOption)
        .map(secs => (secs * 1000).toLong)

    private def handleResponse(
      backend: Backend[IO],
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json],
      json: String,
      roundTrip: Int,
    ): IO[String] =
      for
        parsed <- IO.fromEither(
                    parse(json).left.map(e => new RuntimeException(s"JSON parse error: ${e.getMessage}")),
                  )
        stopReason = parsed.hcursor.get[String]("stop_reason").getOrElse("end_turn")
        content    = parsed.hcursor.downField("content").as[List[Json]].getOrElse(Nil)
        result <- stopReason match
                    case "tool_use" if roundTrip < MaxRoundTrips =>
                      resolveTools(backend, model, maxTokens, system, messages, tools, content, roundTrip)
                    case "tool_use" =>
                      IO.raiseError(new RuntimeException(s"Tool-calling loop exceeded $MaxRoundTrips round-trips"))
                    case _ =>
                      IO.pure(content.flatMap(_.hcursor.get[String]("text").toOption).mkString("\n"))
      yield result

    private def resolveTools(
      backend: Backend[IO],
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json],
      content: List[Json],
      roundTrip: Int,
    ): IO[String] =
      val toolCalls = content.filter(_.hcursor.get[String]("type").contains("tool_use"))
      val toolResults = toolCalls.map { tc =>
        val id       = tc.hcursor.get[String]("id").getOrElse("")
        val name     = tc.hcursor.get[String]("name").getOrElse("")
        val document = tc.hcursor.downField("input").get[String]("document").getOrElse("")
        val result = name match
          case "get_reference" => References.resolve(document).getOrElse(s"Unknown reference document: $document")
          case other           => s"Unknown tool: $other"
        Json.obj("type" -> "tool_result".asJson, "tool_use_id" -> id.asJson, "content" -> result.asJson)
      }
      val updated =
        messages :+ ClaudeMessage("assistant", content.asJson.noSpaces) :+
          ClaudeMessage("user", toolResults.asJson.noSpaces)
      loop(backend, model, maxTokens, system, updated, tools, roundTrip + 1)

    private def buildBody(
      model: String,
      maxTokens: Int,
      system: Option[String],
      messages: List[ClaudeMessage],
      tools: List[Json],
    ): String =
      val msgArray = messages.map { m =>
        val contentJson = parse(m.content) match
          case Right(arr) if arr.isArray => arr
          case _                         => m.content.asJson
        Json.obj("role" -> m.role.asJson, "content" -> contentJson)
      }
      val base       = Json.obj("model" -> model.asJson, "max_tokens" -> maxTokens.asJson, "messages" -> msgArray.asJson)
      val withSystem = system.fold(base)(s => base.deepMerge(Json.obj("system" -> s.asJson)))
      val withTools  = if tools.isEmpty then withSystem else withSystem.deepMerge(Json.obj("tools" -> tools.asJson))
      withTools.noSpaces

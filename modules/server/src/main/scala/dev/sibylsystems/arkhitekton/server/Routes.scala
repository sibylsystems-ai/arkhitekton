package dev.sibylsystems.arkhitekton.server

import cats.effect.IO
import cats.syntax.all.*

import io.circe.syntax.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.http4s.headers.`Content-Type`

import dev.sibylsystems.arkhitekton.core.Compiler
import dev.sibylsystems.arkhitekton.protocol.*

object Routes:

  def apply(handler: PipelineHandler, idris2: String, staticDir: Option[String] = None): HttpRoutes[IO] =
    val apiRoutes = HttpRoutes.of[IO] {

      // Health check
      case GET -> Root / "api" / "health" =>
        Compiler.verify(idris2).flatMap {
          case Some(v) => Ok(io.circe.Json.obj("status" -> "ok".asJson, "idris2" -> v.asJson))
          case None    => ServiceUnavailable(io.circe.Json.obj("status" -> "error".asJson, "idris2" -> "not found".asJson))
        }

      // Streamable HTTP endpoint: POST command, get back SSE stream
      case req @ POST -> Root / "api" / "pipeline" =>
        req.asJsonDecode[ClientCommand].attempt.flatMap {
          case Left(err) =>
            BadRequest(io.circe.Json.obj("error" -> s"Invalid command: ${err.getMessage}".asJson))
          case Right(command) =>
            val eventStream = handler.handle(command).map { event =>
              val json = event.asJson.noSpaces
              ServerSentEvent(data = Some(json), eventType = Some(event.sseEventType))
            }
            Ok(eventStream).map(_.withContentType(`Content-Type`(MediaType.`text/event-stream`)))
        }
    }

    // Serve static files (SPA) if a static directory is configured
    staticDir match
      case Some(dir) =>
        val static = org.http4s.server.staticcontent.fileService[IO](
          org.http4s.server.staticcontent.FileService.Config(dir),
        )
        apiRoutes <+> static
      case None => apiRoutes

  extension (event: PipelineEvent)
    private def sseEventType: String = event match
      case _: PipelineEvent.StepStarted     => "step_started"
      case _: PipelineEvent.StepProgress    => "step_progress"
      case _: PipelineEvent.StepCompleted   => "step_completed"
      case _: PipelineEvent.StepFailed      => "step_failed"
      case _: PipelineEvent.ArtifactReady   => "artifact_ready"
      case _: PipelineEvent.QuestionsReady  => "questions_ready"
      case _: PipelineEvent.ResultReady     => "result_ready"
      case _: PipelineEvent.TestsCompleted  => "tests_completed"
      case _: PipelineEvent.SessionMetrics  => "session_metrics"
      case _: PipelineEvent.Info            => "info"
      case _: PipelineEvent.ReadinessResult => "readiness_result"
      case _: PipelineEvent.StreamEnd       => "stream_end"
      case _: PipelineEvent.Error           => "error"

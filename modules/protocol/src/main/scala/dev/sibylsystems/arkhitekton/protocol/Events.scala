package dev.sibylsystems.arkhitekton.protocol

import io.circe.*
import io.circe.generic.semiauto.*

// ---------------------------------------------------------------------------
// Server → Client (SSE events on POST response stream)
// ---------------------------------------------------------------------------

enum PipelineEvent:
  case StepStarted(step: String, label: String)
  case StepProgress(step: String, message: String)
  case StepCompleted(step: String, durationMs: Long)
  case StepFailed(step: String, error: String)
  case ArtifactReady(kind: String, name: String, content: String)
  case QuestionsReady(questions: List[String])
  case ResultReady(outcome: String, title: String, explanation: String)
  case TestsCompleted(passed: Int, failed: Int, output: String)
  case SessionMetrics(timings: List[TimingEntry])
  case Info(message: String)
  case ReadinessResult(ready: Boolean, kind: String, intent: String, questions: List[String])
  case StreamEnd(success: Boolean)
  case Error(message: String)

object PipelineEvent:
  // Manual codec to handle the enum discriminator cleanly
  given Encoder[PipelineEvent] = Encoder.instance { event =>
    val (eventType, data) = event match
      case e: StepStarted      => ("step_started", deriveEncoder[StepStarted].apply(e))
      case e: StepProgress     => ("step_progress", deriveEncoder[StepProgress].apply(e))
      case e: StepCompleted    => ("step_completed", deriveEncoder[StepCompleted].apply(e))
      case e: StepFailed       => ("step_failed", deriveEncoder[StepFailed].apply(e))
      case e: ArtifactReady    => ("artifact_ready", deriveEncoder[ArtifactReady].apply(e))
      case e: QuestionsReady   => ("questions_ready", deriveEncoder[QuestionsReady].apply(e))
      case e: ResultReady      => ("result_ready", deriveEncoder[ResultReady].apply(e))
      case e: TestsCompleted   => ("tests_completed", deriveEncoder[TestsCompleted].apply(e))
      case e: SessionMetrics   => ("session_metrics", deriveEncoder[SessionMetrics].apply(e))
      case e: Info             => ("info", deriveEncoder[Info].apply(e))
      case e: ReadinessResult  => ("readiness_result", deriveEncoder[ReadinessResult].apply(e))
      case e: StreamEnd        => ("stream_end", deriveEncoder[StreamEnd].apply(e))
      case e: Error            => ("error", deriveEncoder[Error].apply(e))
    data.deepMerge(Json.obj("type" -> Json.fromString(eventType)))
  }

  given Decoder[PipelineEvent] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "step_started"      => deriveDecoder[StepStarted].apply(cursor)
      case "step_progress"     => deriveDecoder[StepProgress].apply(cursor)
      case "step_completed"    => deriveDecoder[StepCompleted].apply(cursor)
      case "step_failed"       => deriveDecoder[StepFailed].apply(cursor)
      case "artifact_ready"    => deriveDecoder[ArtifactReady].apply(cursor)
      case "questions_ready"   => deriveDecoder[QuestionsReady].apply(cursor)
      case "result_ready"      => deriveDecoder[ResultReady].apply(cursor)
      case "tests_completed"   => deriveDecoder[TestsCompleted].apply(cursor)
      case "session_metrics"   => deriveDecoder[SessionMetrics].apply(cursor)
      case "info"              => deriveDecoder[Info].apply(cursor)
      case "readiness_result"  => deriveDecoder[ReadinessResult].apply(cursor)
      case "stream_end"        => deriveDecoder[StreamEnd].apply(cursor)
      case "error"             => deriveDecoder[Error].apply(cursor)
      case other               => Left(DecodingFailure(s"Unknown event type: $other", cursor.history))
    }
  }

// ---------------------------------------------------------------------------
// Client → Server (POST body)
// ---------------------------------------------------------------------------

enum ClientCommand:
  case StartAnalysis(spec: String, mode: AnalysisMode)
  case SubmitAnswers(answers: List[String])
  case RunTranspile
  case RunSingleStep(step: String)
  case Cancel

object ClientCommand:
  given Encoder[ClientCommand] = Encoder.instance { cmd =>
    val (cmdType, data) = cmd match
      case e: StartAnalysis  => ("start_analysis", deriveEncoder[StartAnalysis].apply(e))
      case e: SubmitAnswers  => ("submit_answers", deriveEncoder[SubmitAnswers].apply(e))
      case RunTranspile      => ("run_transpile", Json.obj())
      case e: RunSingleStep  => ("run_single_step", deriveEncoder[RunSingleStep].apply(e))
      case Cancel            => ("cancel", Json.obj())
    data.deepMerge(Json.obj("command" -> Json.fromString(cmdType)))
  }

  given Decoder[ClientCommand] = Decoder.instance { cursor =>
    cursor.get[String]("command").flatMap {
      case "start_analysis"  => deriveDecoder[StartAnalysis].apply(cursor)
      case "submit_answers"  => deriveDecoder[SubmitAnswers].apply(cursor)
      case "run_transpile"   => Right(RunTranspile)
      case "run_single_step" => deriveDecoder[RunSingleStep].apply(cursor)
      case "cancel"          => Right(Cancel)
      case other             => Left(DecodingFailure(s"Unknown command: $other", cursor.history))
    }
  }

// ---------------------------------------------------------------------------
// Shared types
// ---------------------------------------------------------------------------

enum AnalysisMode derives CanEqual:
  case Once, Interactive, Transpile

object AnalysisMode:
  given Encoder[AnalysisMode] = Encoder.encodeString.contramap {
    case AnalysisMode.Once        => "once"
    case AnalysisMode.Interactive => "interactive"
    case AnalysisMode.Transpile   => "transpile"
  }

  given Decoder[AnalysisMode] = Decoder.decodeString.emap {
    case "once"        => Right(AnalysisMode.Once)
    case "interactive" => Right(AnalysisMode.Interactive)
    case "transpile"   => Right(AnalysisMode.Transpile)
    case other         => Left(s"Unknown analysis mode: $other")
  }

final case class TimingEntry(step: String, durationMs: Long) derives CanEqual

object TimingEntry:
  given Codec[TimingEntry] = deriveCodec

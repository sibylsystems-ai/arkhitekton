package dev.sibylsystems.arkhitekton.web

import dev.sibylsystems.arkhitekton.protocol.*

// ---------------------------------------------------------------------------
// UI state model
// ---------------------------------------------------------------------------

enum UIMode:
  case Editor, Progress, Questions, Results

enum StepState:
  case Pending, Running, Done, Failed

final case class TimelineNode(step: String, state: StepState, durationMs: Option[Long] = None)

final case class AppState(
  mode: UIMode = UIMode.Editor,
  timeline: List[TimelineNode] = Nil,
  artifacts: Map[String, String] = Map.empty,
  questions: List[String] = Nil,
  metrics: List[TimingEntry] = Nil,
  activeStep: Option[String] = None,
  spec: String = "",
  resultTitle: Option[String] = None,
  resultOutcome: Option[String] = None,
  resultExplanation: Option[String] = None,
  error: Option[String] = None,
  streaming: Boolean = false,
):

  def applyEvent(event: PipelineEvent): AppState = event match
    case PipelineEvent.StepStarted(step, _) =>
      val node = TimelineNode(step, StepState.Running)
      copy(
        mode = UIMode.Progress,
        activeStep = Some(step),
        timeline = timeline.filterNot(_.step == step) :+ node,
        streaming = true,
      )

    case PipelineEvent.StepProgress(_, _) =>
      this // progress messages are informational, no state change needed

    case PipelineEvent.StepCompleted(step, ms) =>
      copy(
        timeline = timeline.map(n =>
          if n.step == step then n.copy(state = StepState.Done, durationMs = Some(ms)) else n,
        ),
        activeStep = None,
      )

    case PipelineEvent.StepFailed(step, _) =>
      copy(
        timeline = timeline.map(n =>
          if n.step == step then n.copy(state = StepState.Failed) else n,
        ),
        activeStep = None,
      )

    case PipelineEvent.ArtifactReady(kind, _, content) =>
      copy(artifacts = artifacts + (kind -> content))

    case PipelineEvent.QuestionsReady(qs) =>
      copy(mode = UIMode.Questions, questions = qs)

    case PipelineEvent.ResultReady(outcome, title, explanation) =>
      copy(
        mode = UIMode.Results,
        resultTitle = Some(title),
        resultOutcome = Some(outcome),
        resultExplanation = Some(explanation),
      )

    case PipelineEvent.TestsCompleted(passed, failed, output) =>
      copy(artifacts = artifacts + ("TestOutput" -> s"Passed: $passed, Failed: $failed\n\n$output"))

    case PipelineEvent.SessionMetrics(timings) =>
      copy(metrics = timings)

    case PipelineEvent.Info(message) =>
      copy(artifacts = artifacts + ("Log" -> (artifacts.getOrElse("Log", "") + message + "\n")))

    case PipelineEvent.ReadinessResult(ready, kind, intent, qs) =>
      if ready then copy(artifacts = artifacts + ("Readiness" -> s"$kind: $intent"))
      else copy(mode = UIMode.Questions, questions = qs)

    case PipelineEvent.StreamEnd(_) =>
      copy(streaming = false)

    case PipelineEvent.Error(message) =>
      copy(error = Some(message), streaming = false)

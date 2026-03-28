package dev.sibylsystems.arkhitekton.protocol

import io.circe.syntax.*
import io.circe.parser.decode
import munit.FunSuite

class EventsSuite extends FunSuite:

  // ---------------------------------------------------------------------------
  // PipelineEvent round-trip
  // ---------------------------------------------------------------------------

  private val events: List[PipelineEvent] = List(
    PipelineEvent.StepStarted("formalize", "Formalizing..."),
    PipelineEvent.StepProgress("formalize", "50% done"),
    PipelineEvent.StepCompleted("formalize", 1234L),
    PipelineEvent.StepFailed("compile", "type mismatch"),
    PipelineEvent.ArtifactReady("IdrisSource", "Spec.idr", "module Spec"),
    PipelineEvent.QuestionsReady(List("What is the default?", "Is it optional?")),
    PipelineEvent.ResultReady("clean", "Refined Specification", "Your spec is..."),
    PipelineEvent.TestsCompleted(10, 2, "vitest output..."),
    PipelineEvent.SessionMetrics(List(TimingEntry("Formalize", 1000), TimingEntry("Compile", 500))),
    PipelineEvent.Info("some message"),
    PipelineEvent.ReadinessResult(true, "checker", "validates rules", Nil),
    PipelineEvent.ReadinessResult(false, "pipeline", "transforms data", List("q1", "q2")),
    PipelineEvent.StreamEnd(true),
    PipelineEvent.Error("something broke"),
  )

  events.foreach { event =>
    test(s"PipelineEvent round-trip: ${event.getClass.getSimpleName}") {
      val json    = event.asJson
      val decoded = json.as[PipelineEvent]
      assertEquals(decoded, Right(event))
    }
  }

  // ---------------------------------------------------------------------------
  // ClientCommand round-trip
  // ---------------------------------------------------------------------------

  private val commands: List[ClientCommand] = List(
    ClientCommand.StartAnalysis("my spec", AnalysisMode.Once),
    ClientCommand.StartAnalysis("other spec", AnalysisMode.Transpile),
    ClientCommand.SubmitAnswers(List("answer 1", "answer 2")),
    ClientCommand.RunTranspile,
    ClientCommand.RunSingleStep("compile"),
    ClientCommand.Cancel,
  )

  commands.foreach { cmd =>
    test(s"ClientCommand round-trip: ${cmd.getClass.getSimpleName}") {
      val json    = cmd.asJson
      val decoded = json.as[ClientCommand]
      assertEquals(decoded, Right(cmd))
    }
  }

  // ---------------------------------------------------------------------------
  // JSON structure validation
  // ---------------------------------------------------------------------------

  test("PipelineEvent includes type discriminator") {
    val json = (PipelineEvent.StepStarted("x", "y"): PipelineEvent).asJson
    assertEquals(json.hcursor.get[String]("type"), Right("step_started"))
  }

  test("ClientCommand includes command discriminator") {
    val json = (ClientCommand.Cancel: ClientCommand).asJson
    assertEquals(json.hcursor.get[String]("command"), Right("cancel"))
  }

  test("PipelineEvent decodes from JSON string") {
    val raw = """{"type":"info","message":"hello"}"""
    val decoded = decode[PipelineEvent](raw)
    assertEquals(decoded, Right(PipelineEvent.Info("hello")))
  }

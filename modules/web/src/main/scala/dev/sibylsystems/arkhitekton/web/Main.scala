package dev.sibylsystems.arkhitekton.web

import com.raquo.laminar.api.L.*

import dev.sibylsystems.arkhitekton.protocol.*

object Main:

  val stateVar: Var[AppState] = Var(AppState())

  def main(args: Array[String]): Unit =
    renderOnDomContentLoaded(
      org.scalajs.dom.document.getElementById("app"),
      appElement,
    )

  private def appElement: HtmlElement =
    div(
      cls := "app-container",
      headerTag(
        cls := "app-header",
        h1("arkhitekton studio"),
      ),
      div(
        cls := "app-body",
        Components.timelinePanel(stateVar.signal),
        Components.mainPanel(
          stateVar.signal,
          onAnalyze = handleAnalyze,
          onSubmitAnswers = handleSubmitAnswers,
          onTranspile = handleTranspile,
        ),
        Components.artifactPanel(stateVar.signal),
      ),
      Components.metricsBar(stateVar.signal),
    )

  // ---------------------------------------------------------------------------
  // Command handlers
  // ---------------------------------------------------------------------------

  private def handleAnalyze(spec: String): Unit =
    if spec.nonEmpty then
      stateVar.set(AppState(spec = spec, mode = UIMode.Progress, streaming = true))
      sendCommand(ClientCommand.StartAnalysis(spec, AnalysisMode.Once))

  private def handleSubmitAnswers(answers: List[String]): Unit =
    stateVar.update(_.copy(mode = UIMode.Progress, streaming = true))
    sendCommand(ClientCommand.SubmitAnswers(answers))

  private def handleTranspile(): Unit =
    stateVar.update(_.copy(mode = UIMode.Progress, streaming = true))
    sendCommand(ClientCommand.RunTranspile)

  private def sendCommand(command: ClientCommand): Unit =
    ApiClient.sendCommand(
      command,
      onEvent = event => stateVar.update(_.applyEvent(event)),
      onDone = () => stateVar.update(_.copy(streaming = false)),
      onError = err => stateVar.update(_.copy(error = Some(err), streaming = false)),
    )

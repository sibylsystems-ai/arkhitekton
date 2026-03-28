package dev.sibylsystems.arkhitekton.web

import com.raquo.laminar.api.L.*

/** Reusable UI components for the three-panel layout. */
object Components:

  // ---------------------------------------------------------------------------
  // Timeline Panel (Left)
  // ---------------------------------------------------------------------------

  def timelinePanel(state: Signal[AppState]): HtmlElement =
    navTag(
      cls := "timeline-panel",
      h3("Pipeline"),
      div(
        cls := "timeline-steps",
        children <-- state.map(_.timeline).map(_.map(timelineNode)),
      ),
    )

  private def timelineNode(node: TimelineNode): HtmlElement =
    val (icon, cssClass) = node.state match
      case StepState.Pending => ("\u25cb", "pending")
      case StepState.Running => ("\u25c9", "running")
      case StepState.Done    => ("\u25cf", "done")
      case StepState.Failed  => ("\u25cf", "failed")
    val timing = node.durationMs.map(ms => f" ${ms / 1000.0}%.1fs").getOrElse("")
    div(
      cls := s"timeline-node $cssClass",
      span(cls := "node-icon", icon),
      span(cls := "node-label", s"${node.step}$timing"),
    )

  // ---------------------------------------------------------------------------
  // Main Panel (Center)
  // ---------------------------------------------------------------------------

  def mainPanel(
    state: Signal[AppState],
    onAnalyze: String => Unit,
    onSubmitAnswers: List[String] => Unit,
    onTranspile: () => Unit,
  ): HtmlElement =
    div(
      cls := "main-panel",
      child <-- state.map(_.mode).map {
        case UIMode.Editor    => editorView(state, onAnalyze)
        case UIMode.Progress  => progressView(state)
        case UIMode.Questions => questionsView(state, onSubmitAnswers)
        case UIMode.Results   => resultsView(state, onAnalyze, onTranspile)
      },
    )

  private def editorView(state: Signal[AppState], onAnalyze: String => Unit): HtmlElement =
    val specVar = Var("")
    div(
      cls := "editor-view",
      h2("English Specification"),
      textArea(
        cls         := "spec-editor",
        placeholder := "Paste your English spec here...",
        rows        := 20,
        controlled(
          value <-- specVar.signal,
          onInput.mapToValue --> specVar.writer,
        ),
      ),
      div(
        cls := "editor-actions",
        button(
          cls := "btn-primary",
          "Analyze",
          onClick --> { _ => onAnalyze(specVar.now()) },
          disabled <-- state.map(_.streaming),
        ),
      ),
    )

  private def progressView(state: Signal[AppState]): HtmlElement =
    div(
      cls := "progress-view",
      h2("Running Pipeline..."),
      div(
        cls := "progress-log",
        child.text <-- state.map { s =>
          s.activeStep.map(step => s"Currently: $step...").getOrElse("Processing...")
        },
      ),
      div(
        cls := "spinner",
        child <-- state.map(s => if s.streaming then span(cls := "pulse", "\u25cf") else emptyNode),
      ),
    )

  private def questionsView(state: Signal[AppState], onSubmit: List[String] => Unit): HtmlElement =
    val answersVar = Var(List.empty[Var[String]])
    div(
      cls := "questions-view",
      h2("Questions from the Compiler"),
      p("The spec has gaps. Please answer these questions:"),
      div(
        cls := "questions-list",
        children <-- state.map(_.questions).map { qs =>
          val vars = qs.map(_ => Var(""))
          answersVar.set(vars)
          qs.zip(vars).zipWithIndex.map { case ((q, v), i) =>
            div(
              cls := "question-item",
              label(s"${i + 1}. $q"),
              input(
                typ         := "text",
                cls         := "answer-input",
                placeholder := "Your answer...",
                controlled(
                  value <-- v.signal,
                  onInput.mapToValue --> v.writer,
                ),
              ),
            )
          }
        },
      ),
      button(
        cls := "btn-primary",
        "Submit Answers & Re-run",
        onClick --> { _ =>
          val answers = answersVar.now().map(_.now()).filter(_.nonEmpty)
          onSubmit(answers)
        },
      ),
    )

  private def resultsView(
    state: Signal[AppState],
    onAnalyze: String => Unit,
    onTranspile: () => Unit,
  ): HtmlElement =
    div(
      cls := "results-view",
      h2(child.text <-- state.map(_.resultTitle.getOrElse("Results"))),
      div(
        cls := "result-content",
        pre(child.text <-- state.map(_.resultExplanation.getOrElse(""))),
      ),
      div(
        cls := "result-actions",
        button(
          cls := "btn-secondary",
          "Edit & Iterate",
          onClick --> { _ => onAnalyze("") },
        ),
        child <-- state.map(_.resultOutcome).map {
          case Some("clean") =>
            button(
              cls := "btn-primary",
              "Transpile",
              onClick --> { _ => onTranspile() },
            )
          case _ => emptyNode
        },
      ),
    )

  // ---------------------------------------------------------------------------
  // Artifact Panel (Right)
  // ---------------------------------------------------------------------------

  def artifactPanel(state: Signal[AppState]): HtmlElement =
    val activeTab = Var("Log")
    div(
      cls := "artifact-panel",
      div(
        cls := "artifact-tabs",
        children <-- state.map(_.artifacts.keys.toList.sorted).map { tabs =>
          tabs.map { tab =>
            button(
              cls <-- activeTab.signal.map(a => if a == tab then "tab active" else "tab"),
              tab,
              onClick --> { _ => activeTab.set(tab) },
            )
          }
        },
      ),
      div(
        cls := "artifact-content",
        pre(
          child.text <-- activeTab.signal.combineWith(state.map(_.artifacts)).map {
            case (tab, arts) => arts.getOrElse(tab, "")
          },
        ),
      ),
    )

  // ---------------------------------------------------------------------------
  // Metrics Bar (Bottom)
  // ---------------------------------------------------------------------------

  def metricsBar(state: Signal[AppState]): HtmlElement =
    div(
      cls := "metrics-bar",
      children <-- state.map(_.metrics).map { timings =>
        timings.map { t =>
          span(cls := "metric-entry", s"${t.step} ${t.durationMs / 1000.0}%.1fs")
        }
      },
      child <-- state.map { s =>
        s.error.map(e => span(cls := "error-badge", s"Error: $e")).getOrElse(emptyNode)
      },
    )

package dev.sibylsystems.arkhitekton.web

import org.scalajs.dom
import org.scalajs.dom.XMLHttpRequest

import io.circe.syntax.*
import io.circe.parser.decode

import dev.sibylsystems.arkhitekton.protocol.*

/** HTTP client that POSTs commands and reads SSE event streams. */
object ApiClient:

  private val baseUrl = "" // same origin; override for dev

  /** Send a command to the pipeline endpoint and stream back events via SSE.
    *
    * Uses fetch() with a ReadableStream to read the SSE text/event-stream response
    * from a POST request (EventSource only supports GET).
    */
  def sendCommand(
    command: ClientCommand,
    onEvent: PipelineEvent => Unit,
    onDone: () => Unit,
    onError: String => Unit,
  ): Unit =
    val body = command.asJson.noSpaces
    val xhr  = new XMLHttpRequest()
    xhr.open("POST", s"$baseUrl/api/pipeline", async = true)
    xhr.setRequestHeader("Content-Type", "application/json")

    var buffer = ""

    xhr.onprogress = { (_: dom.Event) =>
      buffer += xhr.responseText.substring(buffer.length)
      processBuffer(buffer, onEvent)
    }

    xhr.onload = { (_: dom.Event) =>
      // Process any remaining data
      buffer = xhr.responseText
      processBuffer(buffer, onEvent)
      onDone()
    }

    xhr.onerror = { (_: dom.Event) =>
      onError(s"Request failed: ${xhr.statusText}")
    }

    xhr.send(body)

  /** Parse SSE-formatted text into individual events. */
  private def processBuffer(text: String, onEvent: PipelineEvent => Unit): Unit =
    text.split("\n\n").foreach { block =>
      val dataLine = block.linesIterator
        .filter(_.startsWith("data:"))
        .map(_.stripPrefix("data:").trim)
        .mkString
      if dataLine.nonEmpty then
        decode[PipelineEvent](dataLine) match
          case Right(event) => onEvent(event)
          case Left(_)      => () // skip malformed events
    }

  /** Check server health. */
  def healthCheck(onResult: Boolean => Unit): Unit =
    val xhr = new XMLHttpRequest()
    xhr.open("GET", s"$baseUrl/api/health", async = true)
    xhr.onload = { (_: dom.Event) => onResult(xhr.status == 200) }
    xhr.onerror = { (_: dom.Event) => onResult(false) }
    xhr.send()

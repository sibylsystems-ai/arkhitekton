package dev.sibylsystems.arkhitekton.core

import cats.effect.IO

/** Timing utilities for pipeline steps. */
object Metrics:

  /** Run an IO action and return the result paired with its elapsed time. */
  def timed[A](stepName: String)(action: IO[A]): IO[(A, StepTiming)] =
    for
      start  <- IO.monotonic
      result <- action
      end    <- IO.monotonic
      elapsed = (end - start).toMillis
    yield (result, StepTiming(stepName, elapsed))

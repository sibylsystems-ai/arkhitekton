package dev.sibylsystems.arkhitekton.server

import cats.effect.{ExitCode, IO, IOApp, Ref}

import com.comcast.ip4s.*
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{CORS, Logger as HttpLogger}

import dev.sibylsystems.arkhitekton.core.*

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    for
      apiKey <- loadApiKey
      backend = AnthropicHttpBackend(apiKey)
      model   = Models.resolve(Models.defaultModel)
      idris2  = sys.env.getOrElse("IDRIS2_PATH", "idris2")

      // Verify idris2 is available
      version <- Compiler.verify(idris2).flatMap {
                   case Some(v) => IO.println(s"Idris 2: $v").as(v)
                   case None    => IO.raiseError(new RuntimeException(s"idris2 not found at: $idris2"))
                 }

      staticDir = sys.env.get("STATIC_DIR").filter(d => java.nio.file.Files.isDirectory(java.nio.file.Path.of(d)))

      sessionRef <- Ref.of[IO, Option[Session]](None)
      handler     = PipelineHandler(backend, model, idris2, sessionRef)
      routes      = Routes(handler, idris2, staticDir)

      // Add CORS (allow browser frontend) and request logging
      app = CORS.policy.withAllowOriginAll(HttpLogger.httpRoutes[IO](
        logHeaders = false,
        logBody = false,
      )(routes))

      port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(8080)

      _ <- IO.println(s"arkhitekton-server starting on port $port (model: $model, idris2: $version)")

      _ <- EmberServerBuilder
             .default[IO]
             .withHost(host"0.0.0.0")
             .withPort(Port.fromInt(port).getOrElse(port"8080"))
             .withHttpApp(app.orNotFound)
             .build
             .useForever
    yield ExitCode.Success

  private def loadApiKey: IO[String] =
    IO.blocking {
      sys.env.get("ANTHROPIC_API_KEY")
        .filter(_.nonEmpty)
        .orElse(loadDotEnv("ANTHROPIC_API_KEY"))
    }.flatMap {
      case Some(key) => IO.pure(key)
      case None      => IO.raiseError(new RuntimeException(
        "ANTHROPIC_API_KEY required. Set in environment or .env file.",
      ))
    }

  private def loadDotEnv(key: String): Option[String] =
    val envFile = os.pwd / ".env"
    Option.when(os.exists(envFile))(os.read(envFile)).flatMap { content =>
      content.linesIterator
        .map(_.trim)
        .filterNot(l => l.isEmpty || l.startsWith("#"))
        .flatMap { line =>
          line.split("=", 2) match
            case Array(k, v) if k.trim == key =>
              Some(v.trim.stripPrefix("'").stripSuffix("'").stripPrefix("\"").stripSuffix("\""))
            case _ => None
        }
        .nextOption()
    }

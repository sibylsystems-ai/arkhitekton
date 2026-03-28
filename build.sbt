ThisBuild / scalaVersion    := "3.7.1"
ThisBuild / organization    := "dev.sibylsystems"
ThisBuild / dynverSeparator := "-"
ThisBuild / javacOptions ++= Seq("--release", "21")

lazy val root = (project in file("."))
  .settings(
    name := "arkhitekton",
    libraryDependencies ++= Seq(
      // CLI
      "com.monovore" %% "decline"        % "2.6.1",
      "com.monovore" %% "decline-effect" % "2.6.1",

      // FP
      "org.typelevel" %% "cats-core"   % "2.13.0",
      "org.typelevel" %% "cats-effect" % "3.7.0",

      // JSON
      "io.circe" %% "circe-core"    % "0.14.15",
      "io.circe" %% "circe-generic" % "0.14.15",
      "io.circe" %% "circe-parser"  % "0.14.15",

      // HTTP — sttp 4 + http4s/ember backend (fs2/CE3-native)
      "com.softwaremill.sttp.client4" %% "core"             % "4.0.19",
      "com.softwaremill.sttp.client4" %% "circe"            % "4.0.19",
      "com.softwaremill.sttp.client4" %% "http4s-backend"   % "4.0.19",
      "org.http4s"                    %% "http4s-ember-client" % "0.23.33",

      // Filesystem / Process
      "com.lihaoyi" %% "os-lib" % "0.11.8",

      // Logging — suppress SLF4J "no binding" warnings from http4s/ember
      "org.slf4j" % "slf4j-nop" % "1.7.36",

      // Test
      "org.scalameta" %% "munit"                   % "1.2.4"  % Test,
      "org.typelevel" %% "munit-cats-effect"        % "2.2.0"  % Test,
    ),
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions ++= Seq(
      "-deprecation",
      "-feature",
      "-unchecked",
      "-Wunused:all",
      "-Wvalue-discard",
    ),
    fork := true,
    run / envVars ++= sys.env.filter { case (k, _) =>
      k == "ANTHROPIC_API_KEY" || k == "IDRIS2_PATH" || k == "PATH" || k == "HOME"
    },

    // Assembly
    assembly / mainClass := Some("dev.sibylsystems.arkhitekton.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    },
  )

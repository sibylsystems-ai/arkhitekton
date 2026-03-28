import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

ThisBuild / scalaVersion    := "3.7.1"
ThisBuild / organization    := "dev.sibylsystems"
ThisBuild / dynverSeparator := "-"
ThisBuild / javacOptions ++= Seq("--release", "21")
ThisBuild / semanticdbEnabled := true
ThisBuild / semanticdbVersion := scalafixSemanticdb.revision
ThisBuild / scalacOptions ++= Seq(
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Wunused:all",
  "-Wvalue-discard",
)

val circeVersion  = "0.14.15"
val http4sVersion = "0.23.33"

lazy val root = (project in file("."))
  .aggregate(core, cli, server, web, protocol.jvm, protocol.js)
  .settings(
    name    := "arkhitekton",
    publish := {},
    // Root project has no sources — everything lives in modules/
    Compile / unmanagedSourceDirectories := Nil,
    Test / unmanagedSourceDirectories    := Nil,
  )

lazy val protocol = crossProject(JVMPlatform, JSPlatform)
  .crossType(CrossType.Pure)
  .in(file("modules/protocol"))
  .settings(
    name := "arkhitekton-protocol",
    libraryDependencies ++= Seq(
      "io.circe" %%% "circe-core"    % circeVersion,
      "io.circe" %%% "circe-generic" % circeVersion,
      "io.circe" %%% "circe-parser"  % circeVersion,

      // Test
      "org.scalameta" %%% "munit" % "1.2.4" % Test,
    ),
  )

lazy val core = (project in file("modules/core"))
  .dependsOn(protocol.jvm)
  .settings(
    name := "arkhitekton-core",
    libraryDependencies ++= Seq(
      // FP
      "org.typelevel" %% "cats-core"   % "2.13.0",
      "org.typelevel" %% "cats-effect" % "3.7.0",

      // JSON
      "io.circe" %% "circe-core"    % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser"  % circeVersion,

      // HTTP — sttp 4 + http4s/ember backend (fs2/CE3-native)
      "com.softwaremill.sttp.client4" %% "core"               % "4.0.19",
      "com.softwaremill.sttp.client4" %% "circe"              % "4.0.19",
      "com.softwaremill.sttp.client4" %% "http4s-backend"     % "4.0.19",
      "org.http4s"                    %% "http4s-ember-client" % "0.23.33",

      // Filesystem / Process
      "com.lihaoyi" %% "os-lib" % "0.11.8",

      // Logging — suppress SLF4J "no binding" warnings from http4s/ember
      "org.slf4j" % "slf4j-nop" % "1.7.36",

      // Test
      "org.scalameta" %% "munit"            % "1.2.4" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
    ),
  )

lazy val cli = (project in file("modules/cli"))
  .dependsOn(core)
  .settings(
    name := "arkhitekton-cli",
    libraryDependencies ++= Seq(
      // CLI
      "com.monovore" %% "decline"        % "2.6.1",
      "com.monovore" %% "decline-effect" % "2.6.1",

      // Test
      "org.scalameta" %% "munit"            % "1.2.4" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
    ),
    fork := true,
    run / envVars ++= sys.env.filter { case (k, _) =>
      k == "ANTHROPIC_API_KEY" || k == "IDRIS2_PATH" || k == "PATH" || k == "HOME"
    },

    // Assembly
    assembly / mainClass := Some("dev.sibylsystems.arkhitekton.cli.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    },
  )

lazy val server = (project in file("modules/server"))
  .dependsOn(core)
  .settings(
    name := "arkhitekton-server",
    libraryDependencies ++= Seq(
      // HTTP server
      "org.http4s" %% "http4s-ember-server" % http4sVersion,
      "org.http4s" %% "http4s-dsl"          % http4sVersion,
      "org.http4s" %% "http4s-circe"        % http4sVersion,

      // Test
      "org.scalameta" %% "munit"            % "1.2.4" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.0" % Test,
    ),
    fork := true,
    run / envVars ++= sys.env.filter { case (k, _) =>
      k == "ANTHROPIC_API_KEY" || k == "IDRIS2_PATH" || k == "PATH" || k == "HOME"
    },

    // Assembly
    assembly / mainClass := Some("dev.sibylsystems.arkhitekton.server.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*) => MergeStrategy.discard
      case _                        => MergeStrategy.first
    },
  )

lazy val web = (project in file("modules/web"))
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(protocol.js)
  .settings(
    name := "arkhitekton-web",
    scalaJSUseMainModuleInitializer := true,
    scalaJSLinkerConfig ~= { _.withModuleKind(ModuleKind.ESModule) },
    libraryDependencies ++= Seq(
      "com.raquo" %%% "laminar"  % "17.2.1",
      "com.raquo" %%% "waypoint" % "9.0.0",
      "io.circe"  %%% "circe-parser" % circeVersion,
    ),
  )

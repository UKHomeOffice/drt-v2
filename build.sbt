
import FrontendDependencies.scalajsReactVersion
import com.typesafe.config.ConfigFactory
import net.nmoncho.sbt.dependencycheck.DependencyCheckPlugin.autoImport.*
import net.nmoncho.sbt.dependencycheck.settings.{AnalyzerSettings, NvdApiSettings}
import org.scalajs.jsenv.Input
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.interface.ModuleSplitStyle
import sbt.Credentials
import sbt.Keys.credentials
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

ThisBuild / organization := "uk.gov.homeoffice.drt"
ThisBuild / scalaVersion := "2.13.18"

val drtv2Name = "DRTv2"
val drtv2Version = sys.env.getOrElse("DRONE_BUILD_NUMBER", sys.env.getOrElse("BUILD_ID", "dev"))

val frontendClientMacrosSettings: Seq[Def.Setting[?]] = Seq(
    name := "clientMacrosJS",
    version := drtv2Version,
    scalacOptions ++= ScalaCompilerSettings.scalacOptions,
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core" % scalajsReactVersion withSources(),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion withSources()
    ),
    resolvers += Resolver.defaultLocal,
)

val frontendClientSettings: Seq[Def.Setting[?]] = Seq(
    name := "client",
    version := drtv2Version,
    libraryDependencies ++= FrontendDependencies.dependencies.value,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("drt.client.SPAMain"),

    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(
          ModuleSplitStyle.SmallModulesFor(List("drt.client")))
    },

    zonesFilter := { (z: String) => z == "Europe/London" },

    Test / scalaJSStage := FastOptStage,

    Test / scalaJSLinkerConfig ~= { cfg =>
      cfg
        .withModuleKind(ModuleKind.CommonJSModule)
        .withModuleSplitStyle(ModuleSplitStyle.FewestModules)
        .withSourceMap(true)
    },

    // Make Node see client/node_modules while running tests
    Test / jsEnv := {
      val cfg = NodeJSEnv.Config()
        .withArgs(List("--trace-uncaught", "--unhandled-rejections=strict"))
        .withEnv(Map("NODE_PATH" -> (baseDirectory.value / "node_modules").getAbsolutePath))
      new NodeJSEnv(cfg)
    },

    // Prepend dom-setup.js so it runs before the linked test module.
    Test / jsEnvInput := {
      val base = (Test / jsEnvInput).value
      val domSetup = Input.Script(((Test / resourceDirectory).value / "dom-setup.js").toPath)
      domSetup +: base
    },

    resolvers += Resolver.defaultLocal,
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),

    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaJSUseMainModuleInitializer := true,
    Compile / doc / sources := List(),
)

val backendServerSettings: Seq[Def.Setting[?]] = Seq(
    name := "drt",
    version := drtv2Version,
    scalacOptions ++= ScalaCompilerSettings.scalacOptions,
    Test / javaOptions += "-Duser.timezone=UTC",
    Test / javaOptions += "-Xmx1750m",
    Runtime / javaOptions += "-Duser.timezone=UTC",
    libraryDependencies ++= BackendDependencies.all,
    libraryDependencies += specs2 % Test,
    libraryDependencies += guice,
    excludeDependencies += ExclusionRule("org.slf4j", "slf4j-log4j12"),

    dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",

    commands += ReleaseCmd,
    scalaJSProjects := Seq(client),
    Assets / pipelineStages := Seq(scalaJSPipeline),
    pipelineStages := Seq(digest, gzip),

    Compile / compile := ((Compile / compile) dependsOn scalaJSPipeline).value,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    resolvers ++= Seq(
      Resolver.defaultLocal,
      "Akka library repository".at("https://repo.akka.io/maven"),
      "Artifactory Realm release" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
      "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
      "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
      "Sonatype OSS Releases" at "https://oss.sonatype.org/content/repositories/releases/",
    ),
    Compile / packageBin / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // compress CSS
    Assets / LessKeys.compress := true,
    TwirlKeys.templateImports ++= Seq(
      "buildinfo._",
    ),
    Compile / doc / sources := List(),
)

lazy val drtv2 = (project in file("."))
  .settings(name := drtv2Name)
  .aggregate(server, client, shared.jvm, shared.js)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    libraryDependencies ++= SharedDependencies.dependencies.value,
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  )
  .settings(CodeCoverageSettings.codeCoverageSettings *)
  .settings(WartRemoverSettings.wartRemoverSettings *)
  .settings(SbtUpdatesSettings.sbtUpdatesSettings *)
  .jsConfigure(_.enablePlugins(ScalaJSWeb))

lazy val clientMacrosJS: Project = (project in file("client-macros"))
  .settings(frontendClientMacrosSettings *)
  .settings(CodeCoverageSettings.codeCoverageSettings *)
  .settings(WartRemoverSettings.wartRemoverSettings *)
  .settings(SbtUpdatesSettings.sbtUpdatesSettings *)
  .enablePlugins(ScalaJSPlugin, ScalaJSWeb)

lazy val client: Project = (project in file("client"))
  .settings(frontendClientSettings *)
  .settings(CodeCoverageSettings.codeCoverageSettings *)
  .settings(WartRemoverSettings.wartRemoverSettings *)
  .settings(SbtUpdatesSettings.sbtUpdatesSettings *)
  .enablePlugins(ScalaJSPlugin, ScalaJSWeb, TzdbPlugin)
  .dependsOn(shared.js, clientMacrosJS)

lazy val server = (project in file("server"))
  .settings(backendServerSettings *)
  .settings(CodeCoverageSettings.codeCoverageSettings *)
  .settings(WartRemoverSettings.wartRemoverSettings *)
  .settings(SbtUpdatesSettings.sbtUpdatesSettings *)
  .enablePlugins(PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
  .dependsOn(shared.jvm)

ThisBuild / dependencyCheckAnalyzers := dependencyCheckAnalyzers.value.copy(
  ossIndex = AnalyzerSettings.OssIndex(
    enabled = Some(false),
    url = None,
    batchSize = None,
    requestDelay = None,
    useCache = None,
    warnOnlyOnRemoteErrors = None,
    username = None,
    password = None
  )
)

val nvdAPIKey = sys.env.getOrElse("NVD_API_KEY", "")

ThisBuild / dependencyCheckNvdApi := NvdApiSettings(apiKey = nvdAPIKey)


// Command for building a release
lazy val ReleaseCmd = Command.command("release") {
  state =>
    "set elideOptions in client := Seq(\"-Xelide-below\", \"WARNING\")" ::
      "server/dist" ::
      "set elideOptions in client := Seq()" ::
      state
}

Global / cancelable := true

val conf = ConfigFactory.parseFile(new File("server/src/main/resources/application.conf")).resolve()

lazy val slick = TaskKey[Seq[File]]("gen-tables")
val tuple = (sourceManaged, Compile / dependencyClasspath, Compile / runner, streams)

addCommandAlias("scalafmtAll", "all scalafmtSbt scalafmt Test/scalafmt")

// loads the Play server project at sbt startup
Global / onLoad := (Command.process("project server", _: State)) compose (Global / onLoad).value

// Docker Plugin§
enablePlugins(DockerPlugin)
// enabled for Alpine JVM docker image compatibility
enablePlugins(AshScriptPlugin)


import Settings.versions.scalajsReact
import com.typesafe.config.ConfigFactory
import net.nmoncho.sbt.dependencycheck.settings.NvdApiSettings
import org.scalajs.linker.interface.ModuleSplitStyle
import sbt.Credentials
import sbt.Keys.credentials
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}


scalaVersion := Settings.versions.scala

lazy val drtv2 = (project in file("."))
  .aggregate(server, client, shared.jvm, shared.js)

lazy val shared = crossProject(JSPlatform, JVMPlatform)
  .crossType(CrossType.Pure)
  .in(file("shared"))
  .settings(
    scalaVersion := Settings.versions.scala,
    libraryDependencies ++= Settings.sharedDependencies.value,
    resolvers += "Akka library repository".at("https://repo.akka.io/maven"),
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  )
  .jsConfigure(_.enablePlugins(ScalaJSWeb))

lazy val clientMacrosJS: Project = (project in file("client-macros"))
  .settings(
    name := "clientMacrosJS",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core" % scalajsReact withSources(),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReact withSources()
    ),
    resolvers += Resolver.defaultLocal,
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSWeb)

lazy val client: Project = (project in file("client"))
  .settings(
    name := "client",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,

    libraryDependencies ++= Settings.scalajsDependencies.value,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("drt.client.SPAMain"),

    /* Configure Scala.js to emit modules in the optimal way to
     * connect to Vite's incremental reload.
     * - emit ECMAScript modules
     * - emit as many small modules as possible for classes in the "drt-client" package
     * - emit as few (large) modules as possible for all other classes
     *   (in particular, for the standard library)
     */
    scalaJSLinkerConfig ~= {
      _.withModuleKind(ModuleKind.ESModule)
        .withModuleSplitStyle(
          ModuleSplitStyle.SmallModulesFor(List("drt.client")))
    },

    zonesFilter := {(z: String) => z == "Europe/London"},

    Test / scalaJSStage := FastOptStage,

    resolvers += Resolver.defaultLocal,
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),

    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaJSUseMainModuleInitializer := true,
    Test / parallelExecution := false,
    Compile / doc / sources := List(),
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSWeb, TzdbPlugin)
  .dependsOn(shared.js, clientMacrosJS)

lazy val server = (project in file("server"))
  .settings(
    name := "drt",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    Test / javaOptions += "-Duser.timezone=UTC",
    Test / javaOptions += "-Xmx1750m",
    Runtime / javaOptions += "-Duser.timezone=UTC",
    libraryDependencies ++= Settings.jvmDependencies.value,
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
      Resolver.bintrayRepo("dwhjames", "maven"),
      Resolver.bintrayRepo("mfglabs", "maven"),
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
    Test / parallelExecution := false,
    Compile / doc / sources := List(),
  )
  .enablePlugins(PlayScala)
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
  .dependsOn(shared.jvm)

// Command for building a release
lazy val ReleaseCmd = Command.command("release") {
  state =>
    "set elideOptions in client := Seq(\"-Xelide-below\", \"WARNING\")" ::
      "server/dist" ::
      "set elideOptions in client := Seq()" ::
      state
}
val nvdBaseUrl = sys.env.getOrElse("NVD_BASE_URL", "http://localhost:8008")
val nvdAPIKey = sys.env.getOrElse("NVD_API_KEY", "10dbca30-1fdc-4e7b-915c-fdfac0e9b1d1")
Global / cancelable := true

// code generation task
val conf = ConfigFactory.parseFile(new File("server/src/main/resources/application.conf")).resolve()

lazy val slick = TaskKey[Seq[File]]("gen-tables")
val tuple = (sourceManaged, Compile / dependencyClasspath, Compile / runner, streams)

Test / parallelExecution := false
// loads the Play server project at sbt startup
Global / onLoad := (Command.process("project server", _: State)) compose (Global / onLoad).value

// Docker Plugin§
enablePlugins(DockerPlugin)
// enabled for Alpine JVM docker image compatibility
enablePlugins(AshScriptPlugin)

dependencyCheckNvdApi := NvdApiSettings(nvdAPIKey)
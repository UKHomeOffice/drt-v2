
import Settings.versions.scalajsReact
import com.typesafe.config._
import sbt.Credentials
import sbt.Keys.{credentials, _}
import sbt.Project.projectToRef
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}
import com.sksamuel.scapegoat.sbt.ScapegoatSbtPlugin.autoImport._

scalaVersion := Settings.versions.scala
ThisBuild / scapegoatVersion := "2.1.1"
// uncomment the following to get a breakdown  of where build time is spent
//enablePlugins(net.virtualvoid.optimizer.SbtOptimizerPlugin)

// a special crossProject for configuring a JS/JVM/shared structure
lazy val shared = (crossProject(JSPlatform, JVMPlatform).crossType(CrossType.Pure) in file("shared"))
  .settings(
    scalaVersion := Settings.versions.scala,
    libraryDependencies ++= Settings.sharedDependencies.value,
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  )
  // set up settings specific to the JS project
  .jsConfigure(_ enablePlugins ScalaJSWeb)

val bundle = project.in(file("bundle"))

addCommandAlias("bundle", "bundle/bundle")

lazy val sharedJVM = shared.jvm.settings(name := "sharedJVM")

lazy val sharedJS = shared.js.settings(name := "sharedJS")

// use eliding to drop some debug code in the production build
lazy val elideOptions = settingKey[Seq[String]]("Set limit for elidable functions")

lazy val clientMacrosJS: Project = (project in file("client-macros"))
  .enablePlugins(ScalaJSPlugin)
  .settings(
    name := "clientMacrosJS",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Seq(
      "com.github.japgolly.scalajs-react" %%% "core" % scalajsReact withSources(),
      "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReact withSources()
    )
  )


// instantiate the JS project for SBT with some additional settings
lazy val client: Project = (project in file("client"))
  .settings(
    name := "client",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Settings.scalajsDependencies.value,
    scalaJSUseMainModuleInitializer := true,
    Compile / mainClass := Some("drt.client.SPAMain"),
    webpackBundlingMode := BundlingMode.LibraryOnly(),
    webpack / version := "5.75.0",
    // by default we do development build, no eliding
    elideOptions := Seq(),
    scalacOptions ++= elideOptions.value,
    jsDependencies ++= Settings.jsDependencies.value,
    // reactjs testing
    Test / requireJsDomEnv := true,
    Test / scalaJSStage := FastOptStage,
    // 'new style js dependencies with scalaBundler'
    Compile / npmDependencies ++= Settings.clientNpmDependencies,
    Compile / npmDevDependencies += Settings.clientNpmDevDependencies,
    // RuntimeDOM is needed for tests
    useYarn := true,
    // yes, we want to package JS dependencies
    packageJSDependencies / skip := false,
    resolvers ++= Resolver.sonatypeOssRepos("snapshots"),
    resolvers += Resolver.defaultLocal,
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // use uTest framework for tests
    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaJSUseMainModuleInitializer := true,
    Test / parallelExecution := false,
    Compile / doc / sources := List()
  )
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
  .enablePlugins(ScalaJSWeb)
  .dependsOn(sharedJS, clientMacrosJS)

// Client projects (just one in this case)
lazy val clients = Seq(client)

// instantiate the JVM project for SBT with some additional settings
lazy val server = (project in file("server"))
  .enablePlugins(PlayScala)
  .enablePlugins(WebScalaJSBundlerPlugin)
  .enablePlugins(BuildInfoPlugin)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
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

    dependencyOverrides += "org.scala-lang.modules" %% "scala-parser-combinators" % "2.1.1",

    commands += ReleaseCmd,
    // connect to the client project
    scalaJSProjects := clients,
    Assets / pipelineStages := Seq(scalaJSPipeline),
    // triggers scalaJSPipeline when using compile or continuous compilation
    Compile / compile := ((Compile / compile) dependsOn scalaJSPipeline).value,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    resolvers += Resolver.bintrayRepo("dwhjames", "maven"),
    resolvers += Resolver.bintrayRepo("mfglabs", "maven"),
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/",
    resolvers += "Artifactory Realm release local" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local/",
    resolvers += "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    Compile / packageBin / publishArtifact := false,
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // compress CSS
    Assets / LessKeys.compress := true,
    TwirlKeys.templateImports += "buildinfo._",
    Test / parallelExecution := false,
    Compile / doc / sources := List()
  )
  .aggregate(clients.map(projectToRef): _*)
  .dependsOn(sharedJVM)

// Command for building a release
lazy val ReleaseCmd = Command.command("release") {
  state =>
    "set elideOptions in client := Seq(\"-Xelide-below\", \"WARNING\")" ::
      "server/dist" ::
      "set elideOptions in client := Seq()" ::
      state
}

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

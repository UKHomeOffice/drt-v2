import sbt.Keys._
import sbt.Project.projectToRef

scalaVersion := Settings.versions.scala

enablePlugins(net.virtualvoid.optimizer.SbtOptimizerPlugin)

// a special crossProject for configuring a JS/JVM/shared structure
lazy val shared = (crossProject.crossType(CrossType.Pure) in file("shared"))
  .settings(
    scalaVersion := Settings.versions.scala,
    libraryDependencies ++= Settings.sharedDependencies.value
  )
  // set up settings specific to the JS project
  .jsConfigure(_ enablePlugins ScalaJSWeb)

val bundle = project.in(file("bundle"))

addCommandAlias("bundle", "bundle/bundle")

lazy val sharedJVM = shared.jvm.settings(name := "sharedJVM")

lazy val sharedJS = shared.js.settings(name := "sharedJS")

// use eliding to drop some debug code in the production build
lazy val elideOptions = settingKey[Seq[String]]("Set limit for elidable functions")

// instantiate the JS project for SBT with some additional settings
lazy val client: Project = (project in file("client"))
  .settings(
    name := "client",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Settings.scalajsDependencies.value,
    // by default we do development build, no eliding
    elideOptions := Seq(),
    scalacOptions ++= elideOptions.value,
    jsDependencies ++= Settings.jsDependencies.value,
    // reactjs testing
    requiresDOM := true,
    scalaJSStage in Test := FastOptStage,
    // 'new style js dependencies with scalaBundler'
    npmDependencies in Compile ++= Settings.clientNpmDependences,
    npmDevDependencies in Compile += Settings.clientNpmDevDependencies,
    // RuntimeDOM is needed for tests
    jsDependencies += RuntimeDOM % "test",
    // yes, we want to package JS dependencies
    skip in packageJSDependencies := false,
    testOptions in Test += Tests.Argument("-verbosity", "2"),
    // use Scala.js provided launcher code to start the client app
    //    scalaJSUseMainModuleInitializer := true,
    //    persistLaunch/ser := true,
    //    scalaJSUseMsainModuleInitializer in Test := false,
    //    webpackConfigFile := Some(baseDirectory.value / "custom.webpack.config.js"),
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.defaultLocal,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // use uTest framework for tests
    testFrameworks += new TestFramework("utest.runner.Framework")
  )
  .enablePlugins(ScalaJSBundlerPlugin, ScalaJSWeb)
  .dependsOn(sharedJS)

// Client projects (just one in this case)
lazy val clients = Seq(client)

// instantiate the JVM project for SBT with some additional settings
lazy val server = (project in file("server"))
  .enablePlugins(PlayScala, WebScalaJSBundlerPlugin)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
  .settings(
  name := "drt",
  version := Settings.version,
  scalaVersion := Settings.versions.scala,
  scalacOptions ++= Settings.scalacOptions,
  javaOptions in Test += "-Duser.timezone=UTC",
  javaOptions in Runtime += "-Duser.timezone=UTC",
  libraryDependencies ++= Settings.jvmDependencies.value,
  commands += ReleaseCmd,
  // connect to the client project
  scalaJSProjects := clients,
  pipelineStages := Seq(scalaJSProd, digest, gzip),
  pipelineStages in Assets := Seq(scalaJSPipeline),
  // triggers scalaJSPipeline when using compile or continuous compilation
  compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
  testFrameworks += new TestFramework("utest.runner.Framework"),
  resolvers ++= Seq(
    "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    Resolver.bintrayRepo("mfglabs", "maven"),
    Resolver.bintrayRepo("dwhjames", "maven"),
    "release" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local",
    Resolver.defaultLocal),
  publishArtifact in(Compile, packageBin) := false,
  // Disable scaladoc generation for this project (useless)
  publishArtifact in(Compile, packageDoc) := false,
  // Disable source jar for this project (useless)
  publishArtifact in(Compile, packageSrc) := false,
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  // compress CSS
  testOptions in Test += Tests.Argument("-verbosity", "2"),
  LessKeys.compress in Assets := true,
  PB.targets in Compile := Seq(
    scalapb.gen() -> (sourceManaged in Compile).value
  )
)
  .aggregate(clients.map(projectToRef): _*)
  .dependsOn(sharedJVM)



//server.resolvers  ++= Seq(
//  Resolver.bintrayRepo("mfglabs", "maven"),
//  Resolver.bintrayRepo("dwhjames", "maven"),
//  Resolver.sonatypeRepo("snapshots")
//)

// Command for building a release
lazy val ReleaseCmd = Command.command("release") {
  state =>
    "set elideOptions in client := Seq(\"-Xelide-below\", \"WARNING\")" ::
      "client/clean" ::
      "client/test" ::
      "server/clean" ::
      "server/test" ::
      "server/dist" ::
      "set elideOptions in client := Seq()" ::
      state
}

testOptions in Test += Tests.Argument("-verbosity", "1")

fork in run := true

fork in Test := true

// loads the Play server project at sbt startup
onLoad in Global := (Command.process("project server", _: State)) compose (onLoad in Global).value

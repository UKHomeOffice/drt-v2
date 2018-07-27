import org.scalajs.core.tools.linker.ModuleInitializer
import sbt.Keys._
import sbt.Project.projectToRef

scalaVersion := Settings.versions.scala

// uncomment the following to get a breakdown  of where build time is spent
//enablePlugins(net.virtualvoid.optimizer.SbtOptimizerPlugin)
// enabled for Apline JVM docker image compatibility 
enablePlugins(AshScriptPlugin)
// a special crossProject for configuring a JS/JVM/shared structure
import sbtcrossproject.CrossPlugin.autoImport.{crossProject, CrossType}

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
    ivyLoggingLevel := UpdateLogging.Quiet,
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Settings.scalajsDependencies.value,
    scalaJSUseMainModuleInitializer := true,
    mainClass in Compile := Some("drt.client.SPAMain"),
    webpackBundlingMode := BundlingMode.LibraryOnly(),
    version in webpack := "4.8.1",
    // by default we do development build, no eliding
    elideOptions := Seq(),
    scalacOptions ++= elideOptions.value,
    jsDependencies ++= Settings.jsDependencies.value,
    // reactjs testing
    jsEnv := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
    scalaJSStage in Test := FastOptStage,
    // 'new style js dependencies with scalaBundler'
    npmDependencies in Compile ++= Settings.clientNpmDependences,
    npmDevDependencies in Compile += Settings.clientNpmDevDependencies,
    // RuntimeDOM is needed for tests
    jsEnv in Test := new org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv(),
    useYarn := true,
    // yes, we want to package JS dependencies
    skip in packageJSDependencies := false,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.defaultLocal,
    // use uTest framework for tests
    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaJSUseMainModuleInitializer := true
  )
  .enablePlugins(ScalaJSPlugin)
  .enablePlugins(ScalaJSBundlerPlugin)
  .enablePlugins(ScalaJSWeb)
  .dependsOn(sharedJS)

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
  buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
  buildInfoPackage := "buildinfo",
  javaOptions in Test += "-Duser.timezone=UTC",
  javaOptions in Runtime += "-Duser.timezone=UTC",
  libraryDependencies ++= Settings.jvmDependencies.value,
  libraryDependencies += guice,
  dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-core" % "2.8.7",
  dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-databind" % "2.8.7",
  dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.11" % "2.8.7",
  dependencyOverrides += "com.github.dwhjames" %% "aws-wrap" % "0.9.0",
  commands += ReleaseCmd,
  // connect to the client project
  scalaJSProjects := clients,
  pipelineStages := Seq(scalaJSProd, digest, gzip),
  pipelineStages in Assets := Seq(scalaJSPipeline),
  // triggers scalaJSPipeline when using compile or continuous compilation
  compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
  testFrameworks += new TestFramework("utest.runner.Framework"),
/*  resolvers ++= Seq(
    "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    "Artifactory Release Realm" at "http://artifactory.registered-traveller.homeoffice.gov.uk/artifactory/libs-release-local/",
    Resolver.bintrayRepo("mfglabs", "maven"),
    Resolver.bintrayRepo("dwhjames", "maven"),
    Resolver.defaultLocal),
*/    
  publishArtifact in(Compile, packageBin) := false,
  // Disable scaladoc generation for this project (useless)
  publishArtifact in(Compile, packageDoc) := false,
  // Disable source jar for this project (useless)
  publishArtifact in(Compile, packageSrc) := false,
  credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
  // compress CSS
  LessKeys.compress in Assets := true,
  PB.targets in Compile := Seq(
    scalapb.gen() -> (sourceManaged in Compile).value / "protobuf"
  ),
  TwirlKeys.templateImports += "buildinfo._"
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

fork in run := true
fork in Test := true



// loads the Play server project at sbt startup
onLoad in Global := (Command.process("project server", _: State)) compose (onLoad in Global).value

// Docker Plugin
enablePlugins(DockerPlugin)
updateOptions := updateOptions.value.withCachedResolution(true)

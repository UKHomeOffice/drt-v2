import sbt.Keys._
import sbt.Project.projectToRef

scalaVersion := Settings.versions.scala

// a special crossProject for configuring a JS/JVM/shared structure
lazy val shared = (crossProject.crossType(CrossType.Pure) in file("shared"))
  .settings(
    scalaVersion := Settings.versions.scala,
    libraryDependencies ++= Settings.sharedDependencies.value
  )
  // set up settings specific to the JS project
  .jsConfigure(_ enablePlugins ScalaJSPlay)

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
    // RuntimeDOM is needed for tests
    jsDependencies += RuntimeDOM % "test",
    jsDependencies += ProvidedJS / "bundle.js",
    // yes, we want to package JS dependencies
    skip in packageJSDependencies := false,
    // use Scala.js provided launcher code to start the client app
    persistLauncher := true,
    persistLauncher in Test := false,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += "release" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local",
    resolvers += Resolver.defaultLocal,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // use uTest framework for tests
    testFrameworks += new TestFramework("utest.runner.Framework")
  )
  .enablePlugins(ScalaJSPlugin, ScalaJSPlay)
  .dependsOn(sharedJS)

// Client projects (just one in this case)
lazy val clients = Seq(client)


lazy val serverMacros = (project in file("server-macros"))
  .settings(
      name := "drtmacros",
      version := Settings.version,
      scalaVersion := Settings.versions.scala,
      scalacOptions ++= Settings.scalacOptions,
      libraryDependencies ++= Settings.jvmDependencies.value,
      commands += ReleaseCmd,
      // connect to the client project
      scalaJSProjects := clients,
      pipelineStages := Seq(scalaJSProd, digest, gzip),
      testFrameworks += new TestFramework("utest.runner.Framework"),
      resolvers += "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
      resolvers += "release" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local",
      resolvers += Resolver.defaultLocal,
      publishArtifact in (Compile, packageBin) := false,
      // Disable scaladoc generation for this project (useless)
      publishArtifact in (Compile, packageDoc) := false,
      // Disable source jar for this project (useless)
      publishArtifact in (Compile, packageSrc) := false,
      credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
      // compress CSS
      LessKeys.compress in Assets := true
  )
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
  .aggregate(clients.map(projectToRef): _*)
  .dependsOn(sharedJVM)


// instantiate the JVM project for SBT with some additional settings
lazy val server = (project in file("server"))
  .settings(
    name := "drt",
    version := Settings.version,
    scalaVersion := Settings.versions.scala,
    scalacOptions ++= Settings.scalacOptions,
    libraryDependencies ++= Settings.jvmDependencies.value,
    commands += ReleaseCmd,
    // connect to the client project
    scalaJSProjects := clients,
    pipelineStages := Seq(scalaJSProd, digest, gzip),
    testFrameworks += new TestFramework("utest.runner.Framework"),
    resolvers += "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    resolvers += "release" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local",
    resolvers += Resolver.defaultLocal,
    publishArtifact in (Compile, packageBin) := false,
    // Disable scaladoc generation for this project (useless)
    publishArtifact in (Compile, packageDoc) := false,
    // Disable source jar for this project (useless)
    publishArtifact in (Compile, packageSrc) := false,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // compress CSS
    LessKeys.compress in Assets := true
  )
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin) // use the standard directory layout instead of Play's custom
  .aggregate(clients.map(projectToRef): _*)
  .dependsOn(sharedJVM, serverMacros)

// Command for building a release
lazy val ReleaseCmd = Command.command("release") {
  state => "set elideOptions in client := Seq(\"-Xelide-below\", \"WARNING\")" ::
    "client/clean" ::
    "client/test" ::
    "server/clean" ::
    "server/test" ::
    "server/dist" ::
    "set elideOptions in client := Seq()" ::
    state
}

fork in run := true

fork in Test := true

// loads the Play server project at sbt startup
onLoad in Global := (Command.process("project server", _: State)) compose (onLoad in Global).value

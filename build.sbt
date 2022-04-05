
import Settings.versions.scalajsReact
import com.typesafe.config._
import sbt.Credentials
import sbt.Keys.{credentials, _}
import sbt.Project.projectToRef
import sbtcrossproject.CrossPlugin.autoImport.{CrossType, crossProject}

scalaVersion := Settings.versions.scala

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
    mainClass in Compile := Some("drt.client.SPAMain"),
    webpackBundlingMode := BundlingMode.LibraryOnly(),
    version in webpack := "4.8.1",
    // by default we do development build, no eliding
    elideOptions := Seq(),
    scalacOptions ++= elideOptions.value,
    jsDependencies ++= Settings.jsDependencies.value,
    // reactjs testing
    requireJsDomEnv in Test := true,
    scalaJSStage in Test := FastOptStage,
    // 'new style js dependencies with scalaBundler'
    npmDependencies in Compile ++= Settings.clientNpmDependencies,
    npmDevDependencies in Compile += Settings.clientNpmDevDependencies,
    // RuntimeDOM is needed for tests
    useYarn := true,
    // yes, we want to package JS dependencies
    skip in packageJSDependencies := false,
    resolvers += Resolver.sonatypeRepo("snapshots"),
    resolvers += Resolver.defaultLocal,
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release/",
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // use uTest framework for tests
    testFrameworks += new TestFramework("utest.runner.Framework"),
    scalaJSUseMainModuleInitializer := true,
    parallelExecution in Test := false,
    sources in doc in Compile := List()
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
    buildInfoKeys := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
    buildInfoPackage := "buildinfo",
    javaOptions in Test += "-Duser.timezone=UTC",
    javaOptions in Test += "-Xmx1750m",
    javaOptions in Runtime += "-Duser.timezone=UTC",
    libraryDependencies ++= Settings.jvmDependencies.value,
    libraryDependencies += specs2 % Test,
    libraryDependencies += guice,
    excludeDependencies += ExclusionRule("org.slf4j", "slf4j-log4j12"),
    dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-core" % "2.8.7",
    dependencyOverrides += "com.fasterxml.jackson.core" % "jackson-databind" % "2.8.7",
    dependencyOverrides += "com.fasterxml.jackson.module" % "jackson-module-scala_2.11" % "2.8.7",
    dependencyOverrides += "io.netty" % "netty-all" % "4.0.56.Final",

    commands += ReleaseCmd,
    // connect to the client project
    scalaJSProjects := clients,
    pipelineStages in Assets := Seq(scalaJSPipeline),
    // triggers scalaJSPipeline when using compile or continuous compilation
    compile in Compile := ((compile in Compile) dependsOn scalaJSPipeline).value,
    testFrameworks += new TestFramework("utest.runner.Framework"),
    resolvers += Resolver.bintrayRepo("dwhjames", "maven"),
    resolvers += Resolver.bintrayRepo("mfglabs", "maven"),
    resolvers += "Artifactory Realm" at "https://artifactory.digital.homeoffice.gov.uk/",
    resolvers += "Artifactory Realm release local" at "https://artifactory.digital.homeoffice.gov.uk/artifactory/libs-release-local/",
    resolvers += "BeDataDriven" at "https://nexus.bedatadriven.com/content/groups/public",
    resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots",
    //dependencyOverrides += "com.github.dwhjames" %% "aws-wrap" % "0.9.0",
    publishArtifact in(Compile, packageBin) := false,
    // Disable scaladoc generation for this project (useless)
    publishArtifact in(Compile, packageDoc) := false,
    // Disable source jar for this project (useless)
    publishArtifact in(Compile, packageSrc) := false,
    credentials += Credentials(Path.userHome / ".ivy2" / ".credentials"),
    // compress CSS
    LessKeys.compress in Assets := true,
    TwirlKeys.templateImports += "buildinfo._",
    parallelExecution in Test := false,
    sources in doc in Compile := List()
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

cancelable in Global := true

// code generation task
val conf = ConfigFactory.parseFile(new File("server/src/main/resources/application.conf")).resolve()

lazy val slick = TaskKey[Seq[File]]("gen-tables")
val tuple = (sourceManaged, dependencyClasspath in Compile, runner in Compile, streams)

parallelExecution in Test := false
// loads the Play server project at sbt startup
onLoad in Global := (Command.process("project server", _: State)) compose (onLoad in Global).value

// Docker Plugin§
enablePlugins(DockerPlugin)
// enabled for Alpine JVM docker image compatibility
enablePlugins(AshScriptPlugin)

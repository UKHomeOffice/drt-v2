import sbt._
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._
import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt.Keys.libraryDependencies

/**
  * Application settings. Configure the build for your application here.
  * You normally don't have to touch the actual build definition after this.
  */
object Settings {
  /** The name of your application */
  val name = "scalajs-spa"

  /** The version of your application */
  val version = sys.env.getOrElse("BUILD_ID", "dev")

  /** Options for the scala compiler */
  val scalacOptions = Seq(
    "-Xlint",
    "-unchecked",
    "-deprecation",
    "-feature"
  )

  /** Declare global dependency versions here to avoid mismatches in multi part dependencies */
  object versions {
    val scala = "2.11.8"
    val scalaDom = "0.9.6"
    val scalajsReact = "1.1.1"
    val scalajsReactComponents = "0.8.0"
    val scalaJsScripts = "1.0.0"
    val scalaCSS = "0.5.5"
    val scalaJsMomentJs = "0.9.2"
    val autowire = "0.2.6"
    val booPickle = "1.2.6"
    val diode = "1.1.3"
    val uTest = "0.6.3"

    val akka = "2.5.13"
    val akkaStreamContrib = "0.9"

    val specs2 = "3.7"
    val react = "15.5.4"
    val reactTestUtils = "15.4.1"

    val bootstrap = "3.3.6"

    val playScripts = "0.5.0"
    val sprayVersion = "1.3.4"
    val levelDb = "0.7"
    val levelDbJni = "1.8"
    val renjin = "0.9.2646"
    val awsSdk = "1.11.89"
    val awsCommons = "0.10.0"
    val csvCommons = "1.4"
    val pprint = "0.4.3"
    val scalaCheck = "1.13.4"
    val akkaPersistenceInmemory = "2.4.18.1"
    val akkaPersistenceJdbc = "3.4.0"
    val postgres = "42.2.2"
    val sshJ = "0.24.0"
    val jodaTime = "2.9.4"
    val playJsonJoda = "2.6.9"
    val exposeLoader = "0.7.1"
    val log4Javascript = "1.4.15"
    val typesafeConfig = "1.3.0"
    val reactHandsontable = "0.3.1"
    val sparkMlLib = "2.2.1"
    val pac4jSaml = "2.0.0-RC1"
    val openSaml = "2.6.1"
    val drtBirminghamSchema = "1.0.0"
    val playJson = "2.6.0"
    val playIteratees = "2.6.1"
  }

  import versions._

  /**
    * These dependencies are shared between JS and JVM projects
    * the special %%% function selects the correct version for each project
    */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "autowire" % autowire,
    "io.suzaku" %%% "boopickle" % booPickle
  ))

  val clientNpmDependences = Seq(
    "react" -> react,
    "react-dom" -> react,
    "react-addons-test-utils" -> reactTestUtils,
    "log4javascript" -> log4Javascript,
    "bootstrap" -> bootstrap,
    "react-handsontable" -> reactHandsontable
  )

  val clientNpmDevDependencies = "expose-loader" -> exposeLoader

  /** Dependencies only used by the JVM project */
  val jvmDependencies = Def.setting(List(
    "com.amazonaws" % "aws-java-sdk" % awsSdk,
    "com.github.dnvriend" %% "akka-persistence-inmemory" % akkaPersistenceInmemory % "test",
    "com.hierynomus" % "sshj" % sshJ,
    "com.lihaoyi" %% "pprint" % pprint,
    "com.lihaoyi" %%% "utest" % uTest % Test,
    "com.mfglabs" %% "commons-aws" % awsCommons,

    "javax.mail" % "mail" % "1.4.7",
    "info.folone" %% "poi-scala" % "0.18",
    "net.liftweb" %% "lift-json" % "3.1.0",

    "com.typesafe" % "config" % typesafeConfig,
    "com.typesafe.akka" %% "akka-testkit" % akka % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akka % "test",
    "com.typesafe.akka" %% "akka-persistence" % akka,
    "com.typesafe.akka" %% "akka-stream-contrib" % akkaStreamContrib,
    "com.typesafe.akka" %% "akka-slf4j" % akka,

    "com.typesafe.play" %% "play-json" % playJson,
    "com.typesafe.play" %% "play-iteratees" % playIteratees,
    "com.typesafe.play" %% "play-iteratees-reactive-streams" % playIteratees,

    "com.vmunier" %% "play-scalajs-scripts" % playScripts,
    "com.vmunier" %% "scalajs-scripts" % scalaJsScripts,

    "io.spray" %% "spray-caching" % sprayVersion,
    "io.spray" %% "spray-client" % sprayVersion,
    "io.spray" %% "spray-routing" % sprayVersion,
    "io.spray" %% "spray-json" % sprayVersion,

    "joda-time" % "joda-time" % jodaTime,
    "com.typesafe.play" %% "play-json-joda" % playJsonJoda,
    "org.opensaml" % "opensaml" % openSaml excludeAll (ExclusionRule("org.bouncycastle"), ExclusionRule("xerces")),
    "org.pac4j" % "pac4j-saml" % pac4jSaml,
    "org.apache.commons" % "commons-csv" % csvCommons,
    "org.apache.spark" % "spark-mllib_2.11" % sparkMlLib,
    "org.apache.spark" % "spark-sql_2.11" % "2.2.1",
    "uk.gov.homeoffice" %% "drt-birmingham-schema" % drtBirminghamSchema,
    "org.codehaus.janino" % "janino" % "3.0.7",
    "org.fusesource.leveldbjni" % "leveldbjni-all" % levelDbJni,
    "org.iq80.leveldb" % "leveldb" % levelDb,
    "com.github.dnvriend" %% "akka-persistence-jdbc" % akkaPersistenceJdbc,
    "org.postgresql" % "postgresql" % postgres,

    "org.renjin" % "renjin-script-engine" % renjin,
    "org.scalacheck" %% "scalacheck" % scalaCheck % "test",

    "org.specs2" %% "specs2-core" % specs2 % Test,
    "org.specs2" %% "specs2-junit" % specs2 % Test,
    "org.specs2" %% "specs2-mock" % specs2 % Test,
    "org.specs2" %% "specs2-scalacheck" % "3.8.4" % Test,

    "org.webjars" % "font-awesome" % "4.3.0-1" % Provided,
    "org.webjars" % "bootstrap" % bootstrap % Provided,

    "com.box" % "box-java-sdk" % "2.19.0",
    "com.eclipsesource.minimal-json" % "minimal-json" % "0.9.1",
    "org.bitbucket.b_c" % "jose4j" % "0.4.4"

  ))


  /** Dependencies only used by the JS project (note the use of %%% instead of %%) */
  val scalajsDependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core" % scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "test" % scalajsReact % Test,
    "com.github.japgolly.scalacss" %%% "ext-react" % scalaCSS,
    "com.olvind" %%% "scalajs-react-components" % scalajsReactComponents,
    "io.suzaku" %%% "diode" % diode,
    "io.suzaku" %%% "diode-react" % diode,
    "org.scala-js" %%% "scalajs-dom" % scalaDom,
    "com.lihaoyi" %%% "pprint" % pprint,
    "com.lihaoyi" %%% "utest" % uTest % Test,
    "ru.pavkin" %%% "scala-js-momentjs" % scalaJsMomentJs
  ))

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order
    * this is ignored now that we're using webpack via the sbt-bundle plugin */
  val jsDependencies = Def.setting(Seq())
}

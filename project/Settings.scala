import sbt._
import org.scalajs.sbtplugin.ScalaJSPlugin.autoImport._

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
    val scalaDom = "0.9.1"
    val scalajsReact = "1.0.0"
    val scalajsReactComponents = "0.5.0"
    val scalaCSS = "0.5.3"
    val log4js = "1.4.10"
    val autowire = "0.2.6"
    val booPickle = "1.2.4"
    val diode = "1.1.2"
    val uTest = "0.4.3"

    val akkaVersion = "2.4.16"

    val specs2Version = "3.7"
    val react = "15.1.0"
    val reactVersion = "15.5.4"

    val jQuery = "1.11.1"
    val bootstrap = "3.3.6"
    val chartjs = "2.1.3"

    val playScripts = "0.5.0"
    val sprayVersion: String = "1.3.3"
    val json4sVersion = "3.4.0"
  }

  import versions._

  /**
    * These dependencies are shared between JS and JVM projects
    * the special %%% function selects the correct version for each project
    */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "autowire" % versions.autowire,
    "me.chrons" %%% "boopickle" % versions.booPickle
  ))

  val clientNpmDependences = Seq(
    "react" -> "15.5.1",
    "react-dom" -> "15.5.1",
    "react-addons-test-utils" -> "15.5.1",
    "log4javascript" -> "1.4.15",
    "jquery" -> jQuery,
    "bootstrap" -> bootstrap
  )

  val clientNpmDevDependencies = "expose-loader" -> "0.7.1"


  /** Dependencies only used by the JVM project */
  val jvmDependencies = Def.setting(List(
    "io.spray" % "spray-caching_2.11" % "1.3.4",
    "org.specs2" %% "specs2-core" % specs2Version % Test,
    "org.specs2" %% "specs2-junit" % specs2Version % Test,
    "org.specs2" %% "specs2-mock" % specs2Version % Test,
    "org.specs2" %% "specs2-scalacheck" % "3.8.4" % Test,
    "com.vmunier" %% "play-scalajs-scripts" % versions.playScripts,
    "org.webjars" % "font-awesome" % "4.3.0-1" % Provided,
    "org.webjars" % "bootstrap" % versions.bootstrap % Provided,
    "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % "test",
    "com.typesafe.akka" %% "akka-persistence" % akkaVersion,
    "com.typesafe.akka" %% "akka-stream-contrib" % "0.2",
    "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
    "org.iq80.leveldb" % "leveldb" % "0.7",
    "org.fusesource.leveldbjni" % "leveldbjni-all" % "1.8",
    "com.lihaoyi" %%% "utest" % versions.uTest % Test,
    "org.renjin" % "renjin-script-engine" % "0.8.2195",
    "com.amazonaws" % "aws-java-sdk" % "1.11.89",
    "com.mfglabs" %% "commons-aws" % "0.10.0",
    "org.apache.commons" % "commons-csv" % "1.4",
    "joda-time" % "joda-time" % "2.9.4") :::
    List("io.spray" %% "spray-client" % versions.sprayVersion,
      "io.spray" %% "spray-routing" % versions.sprayVersion,
      "io.spray" %% "spray-json" % "1.3.2",
      "com.typesafe" % "config" % "1.3.0"
    ))


  /** Dependencies only used by the JS project (note the use of %%% instead of %%) */
  val scalajsDependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core" % versions.scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "extra" % versions.scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "test" % versions.scalajsReact % Test,
    "com.github.japgolly.scalacss" %%% "ext-react" % versions.scalaCSS,
//    "com.github.chandu0101.scalajs-react-components" %%% "core" % versions.scalajsReactComponents,
    "io.suzaku" %%% "diode" % versions.diode,
//    "com.payalabs" %%% "scalajs-react-bridge" % "0.2.0-SNAPSHOT",
    "io.suzaku" %%% "diode-react" % versions.diode,
    "org.scala-js" %%% "scalajs-dom" % versions.scalaDom,
    "com.lihaoyi" %%% "utest" % versions.uTest % Test
  ))

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order */
  val jsDependencies = Def.setting(Seq(
    "org.webjars.bower" % "react" % reactVersion % "test"
      /        "react-with-addons.js"
      minified "react-with-addons.min.js"
      commonJSName "React",

    "org.webjars.bower" % "react" % reactVersion % "test"
      /         "react-dom.js"
      minified  "react-dom.min.js"
      dependsOn "react-with-addons.js"
      commonJSName "ReactDOM",

    "org.webjars.bower" % "react" % reactVersion % "test"
      /         "react-dom-server.js"
      minified  "react-dom-server.min.js"
      dependsOn "react-dom.js"
      commonJSName "ReactDOMServer",
    //    "org.webjars.bower" % "react" % versions.react / "react-with-addons.js" minified "react-with-addons.min.js" commonJSName "React",
    //    "org.webjars.bower" % "react" % versions.react / "react-dom.js" minified "react-dom.min.js" dependsOn "react-with-addons.js" commonJSName "ReactDOM",
    //    "org.webjars.npm" % "fixed-data-table" % "0.6.3" / "dist/fixed-data-table.js" minified "dist/fixed-data-table.min.js" dependsOn "react-with-addons.js" commonJSName "ReactFixedDataTable",
//    "org.webjars" % "rgraph" % "3_2014-07-27-stable" / "RGraph.bar.js",
    "org.webjars" % "jquery" % versions.jQuery / "jquery.js" minified "jquery.min.js",
    "org.webjars" % "bootstrap" % versions.bootstrap / "bootstrap.js" minified "bootstrap.min.js" dependsOn "jquery.js",
    "org.webjars" % "chartjs" % versions.chartjs / "Chart.js" minified "Chart.min.js",
    "org.webjars" % "log4javascript" % versions.log4js / "js/log4javascript_uncompressed.js" minified "js/log4javascript.js"
  ))
}

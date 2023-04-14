import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport._
import sbt._

/**
 * Application settings. Configure the build for your application here.
 * You normally don't have to touch the actual build definition after this.
 */
object Settings {
  /** The name of your application */
  val name = "DRTv2"

  /** The version of your application */
  val version: String = sys.env.getOrElse("DRONE_BUILD_NUMBER", sys.env.getOrElse("BUILD_ID", "dev"))
  /** Options for the scala compiler */
  val scalacOptions = Seq(
    "-Xlint",
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Ywarn-dead-code",
    "-Ywarn-unused",
    "-Ywarn-value-discard",
  )

  /** Declare global dependency versions here to avoid mismatches in multi part dependencies */
  //noinspection ScalaStyle
  object versions {
    val drtLib = "v475"

    val scala = "2.13.10"
    val scalaDom = "2.4.0"
    val scalajsReact = "2.1.1"
    val scalaCSS = "1.0.0"
    val scalaJsMomentJs = "0.10.9"
    val autowire = "0.3.3"
    val booPickle = "1.3.3"
    val diode = "1.2.0-RC4"
    val uTest = "0.7.4"
    val h2 = "2.1.210"

    val akka = "2.7.0"
    val akkaStreamContrib = "0.9"

    val specs2 = "4.20.0"
    val react = "17.0.2"

    val bootstrap = "3.3.7-1"

    val playScripts = "0.5.0"
    val sprayVersion = "1.3.4"
    val levelDb = "0.7"
    val levelDbJni = "1.8"
    val renjin = "0.9.2725"
    val awsSdk = "1.11.1034"
    val csvCommons = "1.4"
    val poi = "5.2.3"
    val pprint = "0.5.6"
    val akkaPersistenceJdbc = "5.2.0"
    val bluebus = "0.5.0-DRT"
    val postgres = "42.6.0"
    val sshJ = "0.24.0"
    val jodaTime = "2.9.9"
    val playJsonJoda = "2.6.9"
    val exposeLoader = "0.7.1"
    val log4Javascript = "1.4.15"
    val typesafeConfig = "1.4.2"
    val reactHandsontable = "3.1.2"
    val pac4jSaml = "2.0.0-RC1"
    val drtBirminghamSchema = "50"
    val drtCirium = "186"
    val playJson = "2.6.0"
    val playIteratees = "2.6.1"
    val uPickle = "2.0.0"
    val akkaHttp = "10.2.6"
    val slick = "3.4.1"
    val censorinus = "2.1.16"
    val janinoVersion = "3.1.9"
    val scalaJsReactMaterialUi = "0.3.1"
    val sprayJsonScalaJs = "1.3.5-7"
    val scalaTestVersion = "3.2.15"
  }

  import versions._

  val clientNpmDependencies = Seq(
    "react" -> react,
    "react-dom" -> react,
    "log4javascript" -> log4Javascript,
    "bootstrap" -> bootstrap,
    "@handsontable/react" -> reactHandsontable,
    "handsontable" -> "6.2.2",
    "core-js" -> "3.6.5",
    "chart.js" -> "^3.6.0",
    "@tippyjs/react" -> "4.1.0",
    "react-chartjs-2" -> "^4.0.0",
    "moment" -> ">=2.29.4",
    "@material-ui/core" -> "3.9.4",
    "@material-ui/icons" -> "3.0.2",
    "@material-ui/lab" -> "3.0.0-alpha.30"
  )

  /** Dependencies only used by the JS project (note the use of %%% instead of %%) */
  val scalajsDependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core" % scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReact,
    "com.github.japgolly.scalajs-react" %%% "test" % scalajsReact % Test,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLib,
    "com.github.japgolly.scalacss" %%% "ext-react" % scalaCSS,

    "io.suzaku" %%% "diode" % diode,
    "io.suzaku" %%% "diode-react" % diode,
    "org.scala-js" %%% "scalajs-dom" % scalaDom,
    "org.scala-js" %%% "scalajs-java-securerandom" % "1.0.0",

    "com.lihaoyi" %%% "utest" % uTest % Test,
    "com.lihaoyi" %%% "upickle" % uPickle,
    "com.lihaoyi" %% "pprint" % pprint,
    "ru.pavkin" %%% "scala-js-momentjs" % scalaJsMomentJs,

    "io.kinoplan" %%% "scalajs-react-material-ui-core" % scalaJsReactMaterialUi,
    "io.kinoplan" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUi,
    "io.kinoplan" %%% "scalajs-react-material-ui-lab" % scalaJsReactMaterialUi,

    "io.crashbox" %% "spray-json" % sprayJsonScalaJs,
    "com.dedipresta" %%% "scala-crypto" % "1.0.0",

    "org.scalatest" %%% "scalatest" % scalaTestVersion % "test",
  ))

  val clientNpmDevDependencies: (String, String) = "expose-loader" -> exposeLoader

  /**
   * These dependencies are shared between JS and JVM projects
   * the special %%% function selects the correct version for each project
   */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "autowire" % autowire,
    "com.lihaoyi" %%% "upickle" % uPickle,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLib,
    "io.suzaku" %%% "boopickle" % booPickle
  ))

  /** Dependencies only used by the JVM project */
  val jvmDependencies = Def.setting(List(
    "com.amazonaws" % "aws-java-sdk" % awsSdk,
    "com.github.gphat" %% "censorinus" % censorinus,
    "com.pauldijou" %% "jwt-core" % "4.3.0",
    "com.hierynomus" % "sshj" % sshJ,
    "com.lihaoyi" %% "pprint" % pprint,
    "com.lihaoyi" %%% "utest" % uTest % Test,

    "javax.mail" % "mail" % "1.4.7",
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % "4.0.0",
    "com.sun.xml.ws" % "rt" % "4.0.1",
    "javax.xml.bind" % "jaxb-api" % "2.2.12",

    "com.h2database" % "h2" % h2 % Test,
    "com.typesafe" % "config" % typesafeConfig,
    "com.lightbend.akka" %% "akka-persistence-jdbc" % akkaPersistenceJdbc,
    "com.typesafe.akka" %% "akka-persistence-typed" % akka,
    "com.typesafe.akka" %% "akka-remote" % akka,
    "com.typesafe.akka" %% "akka-persistence-testkit" % akka,
    "com.typesafe.akka" %% "akka-actor-testkit-typed" % akka,
    "com.typesafe.akka" %% "akka-testkit" % akka,
    "com.typesafe.akka" %% "akka-serialization-jackson" % akka,
    "com.typesafe.akka" %% "akka-pki" % akka,
    "com.typesafe.akka" %% "akka-stream-typed" % akka,
    "com.typesafe.akka" %% "akka-persistence-testkit" % akka,
    "com.typesafe.akka" %% "akka-testkit" % akka % "test",
    "com.typesafe.akka" %% "akka-stream-testkit" % akka % "test",
    "com.typesafe.akka" %% "akka-persistence" % akka,
    "com.typesafe.akka" %% "akka-persistence-query" % akka,
    "com.typesafe.akka" %% "akka-slf4j" % akka,
    "com.typesafe.akka" %% "akka-http" % akkaHttp,
    "com.typesafe.akka" %% "akka-http-caching" % akkaHttp,
    "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttp,
    "com.typesafe.akka" %% "akka-http-xml" % akkaHttp,
    "com.typesafe.akka" %% "akka-stream" % akka,

    "com.typesafe.slick" %% "slick" % slick,
    "com.typesafe.slick" %% "slick-hikaricp" % slick,
    "com.typesafe.slick" %% "slick-codegen" % slick,

    "joda-time" % "joda-time" % jodaTime,

    "ch.qos.logback.contrib" % "logback-json-classic" % "0.1.5",
    "ch.qos.logback.contrib" % "logback-jackson" % "0.1.5",
    "org.codehaus.janino" % "janino" % janinoVersion,
    "com.fasterxml.jackson.core" % "jackson-databind" % "2.10.0",
    "com.fasterxml.jackson.core" % "jackson-core" % "2.10.0",
//    "org.pac4j" % "pac4j-saml" % pac4jSaml,
    "org.apache.commons" % "commons-csv" % csvCommons,
    "org.apache.poi" % "poi" % poi,
    "org.apache.poi" % "poi-ooxml" % poi,
    "org.codehaus.janino" % "janino" % "3.0.7",
    "org.fusesource.leveldbjni" % "leveldbjni-all" % levelDbJni,
    "org.iq80.leveldb" % "leveldb" % levelDb,
    "org.postgresql" % "postgresql" % postgres,

    "org.renjin" % "renjin-script-engine" % renjin,

    "org.specs2" %% "specs2-core" % specs2 % Test,
    "org.specs2" %% "specs2-junit" % specs2 % Test,
    "org.specs2" %% "specs2-mock" % specs2 % Test,

    "org.webjars" % "font-awesome" % "4.7.0" % Provided,
    "org.webjars" % "bootstrap" % bootstrap % Provided,

    "com.box" % "box-java-sdk" % "2.19.0",
    "com.eclipsesource.minimal-json" % "minimal-json" % "0.9.1",
//    "org.bitbucket.b_c" % "jose4j" % "0.4.4",

    "io.netty" % "netty-all" % "4.1.91.Final",

    "uk.gov.homeoffice" %% "drt-birmingham-schema" % drtBirminghamSchema,
    "uk.gov.homeoffice" %% "drt-cirium" % drtCirium,
    "uk.gov.homeoffice" %% "drt-lib" % drtLib,
    "uk.gov.homeoffice" %% "bluebus" % bluebus,

    "uk.gov.service.notify" % "notifications-java-client" % "3.17.3-RELEASE"
  ))

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order
   * this is ignored now that we're using webpack via the sbt-bundle plugin */
  val jsDependencies = Def.setting(Seq())
}

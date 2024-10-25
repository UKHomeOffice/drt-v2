import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.*

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
  val scalacOptions: Seq[String] = Seq(
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
    val drtLib = "v913"

    val scala = "2.13.12"
    val scalaDom = "2.8.0"
    val scalajsReact = "2.1.1"
    val scalaCSS = "1.0.0"
    val scalaJsMomentJs = "0.10.9"
    val booPickle = "1.3.3"
    val diode = "1.2.0-RC4"
    val uTest = "0.7.4"
    val h2 = "2.2.224"

    val akka = "2.8.5"

    val specs2 = "4.20.3"
    val react = "18.2.0"

    val bootstrap = "3.3.6"

    val renjin = "0.9.2725"
    val csvCommons = "1.10.0"
    val poi = "5.2.4"
    val pprint = "0.5.9"
    val akkaPersistenceJdbc = "5.2.0"
    val bluebus = "v95"
    val postgres = "42.7.0"
    val sshJ = "0.33.0"
    val jodaTime = "2.12.5"
    val exposeLoader = "0.7.1"
    val log4Javascript = "1.4.15"
    val typesafeConfig = "1.4.3"
    val reactHandsontable = "3.1.2"
    val pac4jSaml = "2.0.0-RC1"
    val drtBirminghamSchema = "50"
    val drtCirium = "186"
    val uPickle = "3.1.3"
    val akkaHttp = "10.5.2"
    val slick = "3.4.1"
    val censorinus = "2.1.16"
    val janinoVersion = "3.1.9"
    val scalaJsReactMaterialUi = "0.1.18"
    val scalaTestVersion = "3.2.17"
    val twirlApi = "1.6.3"
    val mockitoVersion = "4.11.0"
    val rtVersion = "4.0.2"
    val jakartaXmlWsApi = "4.0.1"
    val scalatestplusPlay = "7.0.0"
    val nettyAll = "4.1.101.Final"
    val jwtCore = "9.4.5"
  }

  import versions.*

  val clientNpmDependencies: Seq[(String, String)] = Seq(
    "react" -> react,
    "react-dom" -> react,
    "log4javascript" -> log4Javascript,
    "bootstrap" -> bootstrap,
    "@handsontable/react" -> reactHandsontable,
    "handsontable" -> "7.2.2",
    "core-js" -> "3.23.3",
    "chart.js" -> "^3.6.0",
    "@tippyjs/react" -> "4.1.0",
    "react-chartjs-2" -> "^4.0.0",
    "moment" -> ">=2.29.4",
    "@mui/system" -> "5.16.5",
    "@mui/material" -> "5.16.5",
    "@mui/icons-material" -> "5.16.5",
    "@mui/lab" -> "5.0.0-alpha.173",
    "flickity" -> "2.3.0",
    "react-flickity-component" -> "4.0.6",
    "react-markdown" -> "9.0.1",
    "@types/react-dom" -> react,
    "css-loader" -> "6.7.2",
    "@drt/drt-react"-> "https://github.com/UKHomeOffice/drt-react.git#a31cd014e8913b756f5d901186d4e271e9994b82",
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

    "com.freshcodelimited" %%% "scalajs-react-material-ui-core" % scalaJsReactMaterialUi,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUi,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-lab" % scalaJsReactMaterialUi,
    "com.dedipresta" %%% "scala-crypto" % "1.0.0",
    "io.lemonlabs" %%% "scala-uri" % "4.0.3",

    "org.scalatest" %%% "scalatest" % scalaTestVersion % "test",
  ))

  val clientNpmDevDependencies: (String, String) = "expose-loader" -> exposeLoader

  /**
   * These dependencies are shared between JS and JVM projects
   * the special %%% function selects the correct version for each project
   */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "upickle" % uPickle,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLib exclude("org.apache.spark", "spark-mllib_2.13"),
    "io.suzaku" %%% "boopickle" % booPickle,
  ))

  /** Dependencies only used by the JVM project */
  val jvmDependencies = Def.setting(List(
    "com.github.gphat" %% "censorinus" % censorinus,
    "com.github.jwt-scala" %% "jwt-core" % jwtCore,
    "com.hierynomus" % "sshj" % sshJ,
    "com.lihaoyi" %%% "utest" % uTest % Test,

    "javax.mail" % "mail" % "1.4.7",
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % jakartaXmlWsApi,
    "com.sun.xml.ws" % "rt" % rtVersion,
    "javax.xml.bind" % "jaxb-api" % "2.3.1",

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

    "com.typesafe.play" %% "twirl-api" % twirlApi,

    "com.typesafe.slick" %% "slick" % slick,
    "com.typesafe.slick" %% "slick-hikaricp" % slick,
    "com.typesafe.slick" %% "slick-codegen" % slick,

    "joda-time" % "joda-time" % jodaTime,

    "ch.qos.logback" % "logback-classic" % "1.3.5",
    "ch.qos.logback.contrib" % "logback-json-classic" % "0.1.5",
    "ch.qos.logback.contrib" % "logback-jackson" % "0.1.5",
    "org.codehaus.janino" % "janino" % janinoVersion,
    "org.pac4j" % "pac4j-saml" % pac4jSaml,
    "org.apache.commons" % "commons-csv" % csvCommons,
    "org.apache.poi" % "poi" % poi,
    "org.apache.poi" % "poi-ooxml" % poi,
    "org.codehaus.janino" % "janino" % "3.0.16",
    "org.postgresql" % "postgresql" % postgres,

    "org.renjin" % "renjin-script-engine" % renjin,

    "org.specs2" %% "specs2-core" % specs2 % Test,
    "org.specs2" %% "specs2-junit" % specs2 % Test,
    "org.specs2" %% "specs2-mock" % specs2 % Test,
    "org.mockito" % "mockito-core" % mockitoVersion % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusPlay % Test,
    "org.scalatestplus" %% "mockito-3-4" % "3.2.10.0" % Test,
    "org.webjars" % "font-awesome" % "4.7.0" % Provided,
    "org.webjars" % "bootstrap" % bootstrap % Provided,

    "io.netty" % "netty-all" % nettyAll,

    "uk.gov.homeoffice" %% "drt-birmingham-schema" % drtBirminghamSchema,
    "uk.gov.homeoffice" %% "drt-cirium" % drtCirium,
    "uk.gov.homeoffice" %% "drt-lib" % drtLib,
    "uk.gov.homeoffice" %% "bluebus" % bluebus,

    "uk.gov.service.notify" % "notifications-java-client" % "4.1.0-RELEASE",
    "software.amazon.awssdk" % "s3" % "2.16.96",
  ))

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order
   * this is ignored now that we're using webpack via the sbt-bundle plugin */
  val jsDependencies = Def.setting(Seq())
}

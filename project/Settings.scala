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
    val scala = "2.13.18"

    val drtLib = "v1394"
    val drtCirium = "v339"
    val bluebus = "v149"

    val pekko = "1.4.0"
    val pekkoHttp = "1.3.0"
    val pekkoPersistenceJdbc = "1.2.0"
    val slick = "3.5.2" // restricted by pekko-persistence-jdbc 1.2.0

    val aws = "2.41.9"

    val scalaDom = "2.8.0"
    val scalajsReact = "2.1.2"
    val scalaCSS = "1.0.0"
    val scalaJsMomentJs = "0.10.9"
    val booPickle = "1.3.3"
    val diode = "1.2.0-RC4"
    val uTest = "0.7.4"
    val h2 = "2.4.240"

    val specs2 = "4.23.0"
    val react = "18.2.0"

    val bootstrap = "3.3.6"

    val poi = "5.5.1"
    val renjin = "0.9.2725"
    val csvCommons = "1.14.1"
    val postgres = "42.7.9"
    val sshJ = "0.40.0"
    val jodaTime = "2.14.0"
    val exposeLoader = "0.7.1"
    val log4Javascript = "1.4.15"
    val typesafeConfig = "1.4.5"
    val reactHandsontable = "3.1.2"
    val pac4jSaml = "2.0.0-RC1"
    val drtBirminghamSchema = "50"
    val uPickle = "3.3.1"
    val censorinus = "2.1.16"
    val janinoVersion = "3.1.12"
    val scalaJsReactMaterialUi = "0.1.18"
    val scalaTestVersion = "3.2.19"
    val twirlApi = "2.0.9"
    val mockito = "5.21.0"
    val rtVersion = "4.0.3"
    val jakartaXmlWsApi = "4.0.2"
    val scalatestplusPlay = "7.0.2"
    val nettyAll = "4.2.9.Final"
    val jwtCore = "11.0.3"
    val logback = "1.5.24"
    val logbackContrib = "0.1.5"
    val scalajsScripts = "1.3.0"
    val notificationsJavaClient = "6.0.0-RELEASE"
    val fontAwesome = "7.2.0"
    val webjarsPlay = "3.0.10"
    val webjarsLocator = "0.52"
    val janino = "3.0.16"
    val scalajsJavaSecurerandom = "1.0.0"
    val scalaJavaTime = "2.5.0"
    val scalaCrypto = "1.0.0"
    val scalaUri = "4.0.3"
    val mail = "1.4.7"
    val jaxbApi = "2.3.1"
    val mockito34 = "3.2.10.0"
  }

  import versions.*

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
    "org.scala-js" %%% "scalajs-java-securerandom" % scalajsJavaSecurerandom,
    "com.lihaoyi" %%% "utest" % uTest % Test,
    "com.lihaoyi" %%% "upickle" % uPickle,
    "ru.pavkin" %%% "scala-js-momentjs" % scalaJsMomentJs,
    "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTime,

    "com.freshcodelimited" %%% "scalajs-react-material-ui-core" % scalaJsReactMaterialUi,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUi,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-lab" % scalaJsReactMaterialUi,
    "com.dedipresta" %%% "scala-crypto" % scalaCrypto,
    "io.lemonlabs" %%% "scala-uri" % scalaUri,

    "org.scalatest" %%% "scalatest" % scalaTestVersion % "test",
  ))

  /**
   * These dependencies are shared between JS and JVM projects
   * the special %%% function selects the correct version for each project
   */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "upickle" % uPickle,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLib exclude("org.apache.spark", "spark-mllib_2.13"),
    "io.suzaku" %%% "boopickle" % booPickle,
    "org.webjars" % "webjars-locator" % webjarsLocator,
  ))

  /** Dependencies only used by the JVM project */
  val jvmDependencies = Def.setting(List(
    "com.github.gphat" %% "censorinus" % censorinus,
    "com.github.jwt-scala" %% "jwt-core" % jwtCore,
    "com.hierynomus" % "sshj" % sshJ,
    "com.lihaoyi" %%% "utest" % uTest % Test,
    "com.vmunier" %% "scalajs-scripts" % scalajsScripts,

    "javax.mail" % "mail" % mail,
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % jakartaXmlWsApi,
    "com.sun.xml.ws" % "rt" % rtVersion,
    "javax.xml.bind" % "jaxb-api" % jaxbApi,

    "com.h2database" % "h2" % h2,
    "com.typesafe" % "config" % typesafeConfig,
    "org.apache.pekko" %% "pekko-persistence-jdbc" % pekkoPersistenceJdbc,
    "org.apache.pekko" %% "pekko-persistence-typed" % pekko,
    "org.apache.pekko" %% "pekko-remote" % pekko,
    "org.apache.pekko" %% "pekko-persistence-testkit" % pekko,
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekko,
    "org.apache.pekko" %% "pekko-testkit" % pekko,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekko,
    "org.apache.pekko" %% "pekko-pki" % pekko,
    "org.apache.pekko" %% "pekko-stream-typed" % pekko,
    "org.apache.pekko" %% "pekko-testkit" % pekko % "test",
    "org.apache.pekko" %% "pekko-stream-testkit" % pekko % "test",
    "org.apache.pekko" %% "pekko-persistence" % pekko,
    "org.apache.pekko" %% "pekko-persistence-query" % pekko,
    "org.apache.pekko" %% "pekko-slf4j" % pekko,
    "org.apache.pekko" %% "pekko-http" % pekkoHttp,
    "org.apache.pekko" %% "pekko-http-caching" % pekkoHttp,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttp,
    "org.apache.pekko" %% "pekko-http-xml" % pekkoHttp,
    "org.apache.pekko" %% "pekko-stream" % pekko,

    "org.playframework.twirl" %% "twirl-api" % twirlApi,

    "com.typesafe.slick" %% "slick" % slick,
    "com.typesafe.slick" %% "slick-hikaricp" % slick,
    "com.typesafe.slick" %% "slick-codegen" % slick,

    "joda-time" % "joda-time" % jodaTime,

    "ch.qos.logback" % "logback-classic" % logback,
    "ch.qos.logback.contrib" % "logback-json-classic" % logbackContrib,
    "ch.qos.logback.contrib" % "logback-jackson" % logbackContrib,
    "org.codehaus.janino" % "janino" % janinoVersion,
    "org.pac4j" % "pac4j-saml" % pac4jSaml,
    "org.apache.commons" % "commons-csv" % csvCommons,
    "org.apache.poi" % "poi" % poi,
    "org.apache.poi" % "poi-ooxml" % poi,
    "org.codehaus.janino" % "janino" % janino,
    "org.postgresql" % "postgresql" % postgres,

    "org.renjin" % "renjin-script-engine" % renjin,

    "org.specs2" %% "specs2-core" % specs2 % Test,
    "org.specs2" %% "specs2-junit" % specs2 % Test,
    "org.specs2" %% "specs2-mock" % specs2 % Test,
    "org.mockito" % "mockito-core" % mockito % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusPlay % Test,
    "org.scalatestplus" %% "mockito-3-4" % mockito34 % Test,

    "org.webjars" % "font-awesome" % fontAwesome % Provided,
    "org.webjars" % "bootstrap" % bootstrap % Provided,
    "org.webjars" %% "webjars-play" % webjarsPlay,
    "org.webjars" % "webjars-locator" % webjarsLocator,

    "io.netty" % "netty-all" % nettyAll,

    "uk.gov.homeoffice" %% "drt-birmingham-schema" % drtBirminghamSchema,
    "uk.gov.homeoffice" %% "drt-cirium" % drtCirium,
    "uk.gov.homeoffice" %% "drt-lib" % drtLib,
    "uk.gov.homeoffice" %% "bluebus" % bluebus,

    "uk.gov.service.notify" % "notifications-java-client" % notificationsJavaClient,
    "software.amazon.awssdk" % "s3" % aws,
  ))

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order
   * this is ignored now that we're using webpack via the sbt-bundle plugin */
  val jsDependencies = Def.setting(Seq())
}

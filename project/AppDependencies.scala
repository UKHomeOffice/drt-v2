import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.*

object AppDependencies {
  private val drtLibVersion = "v1410"
  private val drtCiriumVersion = "v339"
  private val bluebusVersion = "v149"

  private val pekkoVersion = "1.4.0"
  private val pekkoHttpVersion = "1.3.0"
  private val pekkoPersistenceJdbcVersion = "1.2.0"
  private val slickVersion = "3.5.2" // restricted by pekko-persistence-jdbc 1.2.0

  private val awsVersion = "2.42.36"

  private val scalaDomVersion = "2.8.0"
  val scalajsReactVersion = "2.1.2"
  private val scalaCssVersion = "1.0.0"
  private val scalaJsMomentJsVersion = "0.10.9"
  private val booPickleVersion = "1.3.3"
  private val diodeVersion = "1.2.0-RC4"
  private val utestVersion = "0.7.4"
  private val h2Version = "2.4.240"

  private val specs2Version = "4.23.0"
  private val bootstrapVersion = "3.3.6"

  private val poiVersion = "5.5.1"
  private val renjinVersion = "0.9.2725"
  private val csvCommonsVersion = "1.14.1"
  private val postgresVersion = "42.7.10"
  private val sshJVersion = "0.40.0"
  private val jodaTimeVersion = "2.14.1"
  private val typesafeConfigVersion = "1.4.6"
  private val pac4jSamlVersion = "2.0.0-RC1"
  private val drtBirminghamSchemaVersion = "50"
  private val uPickleVersion = "3.3.1"
  private val censorinusVersion = "2.1.16"
  private val janinoVersion = "3.1.12"
  private val scalaJsReactMaterialUiVersion = "0.1.18"
  private val scalaTestVersion = "3.2.19"
  private val twirlApiVersion = "2.0.9"
  private val mockitoVersion = "5.23.0"
  private val rtVersion = "4.0.4"
  private val jakartaXmlWsApiVersion = "4.0.3"
  private val scalatestplusPlayVersion = "7.0.2"
  private val nettyAllVersion = "4.2.12.Final"
  private val jwtCoreVersion = "11.0.4"
  private val logbackVersion = "1.5.32"
  private val logbackContribVersion = "0.1.5"
  private val scalajsScriptsVersion = "1.3.0"
  private val notificationsJavaClientVersion = "6.0.0-RELEASE"
  private val fontAwesomeVersion = "4.7.0"
  private val webjarsPlayVersion = "3.0.10"
  private val webjarsLocatorVersion = "0.52"
  private val janinoAltVersion = "3.0.16"
  private val scalajsJavaSecurerandomVersion = "1.0.0"
  private val scalaJavaTimeVersion = "2.5.0"
  private val scalaCryptoVersion = "1.0.0"
  private val scalaUriVersion = "4.0.3"
  private val mailVersion = "1.4.7"
  private val jaxbApiVersion = "2.3.1"
  private val mockito34Version = "3.2.10.0"

  /** Dependencies only used by the JS project (note the use of %%% instead of %%) */
  val scalajsDependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core" % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "test" % scalajsReactVersion % Test,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLibVersion,
    "com.github.japgolly.scalacss" %%% "ext-react" % scalaCssVersion,
    "io.suzaku" %%% "diode" % diodeVersion,
    "io.suzaku" %%% "diode-react" % diodeVersion,
    "org.scala-js" %%% "scalajs-dom" % scalaDomVersion,
    "org.scala-js" %%% "scalajs-java-securerandom" % scalajsJavaSecurerandomVersion,
    "com.lihaoyi" %%% "utest" % utestVersion % Test,
    "com.lihaoyi" %%% "upickle" % uPickleVersion,
    "ru.pavkin" %%% "scala-js-momentjs" % scalaJsMomentJsVersion,
    "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTimeVersion,

    "com.freshcodelimited" %%% "scalajs-react-material-ui-core" % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-lab" % scalaJsReactMaterialUiVersion,
    "com.dedipresta" %%% "scala-crypto" % scalaCryptoVersion,
    "io.lemonlabs" %%% "scala-uri" % scalaUriVersion,

    "org.scalatest" %%% "scalatest" % scalaTestVersion % "test",
  ))

  /**
   * These dependencies are shared between JS and JVM projects
   * the special %%% function selects the correct version for each project
   */
  val sharedDependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "upickle" % uPickleVersion,
    "uk.gov.homeoffice" %%% "drt-lib" % drtLibVersion exclude ("org.apache.spark", "spark-mllib_2.13"),
    "io.suzaku" %%% "boopickle" % booPickleVersion,
    "org.webjars" % "webjars-locator" % webjarsLocatorVersion,
  ))

  private val jvmCompileDependencies: Seq[ModuleID] = Seq(
    "com.github.gphat" %% "censorinus" % censorinusVersion,
    "com.github.jwt-scala" %% "jwt-core" % jwtCoreVersion,
    "com.hierynomus" % "sshj" % sshJVersion,
    "com.vmunier" %% "scalajs-scripts" % scalajsScriptsVersion,

    "javax.mail" % "mail" % mailVersion,
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % jakartaXmlWsApiVersion,
    "com.sun.xml.ws" % "rt" % rtVersion,
    "javax.xml.bind" % "jaxb-api" % jaxbApiVersion,

    "com.typesafe" % "config" % typesafeConfigVersion,
    "org.apache.pekko" %% "pekko-persistence-jdbc" % pekkoPersistenceJdbcVersion,
    "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-remote" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-testkit" % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-pki" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream-typed" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence" % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-caching" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-xml" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,

    "org.playframework.twirl" %% "twirl-api" % twirlApiVersion,

    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.typesafe.slick" %% "slick-codegen" % slickVersion,

    "joda-time" % "joda-time" % jodaTimeVersion,

    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "ch.qos.logback.contrib" % "logback-json-classic" % logbackContribVersion,
    "ch.qos.logback.contrib" % "logback-jackson" % logbackContribVersion,
    "org.codehaus.janino" % "janino" % janinoVersion,
    "org.pac4j" % "pac4j-saml" % pac4jSamlVersion,
    "org.apache.commons" % "commons-csv" % csvCommonsVersion,
    "org.apache.poi" % "poi" % poiVersion,
    "org.apache.poi" % "poi-ooxml" % poiVersion,
    "org.codehaus.janino" % "janino" % janinoAltVersion,
    "org.postgresql" % "postgresql" % postgresVersion,

    "org.renjin" % "renjin-script-engine" % renjinVersion,

    "org.webjars" % "font-awesome" % fontAwesomeVersion % Provided,
    "org.webjars" % "bootstrap" % bootstrapVersion % Provided,
    "org.webjars" %% "webjars-play" % webjarsPlayVersion,
    "org.webjars" % "webjars-locator" % webjarsLocatorVersion,

    "io.netty" % "netty-all" % nettyAllVersion,

    "uk.gov.homeoffice" %% "drt-birmingham-schema" % drtBirminghamSchemaVersion,
    "uk.gov.homeoffice" %% "drt-cirium" % drtCiriumVersion,
    "uk.gov.homeoffice" %% "drt-lib" % drtLibVersion,
    "uk.gov.homeoffice" %% "bluebus" % bluebusVersion,

    "uk.gov.service.notify" % "notifications-java-client" % notificationsJavaClientVersion,
    "software.amazon.awssdk" % "s3" % awsVersion,
  )

  private val jvmTestDependencies: Seq[ModuleID] = Seq(
    "com.h2database" % "h2" % h2Version,
    "com.lihaoyi" %% "utest" % utestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,

    "org.specs2" %% "specs2-core" % specs2Version % Test,
    "org.specs2" %% "specs2-junit" % specs2Version % Test,
    "org.specs2" %% "specs2-mock" % specs2Version % Test,
    "org.mockito" % "mockito-core" % mockitoVersion % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusPlayVersion % Test,
    "org.scalatestplus" %% "mockito-3-4" % mockito34Version % Test,
  )

  /** Dependencies only used by the JVM project */
  val jvmDependencies: Seq[ModuleID] = jvmCompileDependencies ++ jvmTestDependencies

  /** Dependencies for external JS libs that are bundled into a single .js file according to dependency order
   * this is ignored now that we're using webpack via the sbt-bundle plugin */
  val jsDependencies: Seq[ModuleID] = Seq.empty
}


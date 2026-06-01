import sbt.*

object BackendDependencies {
  import SharedDependencyVersions.drt.{birminghamSchema, bluebus, cirium, lib}
  import SharedDependencyVersions.shared.{utest, webjarsLocator}

  private val pekkoVersion = "1.4.0"
  private val pekkoHttpVersion = "1.3.0"
  private val pekkoPersistenceJdbcVersion = "1.2.0"
  private val slickVersion = "3.5.2" // restricted by pekko-persistence-jdbc 1.2.0

  private val awsVersion = "2.42.36"

  private val h2Version = "2.4.240"
  private val bootstrapVersion = "3.3.6"
  private val specs2Version = "4.23.0"
  private val mockitoVersion = "5.23.0"
  private val logbackVersion = "1.5.32"
  private val logbackContribVersion = "0.1.5"

  private val poiVersion = "5.5.1"
  private val renjinVersion = "0.9.2725"
  private val csvCommonsVersion = "1.14.1"
  private val postgresVersion = "42.7.10"
  private val sshJVersion = "0.40.0"
  private val jodaTimeVersion = "2.14.1"
  private val typesafeConfigVersion = "1.4.6"
  private val pac4jSamlVersion = "2.0.0-RC1"
  private val censorinusVersion = "2.1.16"
  private val janinoVersion = "3.1.12"
  private val twirlApiVersion = "2.0.9"
  private val rtVersion = "4.0.4"
  private val jakartaXmlWsApiVersion = "4.0.3"
  private val scalatestplusPlayVersion = "7.0.2"
  private val nettyAllVersion = "4.2.12.Final"
  private val jwtCoreVersion = "11.0.4"
  private val scalajsScriptsVersion = "1.3.0"
  private val notificationsJavaClientVersion = "6.0.0-RELEASE"
  private val fontAwesomeVersion = "4.7.0"
  private val webjarsPlayVersion = "3.0.10"
  private val janinoAltVersion = "3.0.16"
  private val mailVersion = "1.4.7"
  private val jaxbApiVersion = "2.3.1"
  private val mockito34Version = "3.2.10.0"

  private val coreDependencies: Seq[ModuleID] = Seq(
    "com.github.gphat" %% "censorinus" % censorinusVersion,
    "com.github.jwt-scala" %% "jwt-core" % jwtCoreVersion,
    "com.hierynomus" % "sshj" % sshJVersion,
    "com.vmunier" %% "scalajs-scripts" % scalajsScriptsVersion,
    "com.typesafe" % "config" % typesafeConfigVersion,
    "joda-time" % "joda-time" % jodaTimeVersion,
    "uk.gov.service.notify" % "notifications-java-client" % notificationsJavaClientVersion,
    "software.amazon.awssdk" % "s3" % awsVersion,
    "org.playframework.twirl" %% "twirl-api" % twirlApiVersion,
  )

  private val xmlAndMailDependencies: Seq[ModuleID] = Seq(
    "javax.mail" % "mail" % mailVersion,
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % jakartaXmlWsApiVersion,
    "com.sun.xml.ws" % "rt" % rtVersion,
    "javax.xml.bind" % "jaxb-api" % jaxbApiVersion,
  )

  private val pekkoDependencies: Seq[ModuleID] = Seq(
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
  )

  private val slickDependencies: Seq[ModuleID] = Seq(
    "com.typesafe.slick" %% "slick" % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.typesafe.slick" %% "slick-codegen" % slickVersion,
  )

  private val loggingDependencies: Seq[ModuleID] = Seq(
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "ch.qos.logback.contrib" % "logback-json-classic" % logbackContribVersion,
    "ch.qos.logback.contrib" % "logback-jackson" % logbackContribVersion,
    "org.codehaus.janino" % "janino" % janinoVersion,
    "org.codehaus.janino" % "janino" % janinoAltVersion,
  )

  private val dataDependencies: Seq[ModuleID] = Seq(
    "org.pac4j" % "pac4j-saml" % pac4jSamlVersion,
    "org.apache.commons" % "commons-csv" % csvCommonsVersion,
    "org.apache.poi" % "poi" % poiVersion,
    "org.apache.poi" % "poi-ooxml" % poiVersion,
    "org.postgresql" % "postgresql" % postgresVersion,
    "org.renjin" % "renjin-script-engine" % renjinVersion,
    "io.netty" % "netty-all" % nettyAllVersion,
  )

  private val webjarDependencies: Seq[ModuleID] = Seq(
    "org.webjars" % "font-awesome" % fontAwesomeVersion % Provided,
    "org.webjars" % "bootstrap" % bootstrapVersion % Provided,
    "org.webjars" %% "webjars-play" % webjarsPlayVersion,
    "org.webjars" % "webjars-locator" % webjarsLocator,
  )

  private val drtDependencies: Seq[ModuleID] = Seq(
    "uk.gov.homeoffice" %% "drt-birmingham-schema" % birminghamSchema,
    "uk.gov.homeoffice" %% "drt-cirium" % cirium,
    "uk.gov.homeoffice" %% "drt-lib" % lib,
    "uk.gov.homeoffice" %% "bluebus" % bluebus,
  )

  val compileDependencies: Seq[ModuleID] =
    coreDependencies ++
      xmlAndMailDependencies ++
      pekkoDependencies ++
      slickDependencies ++
      loggingDependencies ++
      dataDependencies ++
      webjarDependencies ++
      drtDependencies

  val testDependencies: Seq[ModuleID] = Seq(
    "com.h2database" % "h2" % h2Version,
    "com.lihaoyi" %% "utest" % utest % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-stream-testkit" % pekkoVersion % Test,

    "org.specs2" %% "specs2-core" % specs2Version % Test,
    "org.specs2" %% "specs2-junit" % specs2Version % Test,
    "org.specs2" %% "specs2-mock" % specs2Version % Test,
    "org.mockito" % "mockito-core" % mockitoVersion % Test,
    "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusPlayVersion % Test,
    "org.scalatestplus" %% "mockito-3-4" % mockito34Version % Test,
  )

  val all: Seq[ModuleID] = compileDependencies ++ testDependencies
}


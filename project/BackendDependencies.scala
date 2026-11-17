import sbt.*

object BackendDependencies {
  import SharedDependencyVersions.drt.{ birminghamSchema, bluebus, cirium, lib }
  import SharedDependencyVersions.shared.{ utest, webjarsLocator }

  private val pekkoVersion = "1.7.1"
  private val pekkoHttpVersion = "1.4.2"
  private val slickVersion = "3.5.2" // restricted by pekko-persistence-jdbc 1.3.0
  private val specs2Version = "4.23.0"
  private val logbackContribVersion = "0.1.5"
  private val poiVersion = "5.5.1"

  private val coreDependencies: Seq[ModuleID] = Seq(
    "com.github.gphat"        %% "censorinus"                % "2.1.16",
    "com.github.jwt-scala"    %% "jwt-core"                  % "11.0.4",
    "com.hierynomus"           % "sshj"                      % "0.41.2",
    "com.vmunier"             %% "scalajs-scripts"           % "1.3.0",
    "com.typesafe"             % "config"                    % "1.4.10",
    "joda-time"                % "joda-time"                 % "2.15.1",
    "uk.gov.service.notify"    % "notifications-java-client" % "6.0.1-RELEASE",
    "software.amazon.awssdk"   % "s3"                        % "2.57.13",
    "org.playframework.twirl" %% "twirl-api"                 % "2.0.9"
  )

  private val xmlAndMailDependencies: Seq[ModuleID] = Seq(
    "javax.mail"     % "mail"               % "1.4.7",
    "jakarta.xml.ws" % "jakarta.xml.ws-api" % "4.0.3",
    "javax.xml.bind" % "jaxb-api"           % "2.3.1"
  )

  private val pekkoDependencies: Seq[ModuleID] = Seq(
    "org.apache.pekko" %% "pekko-persistence-jdbc"      % "1.3.0",
    "org.apache.pekko" %% "pekko-persistence-typed"     % pekkoVersion,
    "org.apache.pekko" %% "pekko-remote"                % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-testkit"   % pekkoVersion,
    "org.apache.pekko" %% "pekko-actor-testkit-typed"   % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit"               % pekkoVersion,
    "org.apache.pekko" %% "pekko-serialization-jackson" % pekkoVersion,
    "org.apache.pekko" %% "pekko-pki"                   % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream-typed"          % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence"           % pekkoVersion,
    "org.apache.pekko" %% "pekko-persistence-query"     % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j"                 % pekkoVersion,
    "org.apache.pekko" %% "pekko-http"                  % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-caching"          % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json"       % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-xml"              % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-stream"                % pekkoVersion
  )

  private val slickDependencies: Seq[ModuleID] = Seq(
    "com.typesafe.slick" %% "slick"          % slickVersion,
    "com.typesafe.slick" %% "slick-hikaricp" % slickVersion,
    "com.typesafe.slick" %% "slick-codegen"  % slickVersion
  )

  private val loggingDependencies: Seq[ModuleID] = Seq(
    "ch.qos.logback"         % "logback-classic"      % "1.6.8",
    "ch.qos.logback.contrib" % "logback-json-classic" % logbackContribVersion,
    "ch.qos.logback.contrib" % "logback-jackson"      % logbackContribVersion
  )

  private val dataDependencies: Seq[ModuleID] = Seq(
    "org.apache.commons" % "commons-csv"          % "1.14.1",
    "org.apache.poi"     % "poi"                  % poiVersion,
    "org.apache.poi"     % "poi-ooxml"            % poiVersion,
    "org.postgresql"     % "postgresql"           % "42.7.14",
    "org.renjin"         % "renjin-script-engine" % "0.9.2725"
  )

  private val webjarDependencies: Seq[ModuleID] = Seq(
    "org.webjars"  % "font-awesome"    % "4.7.0" % Provided,
    "org.webjars"  % "bootstrap"       % "3.3.6" % Provided,
    "org.webjars" %% "webjars-play"    % "3.0.10",
    "org.webjars"  % "webjars-locator" % webjarsLocator
  )

  private val drtDependencies: Seq[ModuleID] = Seq(
    "uk.gov.homeoffice" %% "drt-birmingham-schema" % birminghamSchema,
    "uk.gov.homeoffice" %% "drt-cirium"            % cirium,
    "uk.gov.homeoffice" %% "drt-lib"               % lib,
    "uk.gov.homeoffice" %% "bluebus"               % bluebus
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
    "com.h2database"          % "h2"                   % "2.5.252",
    "com.lihaoyi"            %% "utest"                % utest         % Test,
    "org.apache.pekko"       %% "pekko-testkit"        % pekkoVersion  % Test,
    "org.apache.pekko"       %% "pekko-stream-testkit" % pekkoVersion  % Test,
    "org.specs2"             %% "specs2-core"          % specs2Version % Test,
    "org.specs2"             %% "specs2-junit"         % specs2Version % Test,
    "org.specs2"             %% "specs2-mock"          % specs2Version % Test,
    "org.mockito"             % "mockito-core"         % "5.24.0"      % Test,
    "org.scalatestplus.play" %% "scalatestplus-play"   % "7.0.2"       % Test,
    "org.scalatestplus"      %% "mockito-3-4"          % "3.2.10.0"    % Test
  )

  val all: Seq[ModuleID] = compileDependencies ++ testDependencies

  // Spark 4.3.0 pulls Netty 4.2.13.Final, which predates the fix for GHSA-5pvg-856g-cp85 (fixed in 4.2.15.Final).
  // org.bouncycastle 1.86 is required to fix CVE-2026-8763, which is a vulnerability in 1.85 and earlier.
  val dependencyOverrides: Seq[ModuleID] = Seq(
    "io.netty"                % "netty-all"                % "4.2.20.Final",
    "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    "org.bouncycastle"        % "bcprov-jdk18on"           % "1.86",
    "org.bouncycastle"        % "bcpkix-jdk18on"           % "1.86",
    "org.bouncycastle"        % "bcutil-jdk18on"           % "1.86"
  )
}

// repository for Typesafe plugins
resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/ivy-releases/"

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

libraryDependencies += "org.scala-js" %% "scalajs-env-jsdom-nodejs" % "1.1.1"

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.2")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.20.1")

addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.3.0")

addSbtPlugin("com.github.sbt" % "sbt-less" % "2.0.1")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.4")

addSbtPlugin("org.playframework" % "sbt-plugin" % "3.0.9")

addSbtPlugin("com.github.sbt" % "sbt-gzip" % "2.0.0")

addSbtPlugin("com.github.sbt" % "sbt-digest" % "2.1.0")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.2.13")

addSbtPlugin("com.github.sbt" % "sbt-js-engine" % "1.3.9")

addSbtPlugin("io.github.cquiroz" % "sbt-tzdb" % "4.3.0")

addSbtPlugin("net.nmoncho" % "sbt-dependency-check" % "1.8.4")

addDependencyTreePlugin

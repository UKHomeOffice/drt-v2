// repository for Typesafe plugins
resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/ivy-releases/"

ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % VersionScheme.Always

addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.3.1")

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.18.1")

addSbtPlugin("com.vmunier" % "sbt-web-scalajs" % "1.3.0")

addSbtPlugin("com.github.sbt" % "sbt-less" % "2.0.1")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.1")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.9.6")

addSbtPlugin("com.github.sbt" % "sbt-gzip" % "2.0.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.4")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.2.11")

addSbtPlugin("net.vonbuchholtz" % "sbt-dependency-check" % "5.1.0")

addSbtPlugin("com.github.sbt" % "sbt-js-engine" % "1.3.9")

addSbtPlugin("io.github.cquiroz" % "sbt-tzdb" % "4.2.0")

addDependencyTreePlugin

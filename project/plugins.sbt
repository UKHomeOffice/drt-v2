// repository for Typesafe plugins
resolvers += "Typesafe Releases" at "https://repo.typesafe.com/typesafe/ivy-releases/"

addSbtPlugin("org.portable-scala" % "sbt-scalajs-crossproject" % "1.3.2")

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "1.13.2")

addSbtPlugin("ch.epfl.scala" % "sbt-web-scalajs-bundler" % "0.21.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.1.2")

addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.9.9")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.8.19")

addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.4")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.11.0")

addSbtPlugin("org.scalastyle" %% "scalastyle-sbt-plugin" % "1.0.0")

addSbtPlugin("com.sksamuel.scapegoat" %% "sbt-scapegoat" % "1.2.1")

addSbtPlugin("net.vonbuchholtz" % "sbt-dependency-check" % "5.1.0")

addDependencyTreePlugin

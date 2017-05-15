// repository for Typesafe plugins
resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

// the protobuf sbt plugin must come before the scalajs plugin, see
// https://github.com/scalapb/ScalaPB/issues/150
addSbtPlugin("com.thesamet" % "sbt-protoc" % "0.99.6")

libraryDependencies += "com.trueaccord.scalapb" %% "compilerplugin" % "0.6.0-pre1"

addSbtPlugin("org.scala-js" % "sbt-scalajs" % "0.6.15")

//bundles npm packages - should ultimately replace the /bundle(s)/ dirs and the
// jsDependencies from webjars
//addSbtPlugin("ch.epfl.scala" % "sbt-scalajs-bundler" % "0.6.0")

addSbtPlugin("ch.epfl.scala" % "sbt-web-scalajs-bundler" % "0.6.0")


//addSbtPlugin("com.typesafe.sbt" % "sbt-web" % "1.1.1")

addSbtPlugin("com.typesafe.sbt" % "sbt-js-engine" % "1.1.3")

addSbtPlugin("com.typesafe.sbt" % "sbt-less" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.0.0")

addSbtPlugin("com.typesafe.play" % "sbt-plugin" % "2.5.8")

//addSbtPlugin("com.vmunier" % "sbt-play-scalajs" % "0.3.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-digest" % "1.1.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-gzip" % "1.0.0")


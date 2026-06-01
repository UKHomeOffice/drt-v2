import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.*

object FrontendDependencies {
  import SharedDependencyVersions.drt.lib
  import SharedDependencyVersions.shared.{scalajsReact, upickle, utest}

  val scalajsReactVersion: String = scalajsReact

  private val scalaDomVersion = "2.8.0"
  private val scalaCssVersion = "1.0.0"
  private val scalaJsMomentJsVersion = "0.10.9"
  private val diodeVersion = "1.2.0-RC4"
  private val scalaJsReactMaterialUiVersion = "0.1.18"
  private val scalajsJavaSecurerandomVersion = "1.0.0"
  private val scalaJavaTimeVersion = "2.5.0"
  private val scalaCryptoVersion = "1.0.0"
  private val scalaUriVersion = "4.0.3"
  private val scalaTestVersion = "3.2.19"

  val dependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core" % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "extra" % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "test" % scalajsReactVersion % Test,
    "uk.gov.homeoffice" %%% "drt-lib" % lib,
    "com.github.japgolly.scalacss" %%% "ext-react" % scalaCssVersion,
    "io.suzaku" %%% "diode" % diodeVersion,
    "io.suzaku" %%% "diode-react" % diodeVersion,
    "org.scala-js" %%% "scalajs-dom" % scalaDomVersion,
    "org.scala-js" %%% "scalajs-java-securerandom" % scalajsJavaSecurerandomVersion,
    "com.lihaoyi" %%% "utest" % utest % Test,
    "com.lihaoyi" %%% "upickle" % upickle,
    "ru.pavkin" %%% "scala-js-momentjs" % scalaJsMomentJsVersion,
    "io.github.cquiroz" %%% "scala-java-time" % scalaJavaTimeVersion,

    "com.freshcodelimited" %%% "scalajs-react-material-ui-core" % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-lab" % scalaJsReactMaterialUiVersion,
    "com.dedipresta" %%% "scala-crypto" % scalaCryptoVersion,
    "io.lemonlabs" %%% "scala-uri" % scalaUriVersion,

    "org.scalatest" %%% "scalatest" % scalaTestVersion % Test,
  ))
}


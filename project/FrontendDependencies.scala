import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.*

object FrontendDependencies {
  import SharedDependencyVersions.drt.lib
  import SharedDependencyVersions.shared.{ scalajsReact, upickle, utest }

  val scalajsReactVersion: String = scalajsReact

  private val diodeVersion = "1.2.0-RC4"
  private val scalaJsReactMaterialUiVersion = "0.1.18"

  val dependencies = Def.setting(Seq(
    "com.github.japgolly.scalajs-react" %%% "core"                      % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "extra"                     % scalajsReactVersion,
    "com.github.japgolly.scalajs-react" %%% "test"                      % scalajsReactVersion % Test,
    "uk.gov.homeoffice"                 %%% "drt-lib"                   % lib,
    "com.github.japgolly.scalacss"      %%% "ext-react"                 % "1.0.0",
    "io.suzaku"                         %%% "diode"                     % diodeVersion,
    "io.suzaku"                         %%% "diode-react"               % diodeVersion,
    "org.scala-js"                      %%% "scalajs-dom"               % "2.8.0",
    "org.scala-js"                      %%% "scalajs-java-securerandom" % "1.0.0",
    "com.lihaoyi"                       %%% "utest"                     % utest               % Test,
    "com.lihaoyi"                       %%% "upickle"                   % upickle,
    "ru.pavkin"                         %%% "scala-js-momentjs"         % "0.10.9",
    "io.github.cquiroz"                 %%% "scala-java-time"           % "2.5.0",

    "com.freshcodelimited" %%% "scalajs-react-material-ui-core"  % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-icons" % scalaJsReactMaterialUiVersion,
    "com.freshcodelimited" %%% "scalajs-react-material-ui-lab"   % scalaJsReactMaterialUiVersion,
    "com.dedipresta"       %%% "scala-crypto"                    % "1.0.0",
    "io.lemonlabs"         %%% "scala-uri"                       % "4.0.3",

    "org.scalatest" %%% "scalatest" % "3.2.19" % Test
  ))
}

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin.autoImport.*
import sbt.*

object SharedDependencies {
  import SharedDependencyVersions.drt.lib
  import SharedDependencyVersions.shared.{upickle, webjarsLocator}

  val dependencies = Def.setting(Seq(
    "com.lihaoyi" %%% "upickle" % upickle,
    "uk.gov.homeoffice" %%% "drt-lib" % lib exclude ("org.apache.spark", "spark-mllib_2.13"),
    "io.suzaku" %%% "boopickle" % "1.3.3",
    "org.webjars" % "webjars-locator" % webjarsLocator,
  ))
}


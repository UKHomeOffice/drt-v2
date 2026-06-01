import com.timushev.sbt.updates.UpdatesPlugin.autoImport.{
  dependencyUpdatesFailBuild,
  dependencyUpdatesFilter,
  moduleFilterRemoveValue
}
import sbt.*

object SbtUpdatesSettings {
  val sbtUpdatesSettings: Seq[Def.Setting[?]] = Seq(
    dependencyUpdatesFailBuild := false,
    dependencyUpdatesFilter -= moduleFilter("org.scala-lang"),
    dependencyUpdatesFilter -= moduleFilter("org.apache.pekko"),
    dependencyUpdatesFilter -= moduleFilter("com.typesafe.slick")
    // Pekko and Slick updates are intentionally reviewed manually because they are higher risk in this repo.
    // Keep them out of the default dependencyUpdates output so the report stays focused on lower-risk upgrades.
  )
}

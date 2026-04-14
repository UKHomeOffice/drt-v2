import com.timushev.sbt.updates.UpdatesKeys.dependencyUpdates
import com.timushev.sbt.updates.UpdatesPlugin.autoImport.{dependencyUpdatesFailBuild, dependencyUpdatesFilter, moduleFilterRemoveValue}
import sbt.Keys.*
import sbt.{Def, *}

object SbtUpdatesSettings {

  lazy val sbtUpdatesSettings: Seq[Def.Setting[?]] = Seq(
    dependencyUpdatesFailBuild := false,
    (Compile / compile) := ((Compile / compile) dependsOn dependencyUpdates).value,
    dependencyUpdatesFilter -= moduleFilter("org.scala-lang"),
    dependencyUpdatesFilter -= moduleFilter("org.apache.pekko")
    //Scala and Pekko to be manually updated periodically
    //If needed add other exclusions below
  )
}
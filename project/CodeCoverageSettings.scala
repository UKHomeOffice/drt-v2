import sbt.*
import sbt.Keys.*
import scoverage.ScoverageKeys.coverageExcludedPackages

object CodeCoverageSettings {
  val codeCoverageSettings: Seq[Def.Setting[?]] = Seq(
    Test / parallelExecution := false,
    Test / javaOptions += "-Duser.timezone=UTC",
    coverageExcludedPackages := "<empty>;.*Main.*"
  )
}

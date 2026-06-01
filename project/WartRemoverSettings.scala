import sbt.*
import sbt.Keys.*
import wartremover.Wart
import wartremover.WartRemover.autoImport.*

// Settings for WartRemover warnings, these should be addressed in the codebase and set as errors later on,
// but for now we want to be able to compile and run tests without having to fix all the warnings at once.
// List will be expanded as we add more warnings and address them in the codebase.
object WartRemoverSettings {
  private val compileWarnings: Seq[Wart] = Seq(
    Wart.Null,
    Wart.Throw,
    Wart.OptionPartial,
    Wart.TryPartial,
    Wart.AsInstanceOf,
    Wart.IsInstanceOf
  )

  private val testWarnings: Seq[Wart] = Seq(
    Wart.Null
  )

  val wartRemoverSettings: Seq[Def.Setting[?]] = Seq(
    Compile / wartremoverWarnings ++= compileWarnings,
    Test / wartremoverWarnings ++= testWarnings,
    wartremoverExcluded ++= Seq(
      (Compile / sourceManaged).value,
      (Test / sourceManaged).value
    )
  )
}

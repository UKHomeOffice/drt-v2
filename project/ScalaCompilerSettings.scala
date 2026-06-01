object ScalaCompilerSettings {
  val scalacOptions: Seq[String] = Seq(
    "-Xlint",
    "-unchecked",
    "-deprecation",
    "-feature",
    "-Ywarn-dead-code",
    "-Ywarn-unused",
    "-Ywarn-value-discard",
  )
}

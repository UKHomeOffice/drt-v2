package drt.client.util

object TerminalName {
  private val TerminalNameIndex: Map[String, String] = Map(
    "N" -> "North",
    "S" -> "South",
  )

  def getTerminalDisplayName(terminalName: String): Option[String] =
    TerminalNameIndex.get(terminalName)
}

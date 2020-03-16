package services.exports.summaries

trait TerminalSummaryLike {
  def isEmpty: Boolean

  val lineEnding = "\n"

  def toCsv: String

  def csvHeader: String

  def toCsvWithHeader: String = csvHeader + lineEnding + toCsv
}

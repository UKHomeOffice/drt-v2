package services.exports.summaries

trait TerminalSummaryLike {
  val lineEnding = "\n"

  def toCsv: String

  def csvHeader: String

  def toCsvWithHeader: String = csvHeader + lineEnding + toCsv
}

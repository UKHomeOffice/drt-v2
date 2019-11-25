package test.feeds.test

import drt.shared.{Arrival, LiveFeedSource, Terminals}
import org.slf4j.{Logger, LoggerFactory}
import services.SDate

import scala.collection.immutable.Seq
import scala.util.Try

object CSVFixtures {

  val log: Logger = LoggerFactory.getLogger(getClass)

  object ArrivalsCSVFixture {

    object fieldMap {
      val Estimated = 4
      val Actual = 5
      val EstimatedChox = 6
      val ActualChox = 7
      val Stand = 8
      val MaxPax = 9
      val ActPax = 10
      val TranPax = 11
      val Terminal = 0
      val rawICAO = 1
      val rawIATA = 1
      val Origin = 2
      val Scheduled = 3
    }

  }

  def csvPathToArrivalsOnDate(forDate: String, path: String) = {

    def timeToSDate = timeToSDateOnDate(forDate) _

    val maybeArrivals: Seq[Try[Arrival]] = csvPathToRows(path).drop(1).map(csvRow => {
      val fields = csvRow.split(",")
      import ArrivalsCSVFixture.fieldMap._
      Try(Arrival(
        None,
        "Unk",
        timeToSDate(fields(Estimated)),
        timeToSDate(fields(Actual)),
        timeToSDate(fields(EstimatedChox)),
        timeToSDate(fields(ActualChox)),
        None,
        Option(fields(Stand)),
        Option(fields(MaxPax).toInt),
        Option(fields(ActPax).toInt),
        Option(fields(TranPax).toInt),
        None,
        None,
        "TEST",
        Terminals.Terminal(fields(Terminal)),
        fields(rawICAO),
        fields(rawIATA),
        fields(Origin),
        timeToSDate(fields(Scheduled)).getOrElse(SDate.now().millisSinceEpoch),
        None,
        Set(LiveFeedSource)
      ))
    })

    log.info(s"Found ${maybeArrivals.length} arrival fixtures in $path")

    maybeArrivals
  }

  def timeToSDateOnDate(forDate: String)(time: String) = SDate.tryParseString(forDate + "T" + time + "Z")
    .toOption
    .map(_.millisSinceEpoch)

  def csvPathToRows(fileName: String): Seq[String] = {
    val bufferedSource = scala.io.Source.fromFile(fileName)
    val lines = bufferedSource.getLines()
    lines.toList
  }
}

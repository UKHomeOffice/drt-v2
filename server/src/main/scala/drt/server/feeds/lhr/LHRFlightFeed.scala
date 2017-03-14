package drt.server.feeds.lhr

import akka.NotUsed
import akka.actor.Cancellable
import akka.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory
import org.apache.commons.csv.{CSVFormat, CSVParser}
import org.joda.time.DateTime
import org.joda.time.format.{DateTimeFormat, DateTimeFormatter}
import org.slf4j.LoggerFactory
import spatutorial.shared.ApiFlight

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.sys.process._
import scala.util.{Failure, Success, Try}


case class LHRLiveFlight(
                          term: String,
                          flightCode: String,
                          operator: String,
                          from: String,
                          airportName: String,
                          scheduled: org.joda.time.DateTime,
                          estimated: Option[org.joda.time.DateTime],
                          touchdown: Option[org.joda.time.DateTime],
                          estChox: Option[org.joda.time.DateTime],
                          actChox: Option[org.joda.time.DateTime],
                          stand: Option[String],
                          maxPax: Option[Int],
                          actPax: Option[Int],
                          connPax: Option[Int]
                        ) {
  def flightNo = 23
}

case class LHRCsvException(originalLine: String, idx: Int, innerException: Throwable) extends Exception {
  override def toString = s"$originalLine : $idx $innerException"
}


case class LHRFlightFeed(csvLines: Iterator[String]) {


  def opt(s: String) = if (s.isEmpty) None else Option(s)

  def pd(s: String) = LHRFlightFeed.parseDateTime(s)

  def optDate(s: String) = if (s.isEmpty) None else Option(pd(s))

  def optInt(s: String) = if (s.isEmpty) None else Option(s.toInt)

  lazy val lhrFlights: Iterator[Try[LHRLiveFlight]] = {

    csvLines.zipWithIndex.drop(1).map { case (l, idx) =>

      val t: Try[LHRLiveFlight] = Try {
        val csv = CSVParser.parse(l, CSVFormat.DEFAULT)

        val csvRecords = csv.iterator().toList
        csvRecords match {
          case csvRecord :: Nil =>
            def splitRow(i: Int) = csvRecord.get(i)

            val sq: (String) => String = (x) => x
            LHRLiveFlight(
              term = s"T${sq(splitRow(0))}",
              flightCode = sq(splitRow(1)),
              operator = sq(splitRow(2)),
              from = sq(splitRow(3)),
              airportName = sq(splitRow(4)),
              scheduled = pd(splitRow(5)),
              estimated = optDate(splitRow(6)),
              touchdown = optDate(splitRow(7)),
              estChox = optDate(splitRow(8)),
              actChox = optDate(sq(splitRow(9))),
              stand = opt(splitRow(10)),
              maxPax = optInt(splitRow(11)),
              actPax = optInt(splitRow(12)),
              connPax = optInt(splitRow(13)))
          case Nil => throw new Exception(s"Invalid CSV row: $l")
        }
      }
      t match {
        case Success(s) => Success(s)
        case Failure(t) =>
          Failure(LHRCsvException(l, idx, t))
      }
    }
  }
  val walkTimeMinutes = 4

  lazy val successfulFlights = lhrFlights.collect { case Success(s) => s }

  def dateOptToStringOrEmptyString = (dto: Option[DateTime]) => dto.map(_.toDateTimeISO.toString()).getOrElse("")

  lazy val copiedToApiFlights: Source[List[ApiFlight], NotUsed] = Source(
    List(
      successfulFlights.map(flight => {
        val pcpTime: Long = flight.scheduled.plusMinutes(walkTimeMinutes).getMillis
        val schDtIso = flight.scheduled.toDateTimeISO().toString()
        val defaultPaxPerFlight = 200
        ApiFlight(
          Operator = flight.operator,
          Status = "UNK",
          EstDT = dateOptToStringOrEmptyString(flight.estimated),
          ActDT = dateOptToStringOrEmptyString(flight.touchdown),
          EstChoxDT = dateOptToStringOrEmptyString(flight.estChox),
          ActChoxDT = dateOptToStringOrEmptyString(flight.actChox),
          Gate = "",
          Stand = flight.stand.getOrElse(""),
          MaxPax = flight.maxPax.getOrElse(-1),
          ActPax = flight.actPax.getOrElse(defaultPaxPerFlight),
          TranPax = flight.connPax.getOrElse(-1),
          RunwayID = "",
          BaggageReclaimId = "",
          FlightID = flight.hashCode(),
          AirportID = "LHR",
          Terminal = flight.term,
          ICAO = flight.flightCode,
          IATA = flight.flightCode,
          Origin = flight.from,
          SchDT = schDtIso,
          PcpTime = pcpTime)
      }).toList))
}

object LHRFlightFeed {

  def csvContentsProviderProd(): String = {
    Seq(
      "/usr/local/bin/lhr-live-fetch-latest-feed.sh",
      "-u", ConfigFactory.load.getString("lhr_live_username"),
      "-p", ConfigFactory.load.getString("lhr_live_password")).!!
  }

  val pattern: DateTimeFormatter = DateTimeFormat.forPattern("HH:mm dd/MM/YYYY")
  val log = LoggerFactory.getLogger(classOf[LHRFlightFeed])
  def parseDateTime(dateString: String) = pattern.parseDateTime(dateString)

  def apply(csvContentsProvider: () => String = csvContentsProviderProd _): Source[List[ApiFlight], Cancellable] = {
    log.info(s"preparing lhrfeed")

    val pollFrequency = 1 minute
    val initialDelayImmediately: FiniteDuration = 1 milliseconds
    val tickingSource: Source[Source[List[ApiFlight], NotUsed], Cancellable] = Source.tick(initialDelayImmediately, pollFrequency, NotUsed)
      .map((t) => {
        log.info(s"about to request csv")
        val  csvContents: String = csvContentsProvider()
        log.info(s"Got csvContents: $csvContents")
        val f = LHRFlightFeed(csvContents.split("\n").toIterator).copiedToApiFlights
        log.info(s"copied to api flights")
        f
      })

    val recoverableTicking: Source[List[ApiFlight], Cancellable] = tickingSource.flatMapConcat(s => s.map(x => x))

    recoverableTicking
  }
}
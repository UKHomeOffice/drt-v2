package drt.server.feeds.chroma

import akka.actor.{ActorSystem, Cancellable}
import akka.event.LoggingAdapter
import akka.stream.scaladsl.Source
import controllers.MockedChromaSendReceive
import drt.chroma.chromafetcher.ChromaFetcher
import drt.chroma.chromafetcher.ChromaFetcher.ChromaSingleFlight
import drt.chroma.{DiffingStage, StreamingChromaFlow}
import drt.http.ProdSendAndReceive
import drt.shared.ApiFlight
import drt.shared.FlightsApi.Flights

import scala.concurrent.duration._


trait ChromaFetcherLike {
  def system: ActorSystem

  def chromafetcher: ChromaFetcher
}

case class MockChroma(system: ActorSystem) extends ChromaFetcherLike {
  self =>
  system.log.info("Mock Chroma init")
  override val chromafetcher = new ChromaFetcher with MockedChromaSendReceive {
    implicit val system: ActorSystem = self.system
  }
}

case class ProdChroma(system: ActorSystem) extends ChromaFetcherLike {
  self =>
  override val chromafetcher = new ChromaFetcher with ProdSendAndReceive {
    implicit val system: ActorSystem = self.system
  }
}


case class ChromaFlightFeed(log: LoggingAdapter, chromaFetcher: ChromaFetcherLike) {
  flightFeed =>
  val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 100 seconds)

  object EdiChroma {
    val ArrivalsHall1 = "A1"
    val ArrivalsHall2 = "A2"
    val ediMapTerminals = Map(
      "T1" -> ArrivalsHall1,
      "T2" -> ArrivalsHall2
    )

    val ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()).map(csfs =>
      csfs.map(ediBaggageTerminalHack(_)).map(csf => ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    def ediBaggageTerminalHack(csf: ChromaSingleFlight) = {
      if (csf.BaggageReclaimId == "7") csf.copy(Terminal = ArrivalsHall2) else csf
    }
  }


  def apiFlightCopy(ediMapping: Source[Seq[ChromaSingleFlight], Cancellable]) = {
    ediMapping.map(flights =>
      flights.map(flight => {
        val walkTimeMinutes = 4
        val pcpTime: Long = org.joda.time.DateTime.parse(flight.SchDT).plusMinutes(walkTimeMinutes).getMillis
        ApiFlight(
          Operator = flight.Operator,
          Status = flight.Status, EstDT = flight.EstDT,
          ActDT = flight.ActDT, EstChoxDT = flight.EstChoxDT,
          ActChoxDT = flight.ActChoxDT,
          Gate = flight.Gate,
          Stand = flight.Stand,
          MaxPax = flight.MaxPax,
          ActPax = flight.ActPax,
          TranPax = flight.TranPax,
          RunwayID = flight.RunwayID,
          BaggageReclaimId = flight.BaggageReclaimId,
          FlightID = flight.FlightID,
          AirportID = flight.AirportID,
          Terminal = flight.Terminal,
          ICAO = flight.ICAO,
          IATA = flight.IATA,
          Origin = flight.Origin,
          SchDT = flight.SchDT,
          PcpTime = pcpTime
        )
      }).toList)
  }

  val copiedToApiFlights = apiFlightCopy(EdiChroma.ediMapping).map(Flights(_))

  def chromaEdiFlights(): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 10 seconds)

    def ediMapping = chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()).map(csfs =>
      csfs.map(EdiChroma.ediBaggageTerminalHack(_)).map(csf => EdiChroma.ediMapTerminals.get(csf.Terminal) match {
        case Some(renamedTerminal) =>
          csf.copy(Terminal = renamedTerminal)
        case None => csf
      })
    )

    apiFlightCopy(ediMapping)
  }

  def chromaVanillaFlights(): Source[List[ApiFlight], Cancellable] = {
    val chromaFlow = StreamingChromaFlow.chromaPollingSource(log, chromaFetcher.chromafetcher, 10 seconds)
    apiFlightCopy(chromaFlow.via(DiffingStage.DiffLists[ChromaSingleFlight]()))
  }
}

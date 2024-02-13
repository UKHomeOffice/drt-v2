package providers

import actors.PartitionedPortStateActor
import actors.PartitionedPortStateActor.FlightsRequest
import akka.NotUsed
import akka.actor.{ActorRef, Props}
import akka.pattern.ask
import akka.stream.scaladsl.{Sink, Source}
import akka.util.Timeout
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, Arrival, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.{LocalDate, SDate, UtcDate}

import scala.concurrent.Future

case class FlightsProvider(flightsRouterActor: ActorRef)
                          (implicit timeout: Timeout) {
  def singleTerminal: Terminal => (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    terminal => (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlightsForTerminals(startMillis, endMillis, Seq(terminal))
      flightsByUtcDate(request)
    }

  def terminalLocalDate: Terminal => LocalDate => Source[Seq[ApiFlightWithSplits], NotUsed] =
    terminal => (start, end) => (terminal, localDate) => {
      val sdate = SDate(localDate)
      val utcDates = Set(sdate.toUtcDate, sdate.addDays(1).addMinutes(-1).toUtcDate)
      val preProcess = populateMaxPax()
      Source(utcDates.toList)
        .mapAsync(1) { utcDate =>
          val actor = system.actorOf(Props(new FlightsActor(terminal, utcDate, None)))
          actor
            .ask(GetState)
            .mapTo[Seq[Arrival]].map { arrivals =>
              actor ! PoisonPill
              arrivals
                .filter { a =>
                  SDate(a.Scheduled).toLocalDate == localDate && !a.Origin.isDomesticOrCta && !a.isCancelled
                }
                .map(a => (a.unique, a)).toMap.values.toSeq
            }
            .flatMap(preProcess(utcDate, _))
            .recoverWith {
              case t =>
                log.error(s"Failed to get state for $terminal on $utcDate", t)
                Future.successful(Seq.empty[Arrival])
            }
        }
        .runWith(Sink.seq)
        .map(_.flatten)
        .recoverWith {
          case t =>
            log.error(s"Failed to get state for $terminal on $localDate", t)
            Future.successful(Seq.empty[Arrival])
        }
    }


  def allTerminals: (UtcDate, UtcDate) => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] =
    (start, end) => {
      val startMillis = SDate(start).millisSinceEpoch
      val endMillis = SDate(end).addDays(1).addMinutes(-1).millisSinceEpoch
      val request = PartitionedPortStateActor.GetFlights(startMillis, endMillis)
      flightsByUtcDate(request)
    }

  private def flightsByUtcDate(request: FlightsRequest): Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed] = {
    Source
      .future(flightsRouterActor.ask(request).mapTo[Source[(UtcDate, FlightsWithSplits), NotUsed]])
      .flatMapConcat(identity)
      .map {
        case (date, flights) => (date, flights.flights.values.toSeq)
      }
  }
}

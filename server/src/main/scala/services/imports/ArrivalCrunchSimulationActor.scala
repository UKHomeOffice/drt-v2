package services.imports

import actors.PartitionedPortStateActor.GetFlights
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import akka.actor.{Actor, ActorLogging, PoisonPill}
import akka.pattern.{StatusReply, pipe}
import akka.stream.scaladsl.Source
import drt.shared.CrunchApi.DeskRecMinutes
import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamInitialized}
import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits
import uk.gov.homeoffice.drt.time.SDate

import scala.concurrent.{ExecutionContextExecutor, Promise}
import scala.util.Try

class ArrivalCrunchSimulationActor(fws: FlightsWithSplits) extends Actor with ActorLogging {
  implicit val dispatcher: ExecutionContextExecutor = context.dispatcher

  var promisedResult: Promise[DeskRecMinutes] = Promise[DeskRecMinutes]

  override def receive: Receive = {
    case GetFlights(_, _) =>
      val groupedByDay = fws.flights.values
        .groupBy(f => SDate(f.apiFlight.Scheduled).toUtcDate)
        .map {
          case (utcDate, flights) => (utcDate, FlightsWithSplits(flights))
        }
      sender() ! Source(groupedByDay)

    case GetState =>
      val replyTo = sender()
      promisedResult.future.pipeTo(replyTo)

    case m: DeskRecMinutes =>
      promisedResult.complete(Try(m))

    case StreamInitialized =>
      sender() ! StatusReply.Ack

    case StreamCompleted =>
      self ! PoisonPill

    case unexpected =>
      log.warning(s"Got and unexpected message $unexpected")
  }
}

package passengersplits

import akka.actor._
import passengersplits.core.PassengerInfoRouterActor.PassengerSplitsAck
import passengersplits.parsing.PassengerInfoParser.VoyagePassengerInfo
import passengersplits.performance.PassengerInfoBatchComplete

import scala.collection.immutable.Seq

object PassengerInfoBatchActor {
  def props(replyActor: ActorRef,
            passengerInfoRoutingRef: ActorRef,
            flights: Seq[VoyagePassengerInfo],
            description: String
           ) = Props(classOf[PassengerInfoBatchActor], replyActor, passengerInfoRoutingRef, flights, description)
}

case class PassengerInfoBatchActor(replyActor: ActorRef,
                                   passengerInfoRoutingRef: ActorRef,
                                   flights: Seq[VoyagePassengerInfo],
                                   description: String) extends Actor with ActorLogging {
  var received = 0

  def receive = {
    case "Begin" =>
      log.info(s"Dates to process ${description}")
      flights foreach {
        (flight) => passengerInfoRoutingRef ! flight
      }
    case PassengerSplitsAck =>
      if (received % 1000 == 0) log.info(s"${description} processed flight info ${received}")
      received += 1
      if (received == flights.length) {
        log.info(s"${description} Batch complete")
        if (replyActor != Actor.noSender)
          replyActor ! PassengerInfoBatchComplete
        self ! PoisonPill
      }
  }
}

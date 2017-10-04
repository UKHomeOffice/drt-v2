package services.graphstages

import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.stream.{Attributes, FanInShape2, Inlet, Outlet}
import drt.shared.FlightsApi.Flights
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.immutable.Map
import scala.language.postfixOps

class ArrivalsGraphStage()
  extends GraphStage[FanInShape2[Flights, Flights, Flights]] {

  val inBase: Inlet[Flights] = Inlet[Flights]("inFlightsBase.in")
  val inLive: Inlet[Flights] = Inlet[Flights]("inFlightsLive.in")
  val outMerged: Outlet[Flights] = Outlet[Flights]("outFlights.in")
  override val shape = new FanInShape2(inBase, inLive, outMerged)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var baseArrivals: Set[Arrival] = Set()
    var liveArrivals: Set[Arrival] = Set()
    var merged: Map[Int, Arrival] = Map()
    var toPush: Option[Flights] = None

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inBase, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inBase onPush() grabbing base flights")
        val incomingBase = grab(inBase).flights.toSet
        toPush = mergeArrivals(incomingBase, liveArrivals)
      }
    })

    setHandler(inLive, new InHandler {
      override def onPush(): Unit = {
        log.info(s"inLive onPush() grabbing live flights")
        val incomingLive = grab(inLive).flights.toSet
        toPush = mergeArrivals(baseArrivals, incomingLive)
      }
    })

    setHandler(outMerged, new OutHandler {
      override def onPull(): Unit = {
        toPush match {
          case None =>
            log.info(s"No updated arrivals to push")
          case Some(flights) =>
            log.info(s"Pushing ${flights.flights.length} flights")
            push(outMerged, flights)
            toPush = None
        }

        if (!hasBeenPulled(inLive)) pull(inLive)
        if (!hasBeenPulled(inBase)) pull(inBase)
      }
    })

    def mergeArrivals(base: Set[Arrival], live: Set[Arrival]): Option[Flights] = {
      val baseById = base.map(a => (a.uniqueId, a)).toMap
      val updatedMerged = live.foldLeft(baseById) {
        case (mergedSoFar, liveArrival) =>
          val baseArrival = mergedSoFar.getOrElse(liveArrival.uniqueId, liveArrival)
          val mergedArrival = liveArrival.copy(rawIATA = baseArrival.rawIATA, rawICAO = baseArrival.rawICAO)
          mergedSoFar.updated(liveArrival.uniqueId, mergedArrival)
      }
      updatedMerged.values.toSet -- merged.values.toSet match {
        case updatedArrivals if updatedArrivals.isEmpty =>
          log.info(s"No updated arrivals")
          None
        case updatedArrivals =>
          log.info(s"${updatedArrivals.size} updated arrivals")
          Option(Flights(updatedArrivals.toList))
      }
    }
  }
}

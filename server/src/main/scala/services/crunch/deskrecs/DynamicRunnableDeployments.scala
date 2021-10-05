package services.crunch.deskrecs

import akka.NotUsed
import akka.stream.scaladsl.Flow
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.TimeLogger
import services.crunch.desklimits.PortDeskLimits.StaffToDeskLimits
import services.crunch.deskrecs.DynamicRunnableDeskRecs.LoadsToQueueMinutes
import services.crunch.deskrecs.RunnableOptimisation.CrunchRequest
import services.graphstages.Crunch.LoadMinute

import scala.collection.immutable.Map
import scala.concurrent.{ExecutionContext, Future}


object DynamicRunnableDeployments {
  val log: Logger = LoggerFactory.getLogger(getClass)
  val timeLogger: TimeLogger = TimeLogger("Deployment", 1000, log)

  def crunchRequestsToDeployments(loadsProvider: CrunchRequest => Future[Map[TQM, LoadMinute]],
                                  staffProvider: CrunchRequest => Future[Map[Terminal, List[Int]]],
                                  staffToDeskLimits: StaffToDeskLimits,
                                  loadsToQueueMinutes: LoadsToQueueMinutes)
                                 (implicit executionContext: ExecutionContext): Flow[CrunchRequest, PortStateQueueMinutes, NotUsed] = {
    Flow[CrunchRequest]
      .mapAsync(1) { request =>
        loadsProvider(request).map { minutes => (request, minutes) }
      }
      .mapAsync(1) { case (request, loads) =>
        staffProvider(request).map(staff => (request, loads, staffToDeskLimits(staff)))
      }
      .map {
        case (request, loads, deskLimitsByTerminal) =>
          log.info(s"Simulating ${request.durationMinutes} minutes (${request.start.toISOString()} to ${request.end.toISOString()})")
          timeLogger.time(loadsToQueueMinutes(request.minutesInMillis, loads, deskLimitsByTerminal))
      }
  }
}

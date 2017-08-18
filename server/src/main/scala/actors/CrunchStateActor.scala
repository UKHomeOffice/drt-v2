package actors

import akka.actor._
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotSuccess, SnapshotOffer}
import controllers.{FlightState, GetTerminalCrunch}
import drt.shared
import drt.shared.FlightsApi._
import drt.shared.{Arrival, _}
import org.joda.time.{DateTime, DateTimeZone}
import org.joda.time.format.DateTimeFormat
import server.protobuf.messages.CrunchState._
import services.Crunch.CrunchState
import services._
import services.workloadcalculator.{PaxLoadCalculator, WorkloadCalculator}
import spray.caching.{Cache, LruCache}

import scala.collection.immutable
import scala.collection.immutable.{NumericRange, Seq}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


class CrunchStateActor extends PersistentActor with ActorLogging {
  var state: Option[CrunchState] = None

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved CrunchState Snapshot")
    case cs@CrunchState(_, _, _, _) =>
      log.info(s"received CrunchState")
      state = Option(cs)
      persist(cs) { (crunchState: CrunchState) =>

        val cm = CrunchStateSnapshotMessage(
          crunchState.flights.map(f => {
            FlightWithSplitsMessage(
              Option(FlightMessageConversion.apiFlightToFlightMessage(f.apiFlight)),
              f.splits.map(s => {
                SplitMessage(s.splits.map(ptqc => {
                  PaxTypeAndQueueCountMessage(
                    Option(ptqc.passengerType.name),
                    Option(ptqc.queueType),
                    Option(ptqc.paxCount)
                  )
                }))
              }))}
            ),
          crunchState.workloads.map {
            case (terminalName, queueLoads) =>
              TerminalLoadMessage(Option(terminalName), queueLoads.map{
                case (queueName, loads) =>
                  QueueLoadMessage(Option(queueName), loads.map{
                    case (timestamp, (pax, work)) =>
                      LoadMessage(Option(timestamp), Option(pax), Option(work))
                  })
              }.toList)
          }.toList,
          crunchState.crunchResult.map {
            case (terminalName, queueCrunch) =>
              TerminalCrunchMessage(Option(terminalName), queueCrunch.collect{
                case (queueName, Success(cr)) =>
                  QueueCrunchMessage(Option(queueName), Option(CrunchMessage(cr.recommendedDesks, cr.waitTimes)))
              }.toList)
          }.toList,
          Option(crunchState.crunchFirstMinuteMillis)
        )
        context.system.eventStream.publish(cm)
      }
      state.foreach(s => {
        log.info(s"Saving Snapshot $s")
        saveSnapshot(s)
      })
    case GetFlights =>
      state match {
        case Some(CrunchState(flights, _, _, _)) =>
          sender() ! FlightsWithSplits(flights)
        case None => FlightsNotReady
      }
    case GetPortWorkload =>
      state match {
        case Some(CrunchState(_, workloads, _, _)) =>
          val values = workloads.mapValues(_.mapValues(wl =>
            (wl.map(wlm => WL(wlm._1, wlm._2._2)), wl.map(wlm => Pax(wlm._1, wlm._2._1)))))
          sender() ! values
        case None => WorkloadsNotReady
      }
    case GetTerminalCrunch(terminalName) =>
      val terminalCrunchResults: List[(QueueName, Either[NoCrunchAvailable, CrunchResult])] = state match {
        case Some(CrunchState(_, _, portCrunchResult, crunchFirstMinuteMillis)) =>
          portCrunchResult.getOrElse(terminalName, Map()).map {
            case (queueName, optimiserCRTry) =>
              optimiserCRTry match {
                case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                  (queueName, Right(CrunchResult(crunchFirstMinuteMillis, 60000, deskRecs, waitTimes)))
                case _ =>
                  (queueName, Left(NoCrunchAvailable()))
              }
          }.toList
        case None => List[(QueueName, Either[NoCrunchAvailable, CrunchResult])]()
      }
      sender() ! terminalCrunchResults
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(m, s) =>
      log.info(s"restoring crunch state")
      s match {
        case c@CrunchState(_,_,_,_) =>
          log.info("matched crunch state, storing it.")
          state = Option(c)
        case somethingElse =>
          log.info(s"Got $somethingElse when trying to restore Crunch State")
      }
    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")
  }

  override def persistenceId: String = "crunch-state"
}

//i'm of two minds about the benefit of having this message independent of the Flights() message.
case class PerformCrunchOnFlights(flights: Seq[Arrival])

case class GetLatestCrunch(terminalName: TerminalName, queueName: QueueName)

case class SaveTerminalCrunchResult(terminalName: TerminalName, terminalCrunchResult: Map[TerminalName, CrunchResult])

trait EGateBankCrunchTransformations {

  def groupEGatesIntoBanksWithSla(desksInBank: Int, sla: Int)(crunchResult: OptimizerCrunchResult, workloads: Seq[Double]): OptimizerCrunchResult = {
    val recommendedDesks = crunchResult.recommendedDesks.map(roundUpToNearestMultipleOf(desksInBank))
    val optimizerConfig = OptimizerConfig(sla)
    val simulationResult = runSimulation(workloads, recommendedDesks, optimizerConfig)

    crunchResult.copy(
      recommendedDesks = recommendedDesks.map(recommendedDesk => recommendedDesk / desksInBank),
      waitTimes = simulationResult.waitTimes
    )
  }

  protected[actors] def runSimulation(workloads: Seq[Double], recommendedDesks: immutable.IndexedSeq[Int], optimizerConfig: OptimizerConfig) = {
    TryRenjin.runSimulationOfWork(workloads, recommendedDesks, optimizerConfig)
  }

  def roundUpToNearestMultipleOf(multiple: Int)(number: Int) = math.ceil(number.toDouble / multiple).toInt * multiple
}

object EGateBankCrunchTransformations extends EGateBankCrunchTransformations

case object GetPortWorkload

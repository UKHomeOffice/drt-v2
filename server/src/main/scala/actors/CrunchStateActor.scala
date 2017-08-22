package actors

import akka.actor._
import akka.persistence.{PersistentActor, RecoveryCompleted, SaveSnapshotSuccess, SnapshotOffer}
import controllers.GetTerminalCrunch
import drt.shared.FlightsApi._
import drt.shared.{Arrival, _}
import server.protobuf.messages.CrunchState._
import services.Crunch._
import services._
import services.workloadcalculator.PaxLoadCalculator.MillisSinceEpoch

import scala.collection.immutable._
import scala.language.postfixOps
import scala.util.{Success, Try}


class CrunchStateActor(portQueues: Map[TerminalName, Seq[QueueName]]) extends PersistentActor with ActorLogging {
  override def persistenceId: String = "crunch-state"

  var state: Option[CrunchState] = None

  val snapshotInterval = 1

  def emptyWorkloads(firstMinuteMillis: MillisSinceEpoch): Map[TerminalName, Map[QueueName, List[(Long, (Double, Double))]]] = {
    portQueues.map {
      case (tn, q) =>
        val tl = q.filterNot(_ == Queues.Transfer).map(queueName => {
          val ql = oneDayOfMinutes.map(minute => {
            (firstMinuteMillis + (minute * oneMinute), (0d, 0d))
          }).toList

          (queueName, ql)
        }).toMap

        (tn, tl)
    }
  }

  def oneDayOfMinutes = {
    0 until 1440
  }

  def emptyCrunch(crunchStartMillis: MillisSinceEpoch) = portQueues
    .mapValues(_.filterNot(_ == Queues.Transfer)
      .map(queueName => {
        val zeros = oneDayOfMinutes.map(_ => 0).toList
        (queueName, Success(OptimizerCrunchResult(zeros.toIndexedSeq, zeros)))
      }).toMap)

  def emptyState(crunchStartMillis: MillisSinceEpoch) = {
    CrunchState(
      crunchFirstMinuteMillis = crunchStartMillis,
      flights = List(),
      workloads = emptyWorkloads(crunchStartMillis),
      crunchResult = emptyCrunch(crunchStartMillis)
    )
  }

  override def receiveCommand: Receive = {
    case SaveSnapshotSuccess =>
      log.info("Saved CrunchState Snapshot")
    case cs@CrunchState(_, _, _, _) =>
      log.info(s"received CrunchState")
      state = Option(cs)
      //      persist(cs) { (crunchState: CrunchState) =>
      //        val cm = stateToSnapshotMessage(crunchState)
      //        context.system.eventStream.publish(cm)
      //      }
      state.foreach(s => {
        log.info(s"Saving CrunchState as CrunchStateSnapshotMessage")
        saveSnapshot(stateToSnapshotMessage(s))
      })
    case csd@CrunchStateDiff(start, fd, qd, cd) =>
      log.info(s"received CrunchStateDiff - $start, ${fd.size} flights, ${qd.size} queue minutes, ${cd.size} crunch minutes")
      updateStateFromDiff(csd)

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

  def updateStateFromDiff(csd: CrunchStateDiff) = {
    val currentState = state match {
      case Some(s) => s
      case None => emptyState(csd.crunchFirstMinuteMillis)
    }

    state = Option(currentState.copy(
      flights = updatedFlightState(csd.flightDiffs, currentState),
      workloads = updatedLoadState(csd.queueDiffs, currentState),
      crunchResult = updatedCrunchState(csd.crunchFirstMinuteMillis, csd.crunchDiffs, currentState)
    ))
  }

  private def updatedCrunchState(crunchStartMillis: Long, crunches: Set[CrunchDiff], currentState: CrunchState) = {
    val crunchByTQM: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)] = currentState.crunchResult.flatMap {
      case (tn, tc) =>
        tc.flatMap {
          case (qn, qc) =>
            qc match {
              case Success(c) =>
                c.recommendedDesks.indices.map(m => ((tn, qn, crunchStartMillis + (m * oneMinute)), (c.recommendedDesks(m), c.waitTimes(m))))
            }
        }
    }
    val newCrunch: Map[(TerminalName, QueueName, MillisSinceEpoch), (Int, Int)] = crunches.foldLeft(crunchByTQM) {
      case (crunchesSoFar, CrunchDiff(tn, qn, m, dr, wt)) =>
        val currentCr = crunchesSoFar.getOrElse((tn, qn, m), (0, 0))
        crunchesSoFar.updated((tn, qn, m), (currentCr._1 + dr, currentCr._2 + wt))
    }
    val updatedCrunch: Map[TerminalName, Map[QueueName, Try[OptimizerCrunchResult]]] = newCrunch
      .groupBy { case ((tn, qn, m), l) => tn }
      .map {
        case ((tn, tl)) =>
          val terminalLoads = tl.groupBy {
            case ((_, qn, _), _) => qn
          }.map {
            case (qn, ql) =>
              val deskRecs: IndexedSeq[Int] = ql.map {
                case ((_, _, millis), (dr, _)) => (millis, dr)
              }.toList.sortBy(_._1).map(_._2).toIndexedSeq

              val waitTimes: Seq[Int] = ql.toSeq.map {
                case ((_, _, millis), (_, wt)) => (millis, wt)
              }.toList.sortBy(_._1).map(_._2)

              (qn, Success(OptimizerCrunchResult(deskRecs, waitTimes)))
          }
          (tn, terminalLoads)
      }
    updatedCrunch
  }

  private def updatedLoadState(queueLoads: Set[QueueLoadDiff], currentState: CrunchState) = {
    val loadByTQM = currentState.workloads.flatMap {
      case (tn, tlw) => tlw.flatMap {
        case (qn, qlw) => qlw.map {
          case (millis, (pl, wl)) => ((tn, qn, millis), (pl, wl))
        }
      }
    }
    val newLoads = queueLoads.foldLeft(loadByTQM) {
      case (loadsSoFar, QueueLoadDiff(tn, qn, m, pl, wl)) =>
        val currentLoads = loadsSoFar.getOrElse((tn, qn, m), (0d, 0d))
        loadsSoFar.updated((tn, qn, m), (currentLoads._1 + pl, currentLoads._2 + wl))
    }
    val updatedLoads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = newLoads
      .groupBy { case ((tn, qn, m), l) => tn }
      .map {
        case ((tn, tl)) =>
          val terminalLoads = tl.groupBy {
            case ((_, qn, _), _) => qn
          }.map {
            case (qn, ql) =>
              val queueLoads = ql.map {
                case ((tn, qn, m), (pl, wl)) => (m, (pl, wl))
              }.toList
              (qn, queueLoads)
          }
          (tn, terminalLoads)
      }
    updatedLoads
  }

  private def updatedFlightState(flights: Set[ApiFlightWithSplits], currentState: CrunchState) = {
    val newFlights = currentState.flights.map(f => (f.apiFlight.FlightID, f)).toMap
    val updatedFlights = flights.foldLeft(newFlights) {
      case (flightsSoFar, newFlight) => flightsSoFar.updated(newFlight.apiFlight.FlightID, newFlight)
    }.values.toList
    updatedFlights
  }

  def snapshotMessageToState(snapshot: CrunchStateSnapshotMessage) = {
    CrunchState(
      snapshot.flightWithSplits.map(fm => {
        ApiFlightWithSplits(
          FlightMessageConversion.flightMessageV2ToArrival(fm.flight.get),
          fm.splits.map(sm => {
            ApiSplits(
              sm.paxTypeAndQueueCount.map(pqcm => {
                ApiPaxTypeAndQueueCount(PaxType(pqcm.paxType.get), pqcm.queueType.get, pqcm.paxValue.get)
              }).toList, sm.source.get, SplitStyle(sm.style.get))
          }).toList
        )
      }).toList,
      snapshot.terminalLoad.map(tlm => {
        (tlm.terminalName.get, tlm.queueLoad.map(qlm => {
          (qlm.queueName.get, qlm.load.map(lm => {
            (lm.timestamp.get, (lm.pax.get, lm.work.get))
          }).toList)
        }).toMap)
      }).toMap,
      snapshot.terminalCrunch.map(tcm => {
        (tcm.terminalName.get, tcm.queueCrunch.map(qcm => {
          val cm = qcm.crunch.get
          (qcm.queueName.get, Success(OptimizerCrunchResult(cm.desks.toIndexedSeq, cm.waitTimes.toList)))
        }).toMap)
      }).toMap,
      snapshot.crunchFirstMinuteTimestamp.get
    )
  }

  def stateToSnapshotMessage(crunchState: CrunchState): CrunchStateSnapshotMessage = {
    CrunchStateSnapshotMessage(
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
            }),
              Option(s.source),
              Option(s.splitStyle.name)
            )
          }))
      }
      ),
      crunchState.workloads.map {
        case (terminalName, queueLoads) =>
          TerminalLoadMessage(Option(terminalName), queueLoads.map {
            case (queueName, loads) =>
              QueueLoadMessage(Option(queueName), loads.map {
                case (timestamp, (pax, work)) =>
                  LoadMessage(Option(timestamp), Option(pax), Option(work))
              })
          }.toList)
      }.toList,
      crunchState.crunchResult.map {
        case (terminalName, queueCrunch) =>
          TerminalCrunchMessage(Option(terminalName), queueCrunch.collect {
            case (queueName, Success(cr)) =>
              QueueCrunchMessage(Option(queueName), Option(CrunchMessage(cr.recommendedDesks, cr.waitTimes)))
          }.toList)
      }.toList,
      Option(crunchState.crunchFirstMinuteMillis)
    )
  }

  override def receiveRecover: Receive = {
    case SnapshotOffer(m, s) =>
      log.info(s"restoring crunch state")
      s match {
        case sm@CrunchStateSnapshotMessage(_, _, _, _) =>
          log.info("matched CrunchStateSnapshotMessage, storing it.")
          state = Option(snapshotMessageToState(sm))
        case somethingElse =>
          log.info(s"Got $somethingElse when trying to restore Crunch State")
      }
    case RecoveryCompleted =>
      log.info("Finished restoring crunch state")
  }
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

  protected[actors] def runSimulation(workloads: Seq[Double], recommendedDesks: IndexedSeq[Int], optimizerConfig: OptimizerConfig) = {
    TryRenjin.runSimulationOfWork(workloads, recommendedDesks, optimizerConfig)
  }

  def roundUpToNearestMultipleOf(multiple: Int)(number: Int) = math.ceil(number.toDouble / multiple).toInt * multiple
}

object EGateBankCrunchTransformations extends EGateBankCrunchTransformations

case object GetPortWorkload

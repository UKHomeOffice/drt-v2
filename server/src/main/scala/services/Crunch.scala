package services

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.util.zip.ZipInputStream

import akka.NotUsed
import akka.actor.{ActorLogging, ActorRef, ActorSystem, Cancellable}
import akka.pattern.AskableActorRef
import akka.persistence.{RecoveryCompleted, _}
import akka.stream._
import akka.stream.scaladsl.{GraphDSL, RunnableGraph, Sink, Source, StreamConverters}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import akka.util.{ByteString, Timeout}
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.profile.ProfileCredentialsProvider
import com.amazonaws.services.s3.S3ClientOptions
import com.mfglabs.commons.aws.s3.{AmazonS3AsyncClient, S3StreamBuilder}
import controllers.SystemActors.SplitsProvider
import drt.shared.FlightsApi.{Flights, QueueName, TerminalName}
import drt.shared.PassengerQueueTypes.PaxTypeAndQueueCounts
import drt.shared.SplitRatiosNs.{SplitRatio, SplitRatios, SplitSources}
import drt.shared._
import org.joda.time.{DateTime, DateTimeZone}
import org.slf4j.LoggerFactory
import passengersplits.core.PassengerQueueCalculator
import passengersplits.parsing.VoyageManifestParser
import passengersplits.parsing.VoyageManifestParser.VoyageManifest
import server.protobuf.messages.CrunchState.CrunchStateSnapshotMessage
import services.Crunch.{log => _, _}
import services.workloadcalculator.PaxLoadCalculator._

import scala.collection.immutable.{Map, Seq}
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.Success

object Crunch {
  val log = LoggerFactory.getLogger(getClass)

  case class FlightSplitMinute(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class FlightSplitDiff(flightId: Int, paxType: PaxType, terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class QueueLoadMinute(terminalName: TerminalName, queueName: QueueName, paxLoad: Double, workLoad: Double, minute: MillisSinceEpoch)

  case class CrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch, paxLoad: Double, workLoad: Double, deskRec: Int, waitTime: Int)

  case class RemoveCrunchMinute(terminalName: TerminalName, queueName: QueueName, minute: MillisSinceEpoch)

  case class RemoveFlight(flightId: Int)

  case class CrunchDiff(flightRemovals: Set[RemoveFlight], flightUpdates: Set[ApiFlightWithSplits], crunchMinuteRemovals: Set[RemoveCrunchMinute], crunchMinuteUpdates: Set[CrunchMinute])

  case class CrunchState(
                          crunchFirstMinuteMillis: MillisSinceEpoch,
                          numberOfMinutes: Int,
                          flights: Set[ApiFlightWithSplits],
                          crunchMinutes: Set[CrunchMinute])

  case class CrunchRequest(flights: List[ApiFlightWithSplits], crunchStart: MillisSinceEpoch, numberOfMinutes: Int)

  val oneMinute = 60000

  class CrunchFlow(slas: Map[QueueName, Int],
                   minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                   procTimes: Map[PaxTypeAndQueue, Double],
                   groupFlightsByCodeShares: (Seq[ApiFlightWithSplits]) => List[(ApiFlightWithSplits, Set[Arrival])],
                   validPortTerminals: Set[String])
    extends GraphStage[FlowShape[CrunchRequest, CrunchState]] {

    val in = Inlet[CrunchRequest]("CrunchState.in")
    val out = Outlet[CrunchState]("CrunchState.out")
    override val shape = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
      var flightsByFlightId: Map[Int, ApiFlightWithSplits] = Map()
      var flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]] = Map()
      var crunchMinutes: Set[CrunchMinute] = Set()

      var crunchStateOption: Option[CrunchState] = None
      var crunchRunning = false

      setHandlers(in, out, new InHandler with OutHandler {
        override def onPull(): Unit = {
          log.info(s"onPull called")
          crunchStateOption match {
            case Some(crunchState) =>
              push(out, crunchState)
              crunchStateOption = None
            case None =>
              log.info(s"No CrunchState to push")
          }
          if (!hasBeenPulled(in)) pull(in)
        }

        override def onPush(): Unit = {
          log.info(s"onPush called")
          if (!crunchRunning) grabAndCrunch()

          pushStateIfReady()

          if (!hasBeenPulled(in)) pull(in)
        }

        def grabAndCrunch() = {
          crunchRunning = true

          val crunchRequest: CrunchRequest = grab(in)

          log.info(s"processing crunchRequest - ${crunchRequest.flights.length} flights")
          processFlights(crunchRequest)

          crunchRunning = false
        }

        def processFlights(crunchFlights: CrunchRequest) = {
          val newCrunchStateOption = crunch(crunchFlights)

          log.info(s"setting crunchStateOption")
          crunchStateOption = newCrunchStateOption
        }

        def crunch(crunchRequest: CrunchRequest) = {
          val relevantFlights = crunchRequest.flights.filter {
            case ApiFlightWithSplits(flight, _) =>
              validPortTerminals.contains(flight.Terminal) && (flight.PcpTime + 30) > crunchRequest.crunchStart
          }
          val uniqueFlights = groupFlightsByCodeShares(relevantFlights).map(_._1)
          log.info(s"found ${uniqueFlights.length} flights to crunch")
          val newFlightsById = uniqueFlights.map(f => (f.apiFlight.FlightID, f)).toMap
          val newFlightSplitMinutesByFlight = flightsToFlightSplitMinutes(procTimes)(uniqueFlights)

          val crunchStart = crunchRequest.crunchStart
          val numberOfMinutes = crunchRequest.numberOfMinutes
          val crunchEnd = crunchStart + (numberOfMinutes * oneMinute)
          val flightSplitDiffs: Set[FlightSplitDiff] = flightsToSplitDiffs(flightSplitMinutesByFlight, newFlightSplitMinutesByFlight)
            .filter {
              case FlightSplitDiff(_, _, _, _, _, _, minute) =>
                crunchStart <= minute && minute < crunchEnd
            }

          val crunchState = flightSplitDiffs match {
            case fsd if fsd.isEmpty => None
            case _ =>
              val newCrunchState = crunchStateFromFlightSplitMinutes(crunchStart, numberOfMinutes, newFlightsById, newFlightSplitMinutesByFlight)
              Option(newCrunchState)
          }

          flightsByFlightId = newFlightsById
          flightSplitMinutesByFlight = newFlightSplitMinutesByFlight

          crunchState
        }
      })

      def pushStateIfReady() = {
        crunchStateOption match {
          case None =>
            log.info(s"We have no state yet. Nothing to push")
          case Some(crunchState) =>
            if (isAvailable(out)) {
              log.info(s"pushing csd ${crunchState.crunchFirstMinuteMillis}")
              push(out, crunchState)
              crunchStateOption = None
            }
        }
      }

      def crunchStateFromFlightSplitMinutes(crunchStart: MillisSinceEpoch,
                                            numberOfMinutes: Int,
                                            flightsById: Map[Int, ApiFlightWithSplits],
                                            fsmsByFlightId: Map[Int, Set[FlightSplitMinute]]) = {
        val crunchResults: Set[CrunchMinute] = crunchFlightSplitMinutes(crunchStart, numberOfMinutes, fsmsByFlightId)

        CrunchState(crunchStart, numberOfMinutes, flightsById.values.toSet, crunchResults)
      }

      def crunchFlightSplitMinutes(crunchStart: MillisSinceEpoch, numberOfMinutes: Int, flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]) = {
        val qlm: Set[QueueLoadMinute] = flightSplitMinutesToQueueLoadMinutes(flightSplitMinutesByFlight)
        val wlByQueue: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Load, Load)]]] = indexQueueWorkloadsByMinute(qlm)

        val fullWlByQueue: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]] = queueMinutesForPeriod(crunchStart, numberOfMinutes)(wlByQueue)
        val eGateBankSize = 5

        val crunchResults: Set[CrunchMinute] = workloadsToCrunchMinutes(crunchStart, numberOfMinutes, fullWlByQueue, slas, minMaxDesks, eGateBankSize)
        crunchResults
      }

    }
  }

  def flightsToSplitDiffs(flightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]], newFlightSplitMinutesByFlight: Map[Int, Set[FlightSplitMinute]]): Set[FlightSplitDiff] = {
    val allKnownFlightIds = newFlightSplitMinutesByFlight.keys.toSet.union(flightSplitMinutesByFlight.keys.toSet)
    val flightSplitDiffs: Set[FlightSplitDiff] = allKnownFlightIds.flatMap(id => {
      val existingSplits = flightSplitMinutesByFlight.getOrElse(id, Set())
      val newSplits = newFlightSplitMinutesByFlight.getOrElse(id, Set())
      flightLoadDiff(existingSplits, newSplits)
    })
    flightSplitDiffs
  }

  def workloadsToCrunchMinutes(crunchStartMillis: MillisSinceEpoch,
                               numberOfMinutes: Int,
                               portWorkloads: Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Load, Load))]]],
                               slas: Map[QueueName, Int],
                               minMaxDesks: Map[TerminalName, Map[QueueName, (List[Int], List[Int])]],
                               eGateBankSize: Int): Set[CrunchMinute] = {
    val crunchEnd = crunchStartMillis + (numberOfMinutes * oneMinute)

    portWorkloads.flatMap {
      case (tn, terminalWorkloads) =>
        val terminalCrunchMinutes = terminalWorkloads.flatMap {
          case (qn, queueWorkloads) =>
            val workloadMinutes = qn match {
              case Queues.EGate => queueWorkloads.map(_._2._2 / eGateBankSize)
              case _ => queueWorkloads.map(_._2._2)
            }
            val loadByMillis = queueWorkloads.toMap
            val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
            val sla = slas.getOrElse(qn, 0)
            val queueMinMaxDesks = minMaxDesks.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
            val crunchMinutes = crunchStartMillis until crunchEnd by oneMinute
            val minDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
            val maxDesks = crunchMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
            val triedResult = TryRenjin.crunch(workloadMinutes, minDesks, maxDesks, OptimizerConfig(sla))

            val queueCrunchMinutes = triedResult match {
              case Success(OptimizerCrunchResult(deskRecs, waitTimes)) =>
                val crunchMinutes = (0 until numberOfMinutes).map(minuteIdx => {
                  val minuteMillis = crunchStartMillis + (minuteIdx * oneMinute)
                  val paxLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._1
                  val workLoad = loadByMillis.getOrElse(minuteMillis, (0d, 0d))._2
                  CrunchMinute(tn, qn, minuteMillis, paxLoad, workLoad, deskRecs(minuteIdx), waitTimes(minuteIdx))
                }).toSet
                crunchMinutes
              case _ =>
                Set[CrunchMinute]()
            }

            queueCrunchMinutes
        }
        terminalCrunchMinutes
    }.toSet
  }

  def desksForHourOfDayInUKLocalTime(startTimeMidnightBST: MillisSinceEpoch, desks: Seq[Int]) = {
    val date = new DateTime(startTimeMidnightBST).withZone(DateTimeZone.forID("Europe/London"))
    desks(date.getHourOfDay)
  }

  def queueMinutesForPeriod(startTime: Long, numberOfMinutes: Int)
                           (terminal: Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]]): Map[TerminalName, Map[QueueName, List[(MillisSinceEpoch, (Double, Double))]]] = {
    val endTime = startTime + numberOfMinutes * oneMinute

    terminal.mapValues(queue => {
      queue.mapValues(queueWorkloadMinutes => {
        (startTime until endTime by oneMinute).map(minuteMillis =>
          (minuteMillis, queueWorkloadMinutes.getOrElse(minuteMillis, (0d, 0d)))).toList
      })
    })
  }

  def indexQueueWorkloadsByMinute(queueWorkloadMinutes: Set[QueueLoadMinute]): Map[TerminalName, Map[QueueName, Map[MillisSinceEpoch, (Double, Double)]]] = {
    val portLoads = queueWorkloadMinutes.groupBy(_.terminalName)

    portLoads.mapValues(terminalLoads => {
      val queueLoads = terminalLoads.groupBy(_.queueName)
      queueLoads
        .mapValues(_.map(qwl =>
          qwl.minute -> (qwl.paxLoad, qwl.workLoad)
        ).toMap)
    })
  }

  def flightsToFlightSplitMinutes(procTimes: Map[PaxTypeAndQueue, Double])(flightsWithSplits: List[ApiFlightWithSplits]): Map[Int, Set[FlightSplitMinute]] = {
    flightsWithSplits.map {
      case ApiFlightWithSplits(flight, splits) => (flight.FlightID, flightToFlightSplitMinutes(flight, splits, procTimes))
    }.toMap
  }

  def flightLoadDiff(oldSet: Set[FlightSplitMinute], newSet: Set[FlightSplitMinute]) = {
    val toRemove = oldSet.map(fsm => FlightSplitMinute(fsm.flightId, fsm.paxType, fsm.terminalName, fsm.queueName, -fsm.paxLoad, -fsm.workLoad, fsm.minute))
    val addAndRemoveGrouped: Map[(Int, TerminalName, QueueName, MillisSinceEpoch, PaxType), Set[FlightSplitMinute]] = newSet
      .union(toRemove)
      .groupBy(fsm => (fsm.flightId, fsm.terminalName, fsm.queueName, fsm.minute, fsm.paxType))

    addAndRemoveGrouped
      .map {
        case ((fid, tn, qn, m, pt), fsm) => FlightSplitDiff(fid, pt, tn, qn, fsm.map(_.paxLoad).sum, fsm.map(_.workLoad).sum, m)
      }
      .filterNot(fsd => fsd.paxLoad == 0 && fsd.workLoad == 0)
      .toSet
  }

  def collapseQueueLoadMinutesToSet(queueLoadMinutes: List[QueueLoadMinute]) = {
    queueLoadMinutes
      .groupBy(qlm => (qlm.terminalName, qlm.queueName, qlm.minute))
      .map {
        case ((t, q, m), qlm) =>
          val summedPaxLoad = qlm.map(_.paxLoad).sum
          val summedWorkLoad = qlm.map(_.workLoad).sum
          QueueLoadMinute(t, q, summedPaxLoad, summedWorkLoad, m)
      }.toSet
  }

  def flightToFlightSplitMinutes(flight: Arrival,
                                 splits: List[ApiSplits],
                                 procTimes: Map[PaxTypeAndQueue, Double]): Set[FlightSplitMinute] = {
    val apiSplits = splits.find(_.source == SplitSources.ApiSplitsWithCsvPercentage)
    val historicalSplits = splits.find(_.source == SplitSources.Historical)
    val terminalSplits = splits.find(_.source == SplitSources.TerminalAverage)

    val splitsToUseOption = apiSplits match {
      case s@Some(_) => s
      case None => historicalSplits match {
        case s@Some(_) => s
        case None => terminalSplits match {
          case s@Some(_) => s
          case n@None =>
            log.error(s"Couldn't find terminal splits from AirportConfig to fall back on...")
            None
        }
      }
    }

    splitsToUseOption.map(splitsToUse => {
      val totalPax = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.paxCount).sum
        case Percentage => BestPax()(flight)
      }
      val splitRatios: Seq[ApiPaxTypeAndQueueCount] = splitsToUse.splitStyle match {
        case PaxNumbers => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / totalPax))
        case Percentage => splitsToUse.splits.map(qc => qc.copy(paxCount = qc.paxCount / 100))
      }

      minutesForHours(flight.PcpTime, 1)
        .zip(paxDeparturesPerMinutes(totalPax.toInt, paxOffFlowRate))
        .flatMap {
          case (minuteMillis, flightPaxInMinute) =>
            splitRatios
              .filterNot(_.queueType == Queues.Transfer)
              .map(apiSplit => flightSplitMinute(flight, procTimes, minuteMillis, flightPaxInMinute, apiSplit, splitsToUse.splitStyle))
        }.toSet
    }).getOrElse(Set())
  }

  def flightSplitMinute(flight: Arrival,
                        procTimes: Map[PaxTypeAndQueue, Load],
                        minuteMillis: MillisSinceEpoch,
                        flightPaxInMinute: Int,
                        apiSplitRatio: ApiPaxTypeAndQueueCount,
                        splitStyle: SplitStyle): FlightSplitMinute = {
    val splitPaxInMinute = apiSplitRatio.paxCount * flightPaxInMinute
    val splitWorkLoadInMinute = splitPaxInMinute * procTimes(PaxTypeAndQueue(apiSplitRatio.passengerType, apiSplitRatio.queueType))
    FlightSplitMinute(flight.FlightID, apiSplitRatio.passengerType, flight.Terminal, apiSplitRatio.queueType, splitPaxInMinute, splitWorkLoadInMinute, minuteMillis)
  }

  def flightSplitMinutesToQueueLoadMinutes(flightToFlightSplitMinutes: Map[Int, Set[FlightSplitMinute]]): Set[QueueLoadMinute] = {
    flightToFlightSplitMinutes
      .values
      .flatten
      .groupBy(s => (s.terminalName, s.queueName, s.minute)).map {
      case ((terminalName, queueName, minute), fsms) =>
        val paxLoad = fsms.map(_.paxLoad).sum
        val workLoad = fsms.map(_.workLoad).sum
        QueueLoadMinute(terminalName, queueName, paxLoad, workLoad, minute)
    }.toSet
  }

  def getLocalLastMidnight(now: SDateLike) = {
    val localMidnight = s"${now.getFullYear}-${now.getMonth}-${now.getDate}T00:00"
    SDate(localMidnight, DateTimeZone.forID("Europe/London"))
  }

  def flightsDiff(oldFlights: Set[ApiFlightWithSplits], newFlights: Set[ApiFlightWithSplits]) = {
    val oldFlightsById = oldFlights.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap
    val newFlightsById = newFlights.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap
    val oldIds = oldFlightsById.keys.toSet
    val newIds = newFlightsById.keys.toSet
    val toRemove = (oldIds -- newIds).map {
      case id => RemoveFlight(id)
    }
    val toUpdate = newFlightsById.collect {
      case (id, cm) if oldFlightsById.get(id).isEmpty || cm != oldFlightsById(id) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinutesDiff(oldCm: Set[CrunchMinute], newCm: Set[CrunchMinute]) = {
    val oldTqmToCm = oldCm.map(cm => crunchMinuteToTqmCm(cm)).toMap
    val newTqmToCm = newCm.map(cm => crunchMinuteToTqmCm(cm)).toMap
    val oldKeys = oldTqmToCm.keys.toSet
    val newKeys = newTqmToCm.keys.toSet
    val toRemove = (oldKeys -- newKeys).map {
      case k@(tn, qn, m) => RemoveCrunchMinute(tn, qn, m)
    }
    val toUpdate = newTqmToCm.collect {
      case (k, cm) if oldTqmToCm.get(k).isEmpty || cm != oldTqmToCm(k) => cm
    }.toSet

    Tuple2(toRemove, toUpdate)
  }

  def crunchMinuteToTqmCm(cm: CrunchMinute) = {
    Tuple2(Tuple3(cm.terminalName, cm.queueName, cm.minute), cm)
  }

  def applyCrunchDiff(diff: CrunchDiff, cms: Set[CrunchMinute]): Set[CrunchMinute] = {
    val withoutRemovals = diff.crunchMinuteRemovals.foldLeft(cms) {
      case (soFar, removal) => soFar.filterNot {
        case CrunchMinute(tn, qn, m, _, _, _, _) => removal.terminalName == tn && removal.queueName == qn && removal.minute == m
      }
    }
    val withoutRemovalsWithUpdates = diff.crunchMinuteUpdates.foldLeft(withoutRemovals.map(crunchMinuteToTqmCm).toMap) {
      case (soFar, ncm) =>
        soFar.updated((ncm.terminalName, ncm.queueName, ncm.minute), ncm)
    }
    withoutRemovalsWithUpdates.values.toSet
  }

  def applyFlightsDiff(diff: CrunchDiff, flights: Set[ApiFlightWithSplits]): Set[ApiFlightWithSplits] = {
    val withoutRemovals = diff.flightRemovals.foldLeft(flights) {
      case (soFar, removal) => soFar.filterNot {
        case f: ApiFlightWithSplits => removal.flightId == f.apiFlight.FlightID
      }
    }
    val withoutRemovalsWithUpdates = diff.flightUpdates.foldLeft(withoutRemovals.map(f => Tuple2(f.apiFlight.FlightID, f)).toMap) {
      case (soFar, flight) =>
        soFar.updated(flight.apiFlight.FlightID, flight)
    }
    withoutRemovalsWithUpdates.values.toSet
  }
}

object RunnableCrunchGraph {

  import akka.stream.scaladsl.GraphDSL.Implicits._

  def apply(
             flightsSource: Source[Flights, Cancellable],
             voyageManifestsSource: Source[Set[VoyageManifest], NotUsed],
             flightsAndManifests: FlightsAndManifests,
             cruncher: CrunchFlow,
             crunchStateActor: ActorRef) =
    RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val crunchSink = Sink.actorRef(crunchStateActor, "completed")
      val F: Outlet[Flights] = builder.add(flightsSource).out
      val M: Outlet[Set[VoyageManifest]] = builder.add(voyageManifestsSource).out

      val FS = builder.add(flightsAndManifests)
      val CR = builder.add(cruncher)
      val G = builder.add(crunchSink).in

      F ~> FS.in0
      M ~> FS.in1
      FS.out ~> CR ~> G

      ClosedShape
    })
}

class VoyageManifestsActor extends PersistentActor with ActorLogging {
  var latestZipFilename = "drt_dq_170912"
  val snapshotInterval = 100

  override def persistenceId: String = "VoyageManifests"

  override def receiveRecover: Receive = {
    case recoveredLZF: String =>
      log.info(s"Recovery received $recoveredLZF")
      latestZipFilename = recoveredLZF
    case SnapshotOffer(md, ss) =>
      log.info(s"Recovery received SnapshotOffer($md, $ss)")
      ss match {
        case lzf: String => latestZipFilename = lzf
        case u => log.info(s"Received unexpected snapshot data: $u")
      }
  }

  override def receiveCommand: Receive = {
    case UpdateLatestZipFilename(updatedLZF) if updatedLZF != latestZipFilename=>
      log.info(s"Received update: $updatedLZF")
      latestZipFilename = updatedLZF

      if (lastSequenceNr % snapshotInterval == 0 && lastSequenceNr != 0) {
        log.info(s"Saving VoyageManifests latestZipFilename snapshot $latestZipFilename")
        saveSnapshot(latestZipFilename)
      } else persist(latestZipFilename) { lzf =>
        log.info(s"Persisting VoyageManifests latestZipFilename $latestZipFilename")
        context.system.eventStream.publish(lzf)
      }
    case GetLatestZipFilename =>
      log.info(s"Received GetLatestZipFilename request. Sending $latestZipFilename")
      sender() ! latestZipFilename

    case RecoveryCompleted =>
      log.info(s"Recovery completed")
    case SaveSnapshotSuccess(md) =>
      log.info(s"Save snapshot success: $md")
    case SaveSnapshotFailure(md, cause) =>
      log.info(s"Save snapshot failure: $md, $cause")
  }
}

class VoyageManifests(advPaxInfo: AdvPaxInfo, voyageManifestsActor: ActorRef) extends GraphStage[SourceShape[Set[VoyageManifest]]] {
  val out = Outlet[Set[VoyageManifest]]("VoyageManifests.out")
  override val shape = SourceShape(out)

  val askableVoyageManifestsActor: AskableActorRef = voyageManifestsActor

  val log = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var manifestsState: Set[VoyageManifest] = Set()
    var manifestsToPush: Option[Set[VoyageManifest]] = None
    var latestZipFilename: Option[String] = None
    var fetchInProgress: Boolean = false
    val dqRegex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

    askableVoyageManifestsActor.ask(GetLatestZipFilename)(new Timeout(1 second)).onSuccess {
      case lzf: String =>
        latestZipFilename = Option(lzf)
        if (isAvailable(out)) fetchAndPushManifests(lzf)
    }

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        latestZipFilename match {
          case None => log.info(s"We don't have a latestZipFilename yet")
          case Some(lzf) =>
            log.info(s"Sending latestZipFilename: $lzf to VoyageManfestsActor")
            voyageManifestsActor ! UpdateLatestZipFilename(lzf)
            fetchAndPushManifests(lzf)
        }
      }
    })

    def fetchAndPushManifests(lzf: String): Unit = {
      if (!fetchInProgress) {
        log.info(s"Fetching manifests from files newer than ${lzf}")
        val manifestsFuture = advPaxInfo.manifestsFuture(lzf)
        manifestsFuture.onSuccess {
          case ms =>
            val maxFilename = ms.map(_._1).max
            latestZipFilename = Option(maxFilename)
            log.info(s"Set latestZipFilename to '$latestZipFilename'")
            val vms = ms.map(_._2).toSet
            (vms -- manifestsState) match {
              case newOnes if newOnes.isEmpty =>
                log.info(s"No new manifests")
              case newOnes =>
                log.info(s"${newOnes.size} manifests to push")
                manifestsToPush = Option(newOnes)
                manifestsState = manifestsState ++ newOnes
            }

            manifestsToPush match {
              case None =>
                log.info(s"No manifests to push")
              case Some(manifests) =>
                log.info(s"Pushing ${manifests.size} manifests")
                push(out, manifests)
                manifestsToPush = None
            }

            fetchInProgress = false
            fetchAndPushManifests(maxFilename)
        }
        manifestsFuture.onFailure {
          case t =>
            log.info(s"manifestsFuture failed: $t")
            fetchInProgress = false
            fetchAndPushManifests(lzf)
        }
      } else log.info(s"Fetch already in progress")
    }
  }
}

class FlightsAndManifests(portSplits: SplitRatios,
                          csvSplitsProvider: SplitsProvider,
                          historicalEGateSplitProvider: (Option[SplitRatios], Double) => Double)
  extends GraphStage[FanInShape2[Flights, Set[VoyageManifest], CrunchRequest]] {

  val inFlights = Inlet[Flights]("Flights.in")
  val inSplits = Inlet[Set[VoyageManifest]]("Splits.in")
  val out = Outlet[CrunchRequest]("CrunchRequest.out")
  override val shape = new FanInShape2(inFlights, inSplits, out)

  val log = LoggerFactory.getLogger(getClass)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var flights: Map[Int, Arrival] = Map()
    var splits: Map[String, ApiSplits] = Map()
    var somethingToPush = false

    setHandler(inFlights, new InHandler {
      override def onPush(): Unit = {
        val newFlights = grab(inFlights)

        flights = newFlights.flights.foldLeft(flights) {
          case (flightsSoFar, newFlight) =>
            flightsSoFar.get(newFlight.FlightID) match {
              case None =>
                log.info(s"Adding new flight ${newFlight.IATA}")
                somethingToPush = true
                flightsSoFar.updated(newFlight.FlightID, newFlight)
              case Some(f) if f != newFlight =>
                log.info(s"Updating flight ${newFlight.IATA}")
                somethingToPush = true
                flightsSoFar.updated(newFlight.FlightID, newFlight)
              case _ =>
                log.info(s"No update to flight ${newFlight.IATA}")
                flightsSoFar
            }
        }

        log.info(s"we now have ${flights.size} flights")

        if (somethingToPush && isAvailable(out)) {
          pushFlightsWithSplits(flights, splits)
          somethingToPush = false
        }

        if (!hasBeenPulled(inFlights)) pull(inFlights)
      }
    })

    setHandler(inSplits, new InHandler {
      override def onPush(): Unit = {
        val manifests = grab(inSplits)

        val newSplits = manifests.foldLeft(splits) {
          case (splitsSoFar, manifest) =>
            val paxTypeAndQueueCounts: PaxTypeAndQueueCounts = PassengerQueueCalculator.convertVoyageManifestIntoPaxTypeAndQueueCounts(manifest)
            val apiPaxTypeAndQueueCounts = paxTypeAndQueueCounts.map(ptqc => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, ptqc.paxCount))
            val splitsFromManifest = ApiSplits(apiPaxTypeAndQueueCounts, SplitSources.ApiSplitsWithCsvPercentage, PaxNumbers)
            val index = splitsIndex(manifest)

            splitsSoFar.get(index) match {
              case None =>
                log.info(s"Adding new split")
                splitsSoFar.updated(index, splitsFromManifest)
              case Some(s) if s != splitsFromManifest =>
                log.info(s"Updating existing split ($s != $splitsFromManifest")
                splitsSoFar.updated(index, splitsFromManifest)
              case Some(s) if s == splitsFromManifest =>
                log.info(s"No update to splits")
                splitsSoFar
            }
        }

        if (newSplits != splits) {
          somethingToPush = true
          splits = newSplits
          log.info(s"we now have ${splits.size} splits")

          if (somethingToPush && isAvailable(out)) {
            pushFlightsWithSplits(flights, splits)
            somethingToPush = false
          }
        } else {
          log.info(s"no updates from splits")
        }

        if (!hasBeenPulled(inSplits)) pull(inSplits)
      }
    })

    setHandler(out, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"FlightsAndSplits onPull() called - somethingToPush: $somethingToPush")
        if (somethingToPush && isAvailable(out)) {
          pushFlightsWithSplits(flights, splits)
          somethingToPush = false
        } else {
          log.info(s"Nothing to push at the mo")
        }

        if (!hasBeenPulled(inSplits)) pull(inSplits)
        if (!hasBeenPulled(inFlights)) pull(inFlights)
      }
    })

    def pushFlightsWithSplits(flights: Map[Int, Arrival], apiSplits: Map[String, ApiSplits]) = {
      val toPush = flights.map {
        case (_, fs) =>
          val totalPax = BestPax()(fs)
          val historical: Option[List[ApiPaxTypeAndQueueCount]] = csvSplitsProvider(fs).map(ratios => ratios.splits.map {
            case SplitRatio(ptqc, _) if ptqc.passengerType == PaxTypes.EeaMachineReadable =>
              val totalEeaMr = ratios.splits.filter(_.paxType.passengerType == PaxTypes.EeaMachineReadable).map(_.ratio).sum
              val historicalEgateSplit = historicalEGateSplitProvider(Option(ratios), 0.6)
              val ratioOfEeaMr = if (ptqc.queueType == Queues.EeaDesk) 1 - historicalEgateSplit else historicalEgateSplit
              val ratioFromHistorical = totalEeaMr * ratioOfEeaMr
              ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, (ratioFromHistorical * totalPax.toDouble).round)
            case SplitRatio(ptqc, ratio) =>
              ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, (ratio * totalPax.toDouble).round)
          })
          val portDefault: Seq[ApiPaxTypeAndQueueCount] = portSplits.splits.map {
            case SplitRatio(ptqc, ratio) => ApiPaxTypeAndQueueCount(ptqc.passengerType, ptqc.queueType, (ratio * totalPax.toDouble).round)
          }
          val defaultSplits = List(ApiSplits(portDefault.toList, SplitSources.TerminalAverage, PaxNumbers))
          val splitsWithHistorical = historical match {
            case None => defaultSplits
            case Some(h) => ApiSplits(h, SplitSources.Historical, PaxNumbers) :: defaultSplits
          }

          val allSplits = apiSplits.get(splitsIndex(fs)) match {
            case None => splitsWithHistorical
            case Some(as) =>
              val totalApiPax = as.splits.map(_.paxCount).sum
              ApiSplits(
                as.splits.map(aptqc => aptqc.copy(paxCount = ((aptqc.paxCount / totalApiPax) * totalPax))),
                SplitSources.ApiSplitsWithCsvPercentage,
                PaxNumbers) :: splitsWithHistorical
          }
          ApiFlightWithSplits(fs, allSplits)
      }.toSet

      log.info(s"pushing ${toPush.size} flights with splits")

      val localNow = SDate(new DateTime(DateTimeZone.forID("Europe/London")).getMillis)
      val cr = CrunchRequest(toPush.toList, Crunch.getLocalLastMidnight(localNow).millisSinceEpoch, 1440)

      push(out, cr)
    }
  }

  def splitsIndex(arrival: Arrival) = {
    val number = FlightParsing.parseIataToCarrierCodeVoyageNumber(arrival.IATA)
    val vn = padTo4Digits(number.map(_._2).getOrElse("-"))
    val (date, time) = arrival.SchDT.split("T") match {
      case Array(d, t) => (d, t)
    }
    s"$vn-$date-$time"
  }

  def splitsIndex(manifest: VoyageManifest) = {
    s"${padTo4Digits(manifest.VoyageNumber)}-${manifest.ScheduledDateOfArrival}-${manifest.ScheduledTimeOfArrival}Z"
  }

  def padTo4Digits(voyageNumber: String): String = {
    val prefix = voyageNumber.length match {
      case 4 => ""
      case 3 => "0"
      case 2 => "00"
      case 1 => "000"
      case _ => ""
    }
    prefix + voyageNumber
  }
}

case class AdvPaxInfo(s3HostName: String, bucketName: String) {
  implicit val actorSystem = ActorSystem("AdvPaxInfo")
  implicit val materializer = ActorMaterializer()

  val dqRegex = "(drt_dq_[0-9]{6}_[0-9]{6})(_[0-9]{4}\\.zip)".r

  val log = LoggerFactory.getLogger(getClass)

  def manifestsFuture(latestFile: String): Future[Seq[(String, VoyageManifest)]] = {
    log.info(s"requesting zipFiles source")
    zipFiles(latestFile)
      .mapAsync(64) {
        case filename =>
          log.info(s"fetching $filename as stream")
          val zipByteStream = S3StreamBuilder(s3Client).getFileAsStream(bucketName, filename)
          Future(fileNameAndContentFromZip(filename, zipByteStream))
      }
      .mapConcat(jsons => jsons)
      .runWith(Sink.seq[(String, VoyageManifest)])
  }

  def fileNameAndContentFromZip(zipFileName: String, zippedFileByteStream: Source[ByteString, NotUsed]): Seq[(String, VoyageManifest)] = {
    val inputStream: InputStream = zippedFileByteStream.runWith(
      StreamConverters.asInputStream()
    )
    val zipInputStream = new ZipInputStream(inputStream)
    val vmStream = Stream.continually(zipInputStream.getNextEntry).takeWhile(_ != null).map { jsonFile =>
      val buffer = new Array[Byte](4096)
      val stringBuffer = new ArrayBuffer[Byte]()
      var len: Int = zipInputStream.read(buffer)

      while (len > 0) {
        stringBuffer ++= buffer.take(len)
        len = zipInputStream.read(buffer)
      }
      //      log.info(s"Finished reading manifest from ${jsonFile.getName}")
      val content: String = new String(stringBuffer.toArray, UTF_8)
      Tuple3(zipFileName, jsonFile.getName, VoyageManifestParser.parseVoyagePassengerInfo(content))
    }.collect {
      case (zipFilename, _, Success(vm)) if vm.ArrivalPortCode == "STN" => (zipFilename, vm)
    }
    log.info(s"Finished processing $zipFileName")
    vmStream
  }

  def zipFiles(latestFile: String): Source[String, NotUsed] = {
    filterToFilesNewerThan(filesAsSource, latestFile)
  }

  def filterToFilesNewerThan(filesSource: Source[String, NotUsed], latestFile: String) = {
    val filterFrom: String = filterFromFileName(latestFile)
    filesSource.filter(fn => {
      val takeFile = fn >= filterFrom && fn != latestFile
      if (takeFile) log.info(s"taking api zip $fn")
      takeFile
    })
  }

  def filterFromFileName(latestFile: String) = {
    latestFile match {
      case dqRegex(dateTime, _) => dateTime
      case _ => latestFile
    }
  }

  def filesAsSource: Source[String, NotUsed] = {
    S3StreamBuilder(s3Client)
      .listFilesAsStream(bucketName)
      .map {
        case (filename, _) => filename
      }
  }

  def s3Client: AmazonS3AsyncClient = {
    val configuration: ClientConfiguration = new ClientConfiguration()
    configuration.setSignerOverride("S3SignerType")
    val provider: ProfileCredentialsProvider = new ProfileCredentialsProvider("drt-atmos")
    log.info("Creating S3 client")

    val client = new AmazonS3AsyncClient(provider, configuration)
    client.client.setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build)
    client.client.setEndpoint(s3HostName)
    client
  }
}

case class UpdateLatestZipFilename(filename: String)

case object GetLatestZipFilename

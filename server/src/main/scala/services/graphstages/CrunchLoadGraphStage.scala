package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.{OptimizerConfig, OptimizerCrunchResult, SDate, TryCrunch}

import scala.collection.immutable.Map
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}


class LoadBatchUpdateGraphStage(now: () => SDateLike, expireAfterMillis: MillisSinceEpoch) extends GraphStage[FlowShape[Loads, Loads]] {
  val inLoads: Inlet[Loads] = Inlet[Loads]("Loads.in")
  val outLoads: Outlet[Loads] = Outlet[Loads]("Loads.out")

  override def shape: FlowShape[Loads, Loads] = new FlowShape(inLoads, outLoads)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutesQueue: List[(MillisSinceEpoch, Loads)] = List[(MillisSinceEpoch, Loads)]()

    val log: Logger = LoggerFactory.getLogger(getClass)

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val incomingLoads = grab(inLoads)
        val changedDays = incomingLoads.loadMinutes.groupBy(sm => getLocalLastMidnight(SDate(sm.minute, europeLondonTimeZone)).millisSinceEpoch)

        val updatedMinutes = changedDays.foldLeft(loadMinutesQueue.toMap) {
          case (soFar, (dayMillis, loadMinutes)) => soFar.updated(dayMillis, Loads(loadMinutes))
        }.toList.sortBy(_._1)

        loadMinutesQueue = Crunch.purgeExpired(updatedMinutes, now, expireAfterMillis)

        pushIfAvailable()

        pull(inLoads)
      }
    })

    setHandler(outLoads, new OutHandler {
      override def onPull(): Unit = {
        log.info(s"onPull called. ${loadMinutesQueue.length} sets of minutes in the queue")

        pushIfAvailable()

        if (!hasBeenPulled(inLoads)) pull(inLoads)
      }
    })

    def pushIfAvailable(): Unit = {
      loadMinutesQueue match {
        case Nil => log.info(s"Queue is empty. Nothing to push")
        case _ if !isAvailable(outLoads) =>
          log.info(s"outLoads not available to push")
        case (millis, loadMinutes) :: queueTail =>
          log.info(s"Pushing ${SDate(millis).toLocalDateTimeString()} ${loadMinutes.loadMinutes.size} load minutes for ${loadMinutes.loadMinutes.groupBy(_.terminalName).keys.mkString(", ")}")
          push(outLoads, loadMinutes)

          loadMinutesQueue = queueTail
          log.info(s"Queue length now ${loadMinutesQueue.length}")
      }
    }
  }
}

class CrunchLoadGraphStage(name: String = "",
                           optionalInitialCrunchMinutes: Option[CrunchMinutes],
                           airportConfig: AirportConfig,
                           expireAfterMillis: MillisSinceEpoch,
                           now: () => SDateLike,
                           crunch: TryCrunch,
                           crunchPeriodStartMillis: SDateLike => SDateLike,
                           minutesToCrunch: Int)
  extends GraphStage[FlowShape[Loads, DeskRecMinutes]] {

  val inLoads: Inlet[Loads] = Inlet[Loads]("inLoads.in")
  val outDeskRecMinutes: Outlet[DeskRecMinutes] = Outlet[DeskRecMinutes]("outDeskRecMinutes.out")

  override val shape = new FlowShape(inLoads, outDeskRecMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    var loadMinutes: Map[Int, LoadMinute] = Map()
    var existingDeskRecMinutes: Map[Int, DeskRecMinute] = Map()
    var deskRecMinutesToPush: Map[Int, DeskRecMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      loadMinutes = optionalInitialCrunchMinutes match {
        case None => Map()
        case Some(CrunchMinutes(cms)) => cms.map(cm => {
          val lm = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
          (lm.uniqueId, lm)
        }).toMap
      }

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val incomingLoads = grab(inLoads)
        log.info(s"Received ${incomingLoads.loadMinutes.size} loads")

        val allMinuteMillis = incomingLoads.loadMinutes.map(_.minute)
        val firstMinute = crunchPeriodStartMillis(SDate(allMinuteMillis.min))
        val lastMinute = firstMinute.addMinutes(minutesToCrunch)
        log.info(s"Crunch ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()}")

        val updatedLoads: Map[Int, LoadMinute] = mergeLoads(incomingLoads.loadMinutes, loadMinutes)
        loadMinutes = purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        val deskRecMinutes: Set[DeskRecMinute] = crunchLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, loadMinutes.values.toSet)
        val deskRecMinutesByKey = deskRecMinutes.map(cm => (cm.key, cm)).toMap

        val diff = deskRecMinutes -- existingDeskRecMinutes.values.toSet

        existingDeskRecMinutes = purgeExpired(deskRecMinutesByKey, (cm: DeskRecMinute) => cm.minute, now, expireAfterMillis)

        val mergedDeskRecMinutes = mergeDeskRecMinutes(diff, deskRecMinutesToPush)
        deskRecMinutesToPush = purgeExpired(mergedDeskRecMinutes, (cm: DeskRecMinute) => cm.minute, now, expireAfterMillis)
        log.info(s"Now have ${deskRecMinutesToPush.size} desk rec minutes to push")

        pushStateIfReady()

        pullLoads()
      }
    })

    def crunchLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, loads: Set[LoadMinute]): Set[DeskRecMinute] = {
      loads
        .filter(lm => firstMinute <= lm.minute && lm.minute < lastMinute)
        .groupBy(_.terminalName)
        .flatMap {
          case (tn, tLms) => tLms
            .groupBy(_.queueName)
            .flatMap {
              case (qn, qLms) =>
                log.info(s"Crunching $tn $qn")
                val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
                val sortedLms = qLms.toSeq.sortBy(_.minute)
                val workMinutes: Map[MillisSinceEpoch, Double] = sortedLms.map(m => (m.minute, m.workLoad)).toMap
                val paxMinutes: Map[MillisSinceEpoch, Double] = sortedLms.map(m => (m.minute, m.paxLoad)).toMap
                val minuteMillis = firstMinute until lastMinute by 60000
                val fullWorkMinutes = minuteMillis.map(m => workMinutes.getOrElse(m, 0d))
                val adjustedWorkMinutes = if (qn == Queues.EGate) fullWorkMinutes.map(_ / airportConfig.eGateBankSize) else fullWorkMinutes
                val fullPaxMinutes = minuteMillis.map(m => paxMinutes.getOrElse(m, 0d))
                val (minDesks, maxDesks) = minMaxDesksForQueue(minuteMillis, tn, qn)
                val triedResult: Try[OptimizerCrunchResult] = crunch(adjustedWorkMinutes, minDesks, maxDesks, OptimizerConfig(sla))
                triedResult match {
                  case Success(OptimizerCrunchResult(desks, waits)) =>
                    minuteMillis.zipWithIndex.map {
                      case (minute, idx) =>
                        val wl = fullWorkMinutes(idx)
                        val pl = fullPaxMinutes(idx)
                        DeskRecMinute(tn, qn, minute, pl, wl, desks(idx), waits(idx))
                    }
                  case Failure(t) =>
                    log.warn(s"failed to crunch: $t")
                    Set()
                }
            }
        }.toSet
    }

    def minMaxDesksForQueue(deskRecMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    def mergeDeskRecMinutes(updatedCms: Set[DeskRecMinute], existingCms: Map[Int, DeskRecMinute]): Map[Int, DeskRecMinute] = {
      updatedCms.foldLeft(existingCms) {
        case (soFar, newLoadMinute) => soFar.updated(newLoadMinute.key, newLoadMinute)
      }
    }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val loadDiff = updatedLoads -- existingLoads
      log.info(s"${loadDiff.size} updated load minutes")

      loadDiff
    }

    def mergeLoads(incomingLoads: Set[LoadMinute], existingLoads: Map[Int, LoadMinute]): Map[Int, LoadMinute] = {
      incomingLoads.foldLeft(existingLoads) {
        case (soFar, load) => soFar.updated(load.uniqueId, load)
      }
    }

    setHandler(outDeskRecMinutes, new OutHandler {
      override def onPull(): Unit = {
        log.debug(s"outLoads onPull called")
        pushStateIfReady()
        pullLoads()
      }
    })

    def pullLoads(): Unit = {
      if (!hasBeenPulled(inLoads)) {
        log.info(s"Pulling inFlightsWithSplits")
        pull(inLoads)
      }
    }

    def pushStateIfReady(): Unit = {
      if (deskRecMinutesToPush.isEmpty) log.info(s"We have no crunch minutes. Nothing to push")
      else if (isAvailable(outDeskRecMinutes)) {
        log.info(s"Pushing ${deskRecMinutesToPush.size} crunch minutes")
        push(outDeskRecMinutes, DeskRecMinutes(deskRecMinutesToPush.values.toSet))
        deskRecMinutesToPush = Map()
      } else log.info(s"outDeskRecMinutes not available to push")
    }
  }
}

case class DeskRecMinute(terminalName: TerminalName,
                         queueName: QueueName,
                         minute: MillisSinceEpoch,
                         paxLoad: Double,
                         workLoad: Double,
                         deskRec: Int,
                         waitTime: Int) extends DeskRecMinuteLike {
  lazy val key: Int = MinuteHelper.key(terminalName, queueName, minute)
}

case class DeskRecMinutes(minutes: Set[DeskRecMinute]) extends PortStateMinutes {
  def applyTo(maybePortState: Option[PortState], now: SDateLike): Option[PortState] = {
    maybePortState match {
      case None => Option(PortState(Map(), newCrunchMinutes, Map()))
      case Some(portState) =>
        val updatedCrunchMinutes = minutes
          .foldLeft(portState.crunchMinutes) {
            case (soFar, updatedDrm) =>
              val maybeMinute: Option[CrunchMinute] = soFar.get(updatedDrm.key)
              val mergedCm: CrunchMinute = mergeMinute(maybeMinute, updatedDrm)
              soFar.updated(updatedDrm.key, mergedCm.copy(lastUpdated = Option(now.millisSinceEpoch)))
          }
        Option(portState.copy(crunchMinutes = updatedCrunchMinutes))
    }
  }

  def newCrunchMinutes: Map[Int, CrunchMinute] = minutes
    .map(CrunchMinute(_))
    .map(cm => (cm.key, cm))
    .toMap


  def mergeMinute(maybeMinute: Option[CrunchMinute], updatedDrm: DeskRecMinute): CrunchMinute = maybeMinute
    .map(existingCm => existingCm.copy(
      paxLoad = updatedDrm.paxLoad,
      workLoad = updatedDrm.workLoad,
      deskRec = updatedDrm.deskRec,
      waitTime = updatedDrm.waitTime,
      lastUpdated = Option(SDate.now().millisSinceEpoch)
    ))
    .getOrElse(CrunchMinute(updatedDrm))
}
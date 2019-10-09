package services.graphstages

import akka.stream._
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import drt.shared.CrunchApi._
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import services.graphstages.Crunch._
import services.metrics.StageTimer
import services.{OptimizerConfig, OptimizerCrunchResult, SDate, TryCrunch}

import scala.collection.immutable.{Map, SortedMap}
import scala.collection.mutable
import scala.util.{Failure, Success, Try}


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

  val stageName = "crunch"

  override val shape = new FlowShape(inLoads, outDeskRecMinutes)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    val loadMinutes: mutable.SortedMap[TQM, LoadMinute] = mutable.SortedMap()
    val existingDeskRecMinutes: mutable.SortedMap[TQM, DeskRecMinute] = mutable.SortedMap()
    var deskRecMinutesToPush: Map[TQM, DeskRecMinute] = Map()

    val log: Logger = LoggerFactory.getLogger(s"$getClass-$name")

    override def preStart(): Unit = {
      optionalInitialCrunchMinutes.foreach {
        case CrunchMinutes(cms) =>
          cms.foreach(cm => {
            val lm = LoadMinute(cm.terminalName, cm.queueName, cm.paxLoad, cm.workLoad, cm.minute)
            loadMinutes += (lm.uniqueId -> lm)
          })
      }

      super.preStart()
    }

    setHandler(inLoads, new InHandler {
      override def onPush(): Unit = {
        val timer = StageTimer(stageName, inLoads)
        val incomingLoads = grab(inLoads)
        log.info(s"Received ${incomingLoads.loadMinutes.size} loads")

        val allMinuteMillis = incomingLoads.loadMinutes.keys.map(_.minute)
        val firstMinute = crunchPeriodStartMillis(SDate(allMinuteMillis.min))
        val lastMinute = firstMinute.addMinutes(minutesToCrunch)
        log.info(s"Crunch ${firstMinute.toLocalDateTimeString()} - ${lastMinute.toLocalDateTimeString()}")
        val affectedTerminals = incomingLoads.loadMinutes.keys.map(_.terminalName).toSet

        loadMinutes ++= incomingLoads.loadMinutes
        purgeExpired(loadMinutes, TQM.atTime, now, expireAfterMillis.toInt)

        val deskRecMinutes = crunchLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, affectedTerminals)

        val affectedDeskRecs = deskRecMinutes.foldLeft(Map[TQM, DeskRecMinute]()) {
          case (soFar, (key, drm)) =>
            existingDeskRecMinutes.get(key) match {
              case Some(existingDrm) if existingDrm == drm => soFar
              case _ => soFar.updated(key, drm)
            }
        }

        existingDeskRecMinutes ++= deskRecMinutes
        purgeExpired(existingDeskRecMinutes, TQM.atTime, now, expireAfterMillis.toInt)

        deskRecMinutesToPush ++= affectedDeskRecs

        log.info(s"Now have ${deskRecMinutesToPush.size} desk rec minutes to push")

        pushStateIfReady()

        pullLoads()
        timer.stopAndReport()
      }
    })

    def crunchLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToCrunch: Set[TerminalName]): SortedMap[TQM, DeskRecMinute] = {
      val terminalQueueDeskRecs = for {
        terminal <- terminalsToCrunch
        queue <- airportConfig.nonTransferQueues(terminal)
      } yield {
        val lms = (firstMinute until lastMinute by 60000).map(minute =>
          loadMinutes.getOrElse(TQM(terminal, queue, minute), LoadMinute(terminal, queue, 0, 0, minute)))
        crunchQueue(firstMinute, lastMinute, terminal, queue, lms)
      }
      SortedMap[TQM, DeskRecMinute]() ++ terminalQueueDeskRecs.toSeq.flatten
    }

    def crunchQueue(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, tn: TerminalName, qn: QueueName, qLms: IndexedSeq[LoadMinute]): SortedMap[TQM, DeskRecMinute] = {
      val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
      val paxMinutes = qLms.map(_.paxLoad)
      val workMinutes = qLms.map(_.workLoad)
      val adjustedWorkMinutes = if (qn == Queues.EGate) workMinutes.map(_ / airportConfig.eGateBankSize) else workMinutes
      val minuteMillis: Seq[MillisSinceEpoch] = firstMinute until lastMinute by 60000
      val (minDesks, maxDesks) = minMaxDesksForQueue(minuteMillis, tn, qn)
      val start = SDate.now()
      val triedResult: Try[OptimizerCrunchResult] = crunch(adjustedWorkMinutes, minDesks, maxDesks, OptimizerConfig(sla))
      triedResult match {
        case Success(OptimizerCrunchResult(desks, waits)) =>
          log.debug(s"Optimizer for $qn Took ${SDate.now().millisSinceEpoch - start.millisSinceEpoch}ms")
          SortedMap[TQM, DeskRecMinute]() ++ minuteMillis.zipWithIndex.map {
            case (minute, idx) =>
              val wl = workMinutes(idx)
              val pl = paxMinutes(idx)
              val drm = DeskRecMinute(tn, qn, minute, pl, wl, desks(idx), waits(idx))
              (drm.key, drm)
          }
        case Failure(t) =>
          log.warn(s"failed to crunch: $t")
          SortedMap[TQM, DeskRecMinute]()
      }
    }

    def minMaxDesksForQueue(deskRecMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    setHandler(outDeskRecMinutes, new OutHandler {
      override def onPull(): Unit = {
        val timer = StageTimer(stageName, outDeskRecMinutes)
        pushStateIfReady()
        pullLoads()
        timer.stopAndReport()
      }
    })

    def pullLoads(): Unit = {
      if (!hasBeenPulled(inLoads)) {
        log.debug(s"Pulling inFlightsWithSplits")
        pull(inLoads)
      }
    }

    def pushStateIfReady(): Unit = {
      if (deskRecMinutesToPush.isEmpty) log.debug(s"We have no crunch minutes. Nothing to push")
      else if (isAvailable(outDeskRecMinutes)) {
        log.info(s"Pushing ${deskRecMinutesToPush.size} crunch minutes")
        push(outDeskRecMinutes, DeskRecMinutes(deskRecMinutesToPush.values.toSeq))
        deskRecMinutesToPush = Map()
      } else log.debug(s"outDeskRecMinutes not available to push")
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
  lazy val key: TQM = MinuteHelper.key(terminalName, queueName, minute)
}

case class DeskRecMinutes(minutes: Seq[DeskRecMinute]) extends PortStateMinutes {
  def applyTo(portState: PortStateMutable, now: MillisSinceEpoch): PortStateDiff = {
    val crunchMinutesDiff = minutes.foldLeft(List[CrunchMinute]()) { case (soFar, dm) =>
      val merged = mergeMinute(portState.crunchMinutes.getByKey(dm.key), dm, now)
      merged :: soFar
    }
    portState.crunchMinutes +++= crunchMinutesDiff
    val newDiff = PortStateDiff(Seq(), Seq(), crunchMinutesDiff, Seq())

    newDiff
  }

  def newCrunchMinutes: SortedMap[TQM, CrunchMinute] = SortedMap[TQM, CrunchMinute]() ++ minutes
    .map(CrunchMinute(_))
    .map(cm => (cm.key, cm))
    .toMap

  def mergeMinute(maybeMinute: Option[CrunchMinute], updatedDrm: DeskRecMinute, now: MillisSinceEpoch): CrunchMinute = maybeMinute
    .map(_.copy(
      paxLoad = updatedDrm.paxLoad,
      workLoad = updatedDrm.workLoad,
      deskRec = updatedDrm.deskRec,
      waitTime = updatedDrm.waitTime
    ))
    .getOrElse(CrunchMinute(updatedDrm))
    .copy(lastUpdated = Option(now))
}

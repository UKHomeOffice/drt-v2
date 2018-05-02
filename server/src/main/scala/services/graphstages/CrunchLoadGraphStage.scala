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


class CrunchLoadGraphStage(name: String = "",
                           optionalInitialCrunchMinutes: Option[CrunchMinutes],
                           airportConfig: AirportConfig,
                           expireAfterMillis: MillisSinceEpoch,
                           now: () => SDateLike,
                           crunch: TryCrunch,
                           crunchPeriodStartMillis: SDateLike => SDateLike,
                           minutesToCrunch: Int,
                           minCrunchLoadThreshold: Int)
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
        val affectedTerminals = incomingLoads.loadMinutes.map(_.terminalName)

        val updatedLoads: Map[Int, LoadMinute] = mergeLoads(incomingLoads.loadMinutes, loadMinutes)
        loadMinutes = purgeExpired(updatedLoads, (lm: LoadMinute) => lm.minute, now, expireAfterMillis)

        val deskRecMinutes: Map[Int, DeskRecMinute] = crunchLoads(firstMinute.millisSinceEpoch, lastMinute.millisSinceEpoch, affectedTerminals)

        val diff = deskRecMinutes.foldLeft(Map[Int, DeskRecMinute]()) {
          case (soFar, (key, drm)) =>
            existingDeskRecMinutes.get(key) match {
              case Some(existingDrm) if existingDrm == drm => soFar
              case _ => soFar.updated(key, drm)
            }
        }

        existingDeskRecMinutes = purgeExpired(deskRecMinutes, (cm: DeskRecMinute) => cm.minute, now, expireAfterMillis)

        val mergedDeskRecMinutes = mergeDeskRecMinutes(diff, deskRecMinutesToPush)
        deskRecMinutesToPush = purgeExpired(mergedDeskRecMinutes, (cm: DeskRecMinute) => cm.minute, now, expireAfterMillis)
        log.info(s"Now have ${deskRecMinutesToPush.size} desk rec minutes to push")

        pushStateIfReady()

        pullLoads()
      }
    })

    def crunchLoads(firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToCrunch: Set[TerminalName]): Map[Int, DeskRecMinute] = {
      val filteredLoads = filterTerminalQueueMinutes(firstMinute, lastMinute, terminalsToCrunch, loadMinutes)

      filteredLoads
        .groupBy(_.terminalName)
        .flatMap {
          case (tn, tLms) => tLms
            .groupBy(_.queueName)
            .flatMap {
              case (qn, qLms) =>
                val sla = airportConfig.slaByQueue.getOrElse(qn, 15)
                val sortedLms = qLms.toSeq.sortBy(_.minute)
                val paxMinutes: Map[MillisSinceEpoch, Double] = sortedLms.map(m => (m.minute, m.paxLoad)).toMap
                val nonZeroMinutes = paxMinutes.values.count(_ != 0d)
                if (nonZeroMinutes >= minCrunchLoadThreshold) {
                  log.info(s"Crunching $tn $qn ($nonZeroMinutes non-zero pax minutes)")
                  val workMinutes: Map[MillisSinceEpoch, Double] = sortedLms.map(m => (m.minute, m.workLoad)).toMap
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
                          val drm = DeskRecMinute(tn, qn, minute, pl, wl, desks(idx), waits(idx))
                          (drm.key, drm)
                      }
                    case Failure(t) =>
                      log.warn(s"failed to crunch: $t")
                      Map()
                  }
                } else {
                  log.warn(s"$nonZeroMinutes non-zero loads < $minCrunchLoadThreshold.. Skipped crunch")
                  Map()
                }
            }
        }
    }

    def filterTerminalQueueMinutes[A <: TerminalQueueMinute](firstMinute: MillisSinceEpoch, lastMinute: MillisSinceEpoch, terminalsToUpdate: Set[TerminalName], toFilter: Map[Int, A]): Set[A] = {
      val maybeThings = for {
        terminalName <- terminalsToUpdate
        queueName <- airportConfig.queues.getOrElse(terminalName, Seq())
        minute <- firstMinute until lastMinute by oneMinuteMillis
      } yield {
        toFilter.get(MinuteHelper.key(terminalName, queueName, minute))
      }

      val minutesFound = maybeThings.collect { case Some(thing) => thing }

      terminalsToUpdate.foreach(t =>
        airportConfig.queues.getOrElse(t, Seq()).foreach(q => {
          log.info(s"found ${minutesFound.count(l => l.terminalName == t && l.queueName == q)} loads for $t/$q")
        })
      )

      minutesFound
    }

    def minMaxDesksForQueue(deskRecMinutes: Seq[MillisSinceEpoch], tn: TerminalName, qn: QueueName): (Seq[Int], Seq[Int]) = {
      val defaultMinMaxDesks = (Seq.fill(24)(0), Seq.fill(24)(10))
      val queueMinMaxDesks = airportConfig.minMaxDesksByTerminalQueue.getOrElse(tn, Map()).getOrElse(qn, defaultMinMaxDesks)
      val minDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._1))
      val maxDesks = deskRecMinutes.map(desksForHourOfDayInUKLocalTime(_, queueMinMaxDesks._2))
      (minDesks, maxDesks)
    }

    def mergeDeskRecMinutes(updatedCms: Map[Int, DeskRecMinute], existingCms: Map[Int, DeskRecMinute]): Map[Int, DeskRecMinute] = {
      updatedCms.foldLeft(existingCms) {
        case (soFar, (newId, newLoadMinute)) => soFar.updated(newId, newLoadMinute)
      }
    }

    def loadDiff(updatedLoads: Set[LoadMinute], existingLoads: Set[LoadMinute]): Set[LoadMinute] = {
      val loadDiff = updatedLoads -- existingLoads
      log.info(s"${loadDiff.size} updated load minutes")

      loadDiff
    }

    def mergeLoads(incomingLoads: Set[LoadMinute], existingLoads: Map[Int, LoadMinute]): Map[Int, LoadMinute] = {
      incomingLoads
        .groupBy(_.terminalName)
        .foreach {
          case (tn, tlms) => tlms
            .groupBy(_.queueName)
            .foreach {
              case (qn, qlms) => log.info(s"incoming loads for $tn / $qn -> ${qlms.size} - ${qlms.count(_.paxLoad == 0)} zero pax loads")
            }
        }

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
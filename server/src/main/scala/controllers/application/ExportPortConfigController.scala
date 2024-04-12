package controllers.application

import akka.pattern.ask
import com.google.inject.Inject
import drt.shared.api.{WalkTime, WalkTimes}
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates.{EgateBanksUpdates, PortEgateBanksUpdates}
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{PaxTypes, Queues, Terminals}
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate}

import scala.concurrent.Future

class ExportPortConfigController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with WalkTimeLike {
  private def updatesByTerminalF: Future[Map[Terminals.Terminal, EgateBanksUpdates]] = {
    val eGateBanks: Future[PortEgateBanksUpdates] = ctrl.applicationService.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]
    eGateBanks.map(_.updatesByTerminal)
  }

  private val bankSizeString: Int => String = size => s"$size bank${if (size > 1) "s" else ""}"

  private def getEGateConfig: Future[String] = {
    updatesByTerminalF.map { updatesByTerminal =>
      val eGatesCsv = updatesByTerminal.map { case (k, v) =>
        v.updates.map { updates =>
          s"$k,${SDate(updates.effectiveFrom).prettyDateTime},${bankSizeString(updates.banks.length)}: ${
            updates.banks.map { bank => s"${bank.openCount}/${bank.maxCapacity}" }.mkString(" ")
          }"
        }.mkString("\n")
      }.mkString("\n")
      val eGatesHeader = "E-gates schedule"
      val eGatesHeaders = "Terminal,Effective from,OpenGates per bank"
      Seq(eGatesHeader, eGatesHeaders, eGatesCsv).mkString("\n")
    }
  }

  private def getSlaConfig(thisTerminal: Terminals.Terminal): Future[String] = {
    val slaHeader = "Queue SLAs"
    ctrl.applicationService.slasActor
      .ask(GetState).mapTo[SlaConfigs]
      .map { sla =>
        val terminalQueueOrder = Queues.queueOrder.filter(q => airportConfig.queuesByTerminal.get(thisTerminal).exists(_.contains(q)))
        val slaHeaders = s"Effective from,${terminalQueueOrder.mkString(",")}"
        val slaCsv = sla.configs.map {
          case (date, queues) =>
            val dateStr = SDate(date).prettyDateTime
            val queueSlas = terminalQueueOrder
              .map { queue =>
                val maybeSla = queues.get(queue)
                maybeSla.map(_.toString).getOrElse("")
              }
              .mkString(",")

            s"$dateStr,$queueSlas"
        }.mkString("\n")
        Seq(slaHeader, slaHeaders, slaCsv).mkString("\n")
      }
  }

  private def processingTime(terminal: Terminals.Terminal): String = {
    val processingTimeString = airportConfig.terminalProcessingTimes(terminal)
      .toList
      .sortBy {
        case (paxTypeAndQueue, _) => paxTypeAndQueue.queueType.toString + paxTypeAndQueue.passengerType
      }
      .map {
        case (ptq, time) => s"${ptq.displayName},${(time * 60).toInt}"
      }
      .mkString("\n")
    val processingTimesHeader = "Processing Times"
    val passengerTypeQueueAndSecondsHeader = "Passenger Type & Queue,Seconds"
    Seq(processingTimesHeader, passengerTypeQueueAndSecondsHeader, processingTimeString).mkString("\n")
  }

  private def getDeskAndEGates(terminal: Terminals.Terminal): Future[String] = {
    updatesByTerminalF
      .map { updatesByTerminal =>
        updatesByTerminal.get(terminal).flatMap(_.updatesForDate(SDate.now().millisSinceEpoch))
          .map { update =>
            val openGatesByBank: Seq[Int] = update.banks.map { bank => bank.openCount }
            val totalOpenGates = openGatesByBank.sum
            s"$totalOpenGates egates in ${openGatesByBank.length} banks: ${openGatesByBank.mkString(" ")}"
          }
      }
      .map { eGates =>
        val deskEGateHeader = "Desks and Egates"
        val desks: String = s"${airportConfig.desksByTerminal.getOrElse(terminal, "n/a")} desks"
        (Seq(deskEGateHeader, desks) ++ eGates.toList).mkString("\n")
      }
  }

  private def defaultPaxSplits(terminal: Terminals.Terminal): String = {
    val passengerAllocationHeader = "Passenger Queue Allocation"
    val passengerTypeAndQueueAllocationHeader = "Passenger Type,Queue,Allocation"
    val allocationString = airportConfig.terminalPaxTypeQueueAllocation(terminal)
      .toList
      .sortBy {
        case (pt, _) => pt.cleanName
      }
      .flatMap {
        case (pt, list) =>
          list.map {
            case (qt, ratio) =>
              s"${PaxTypes.displayName(pt)},${Queues.displayName(qt)},${Math.round(ratio * 100)}%"
          }
      }
      .mkString("\n")
    Seq(passengerAllocationHeader, passengerTypeAndQueueAllocationHeader, allocationString).mkString("\n")
  }

  private def defaultWalkTime(terminal: Terminals.Terminal): String = {
    val walkTimeHeader = "Walk times"
    val walkTimeString = s"Default walk time (minutes),${airportConfig.defaultWalkTimeMillis(terminal) / MilliTimes.oneMinuteMillis}"
    Seq(walkTimeHeader, walkTimeString).mkString("\n")
  }

  private def walkTimesFromConfig(terminal: Terminals.Terminal): String = {

    val gates = walkTimes(ctrl.params.gateWalkTimesFilePath)
    val stands = walkTimes(ctrl.params.standWalkTimesFilePath)

    val walkTimesGatesStands = WalkTimes(gates, stands)

    if (walkTimesGatesStands.byTerminal.nonEmpty) {
      val gateStandHeader = "Gate/Stand Walk time"
      val gate = walkTimeString("Gate", walkTimesGatesStands.byTerminal(terminal).gateWalktimes)
      val stand = walkTimeString("Stand", walkTimesGatesStands.byTerminal(terminal).standWalkTimes)
      Seq(gateStandHeader, gate, stand).mkString("\n")
    } else ""
  }

  private val walkTimesString: Map[String, WalkTime] => String =
    walkTimes =>
      walkTimes
        .map {
          case (k, v) => s"$k,${v.inMinutes}"
        }
        .mkString("\n")

  private def walkTimeString(header: String, walktimes: Map[String, WalkTime]): String = {
    val walkTimeHeader = s"$header,Walk time in minutes"
    val walkTimeString = walkTimesString(walktimes)
    Seq(walkTimeHeader, walkTimeString).mkString("\n")
  }

  private def getAirportConfig(tn: Terminals.Terminal): String = {
    s"""${processingTime(tn)}
       |
       |${defaultPaxSplits(tn)}
       |
       |${defaultWalkTime(tn)}
       |
       |${walkTimesFromConfig(tn)}"""
  }

  def exportConfig: Action[AnyContent] = Action.async { _ =>
    val aConfigAndDeskByTerminal = Future.sequence {
      airportConfig.terminals.map { tn =>
        val terminal = tn.toString
        val slaConfig: Future[String] = getSlaConfig(tn)
        val airportConfigString = getAirportConfig(tn)
        val deskAndEGates: Future[String] = getDeskAndEGates(tn)
        slaConfig.flatMap { sla =>
          deskAndEGates.map { dAndE =>
            s"""$terminal
               |
               |$sla
               |
               |$dAndE
               |
               |$airportConfigString"""
          }
        }
      }
    }.map(_.mkString("\n\n"))

    for {
      eGateConfig <- getEGateConfig
      terminalConfig <- aConfigAndDeskByTerminal
    } yield Ok(
      s"""$eGateConfig
         |
         |$terminalConfig""".stripMargin)

  }
}

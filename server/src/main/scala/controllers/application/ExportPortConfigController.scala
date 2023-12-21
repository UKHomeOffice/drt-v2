package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates.{EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}
import akka.pattern.ask
import drt.shared.api.{WalkTime, WalkTimes}
import uk.gov.homeoffice.drt.ports.{PaxTypes, Queues, Terminals}
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.time.SDate
import uk.gov.homeoffice.drt.actor.WalkTimeProvider

import scala.concurrent.Future

class ExportPortConfigController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) {

  def updatesByTerminalF: Future[Map[Terminals.Terminal, EgateBanksUpdates]] = {
    val eGateBanks: Future[PortEgateBanksUpdates] = ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]
    eGateBanks.map(_.updatesByTerminal)
  }

  def getEGateConfig: Future[String] = {
    updatesByTerminalF.map { updatesByTerminal =>
      val eGatesCsv = updatesByTerminal.map { case (k, v) =>
        v.updates.map { updates =>
          updates.banks.zipWithIndex
            .map { case (v, i) => s"$k,${updates.effectiveFrom},bank-$i ${v.openCount}/${v.maxCapacity}" }.mkString("\n")
        }.mkString("\n")
      }.mkString("\n")
      val eGatesHeaders = "Terminal,Effective from,OpenGates per bank"
      s"$eGatesHeaders\n$eGatesCsv"
    }
  }

  def getSlaConfig = {
    val slaUpdates: Future[SlaConfigs] = ctrl.slasActor.ask(GetState).mapTo[SlaConfigs]
    val slaHeaders = "Effective from,Queue,Minutes"
    slaUpdates.map { sla =>
      val slaCsv = sla.configs.map { case (eF, vc) =>
        vc.map { case (queue, v) =>
          s"${eF},${queue},${v} minutes"
        }.mkString("\n")
      }.mkString("\n")
      s"$slaHeaders\n$slaCsv"
    }
  }

  def processingTime(terminal: Terminals.Terminal) = {
    val processingTimeString = airportConfig.terminalProcessingTimes(terminal)
      .toList
      .sortBy {
        case (paxTypeAndQueue, _) => paxTypeAndQueue.queueType.toString + paxTypeAndQueue.passengerType
      }
      .map {
        case (ptq, time) =>
          s"${ptq.displayName} ${(time * 60).toInt}"
      }.mkString("\n")
    val processingTimeHeader = "Processing Times"
    s"$processingTimeHeader\n$processingTimeString"
  }

  def getDeskAndEGates(terminal: Terminals.Terminal): String = {
    val eGates: Future[String] = updatesByTerminalF.map(_.get(terminal).flatMap(_.updatesForDate(SDate.now().millisSinceEpoch)))
      .map { update =>
        val openGatesByBank: Seq[Int] = update.map(_.banks.map { bank => bank.openCount }).getOrElse(List())
        val totalOpenGates = openGatesByBank.sum
        s"$totalOpenGates egates in ${openGatesByBank.length} banks: ${openGatesByBank.mkString(", ")}"
      }

    val deskEGateHeader = s"Desks and Egates"
    val desk: String = s"${airportConfig.desksByTerminal.getOrElse(terminal, "n/a")} desks"
    //    eGates.map(e => s"$deskEGateHeader\n$desk\n$e")
    s"$deskEGateHeader\n$desk"
  }

  def defaultPaxSplits(terminal: Terminals.Terminal) = {
    val passengerAllocationHeader = "Passenger Queue Allocation"
    val queueAllocationHeader = "Passenger Type,Queue,Allocation"
    val allocationString = airportConfig.terminalPaxTypeQueueAllocation(terminal)
      .toList
      .sortBy {
        case (pt, _) => pt.cleanName
      }.flatMap {
      case (pt, list) =>
        list.map {
          case (qt, ratio) =>
            s"${PaxTypes.displayName(pt)} ${Queues.displayName(qt)} ${Math.round(ratio * 100)}%"
        }
    }.mkString("\n")
    s"$passengerAllocationHeader\n$queueAllocationHeader\n$allocationString"
  }

  def defaultWalktime(terminal: Terminals.Terminal) = {
    val walktimeHeader = "Walktimes"
    val walktimeString = s"${airportConfig.defaultWalkTimeMillis(terminal) / 60000} minutes"
    s"$walktimeHeader\n$walktimeString"
  }

  def walkTimesFromConfig(terminal: Terminals.Terminal): String = {
    val walkTimes: String => Iterable[WalkTime] = csvPath => WalkTimeProvider.walkTimes(csvPath).map {
      case ((terminal, gateOrStand), walkTimeSeconds) => WalkTime(gateOrStand, terminal, walkTimeSeconds * 1000)
    }

    val gates = ctrl.params.gateWalkTimesFilePath.map(walkTimes).getOrElse(Iterable())
    val stands = ctrl.params.standWalkTimesFilePath.map(walkTimes).getOrElse(Iterable())

    val walktimesGatesStands = WalkTimes(gates, stands)

    if (walktimesGatesStands.byTerminal.nonEmpty) {
      val gateStandHeader = "Gate/Stand Walktime"
      val gate = gateWalktimeString(walktimesGatesStands.byTerminal(terminal).gateWalktimes)
      val stand = standWalktime(walktimesGatesStands.byTerminal(terminal).standWalkTimes)
      s"$gateStandHeader\n$gate\n$stand"
    } else ""
  }

  def gateWalktimeString(gateWalktimes: Map[String, WalkTime]) = {
    val gateWalktimeHeader = "Gate Walktimes"
    val gateWalktimeString = gateWalktimes
      .map { case (k, v) => s"$k,$v minutes" }.mkString("\n")
    s"$gateWalktimeHeader\n$gateWalktimeString"
  }

  def standWalktime(standWalkTimes: Map[String, WalkTime]) = {
    val standWalktimeHeader = "Stand Walktimes"
    val standWalktimeString = standWalkTimes.map { case (k, v) => s"$k,$v minutes" }.mkString("\n")
    s"$standWalktimeHeader\n$standWalktimeString"
  }

  def getAirportConfig(tn: Terminals.Terminal) = {
    s"${processingTime(tn)}\n" +
      s"${defaultPaxSplits(tn)}\n" +
      s"${defaultWalktime(tn)}\n" +
      s"${walkTimesFromConfig(tn)}"
  }

  def exportConfig: Action[AnyContent] = Action.async { _ =>
    val slaAndEgateBanks: Future[String] = for {
      eGateConfig <- getEGateConfig
      slaConfig <- getSlaConfig
    } yield s"$eGateConfig\n$slaConfig"

    val aConfig: String = airportConfig.terminals.map { tn =>
      val airportConfigString = getAirportConfig(tn)
      val deskAndEGates = getDeskAndEGates(tn)
      s"${tn.toString}\n$deskAndEGates\n$airportConfigString"
    }.mkString("\n")

    slaAndEgateBanks.map { a =>
      Ok(s"$a\n$aConfig")
    }
  }


}

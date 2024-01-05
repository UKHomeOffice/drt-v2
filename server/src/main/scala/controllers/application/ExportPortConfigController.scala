package controllers.application

import com.google.inject.Inject
import play.api.mvc.{Action, AnyContent, ControllerComponents}
import uk.gov.homeoffice.drt.actor.commands.Commands.GetState
import uk.gov.homeoffice.drt.crunchsystem.DrtSystemInterface
import uk.gov.homeoffice.drt.egates.{EgateBanksUpdates, PortEgateBanksUpdates}
import akka.pattern.ask
import drt.shared.api.{WalkTime, WalkTimes}
import uk.gov.homeoffice.drt.ports.{PaxTypes, Queues, Terminals}
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.time.SDate
import scala.concurrent.Future

class ExportPortConfigController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with WalktimeLike {

  private def createTitleHeaderAndBody(title: String, headers: String, body: String, isFirst: Boolean = false): String = {
    val doubleNewlines = "\n\n"
    if (isFirst)
      s"\n$title\n$headers\n$body"
    else
      s"$doubleNewlines$title\n$headers\n$body"
  }

  private def createHeaderAndBody(headers: String, body: String): String = {
    s"\n$headers\n$body"
  }

  private def updatesByTerminalF: Future[Map[Terminals.Terminal, EgateBanksUpdates]] = {
    val eGateBanks: Future[PortEgateBanksUpdates] = ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]
    eGateBanks.map(_.updatesByTerminal)
  }

  private def getEGateConfig: Future[String] = {
    updatesByTerminalF.map { updatesByTerminal =>
      val eGatesCsv = updatesByTerminal.map { case (k, v) =>
        v.updates.map { updates =>
          updates.banks.zipWithIndex
            .map { case (v, i) => s"$k,${SDate(updates.effectiveFrom)},bank-${i + 1}  ${v.openCount}/${v.maxCapacity}" }.mkString("\n")
        }.mkString("\n")
      }.mkString("\n")
      val eGatesTitle = "E-gates schedule"
      val eGatesHeaders = "Terminal,Effective from,OpenGates per bank"
      createTitleHeaderAndBody(eGatesTitle, eGatesHeaders, eGatesCsv, true)
    }
  }

  private def getSlaConfig = {
    val slaUpdates: Future[SlaConfigs] = ctrl.slasActor.ask(GetState).mapTo[SlaConfigs]
    val slaTitle = "Queue SLAs"
    val slaHeaders = "Effective from,Queue,Minutes"
    slaUpdates.map { sla =>
      val slaCsv = sla.configs.map { case (eF, vc) =>
        vc.map { case (queue, v) =>
          s"${SDate(eF)},$queue,$v"
        }.mkString("\n")
      }.mkString("\n")
      createTitleHeaderAndBody(slaTitle, slaHeaders, slaCsv)
    }
  }

  private def processingTime(terminal: Terminals.Terminal) = {
    val processingTimeString = airportConfig.terminalProcessingTimes(terminal)
      .toList
      .sortBy {
        case (paxTypeAndQueue, _) => paxTypeAndQueue.queueType.toString + paxTypeAndQueue.passengerType
      }
      .map {
        case (ptq, time) =>
          s"${ptq.displayName},${(time * 60).toInt}"
      }.mkString("\n")
    val processingTimeTitle = "Processing Times"
    val processingTimeHeader = "Passenger Type & Queue,Seconds"
    createTitleHeaderAndBody(processingTimeTitle, processingTimeHeader, processingTimeString)
  }

  private def getDeskAndEGates(terminal: Terminals.Terminal): Future[String] = {
    updatesByTerminalF.map { updatesByTerminal =>
      updatesByTerminal.get(terminal).flatMap(_.updatesForDate(SDate.now().millisSinceEpoch))
        .map { update =>
          val openGatesByBank: Seq[Int] = update.banks.map { bank => bank.openCount }
          val totalOpenGates = openGatesByBank.sum
          s"$totalOpenGates egates in ${openGatesByBank.length} banks: ${openGatesByBank.mkString(" ")}"
        }
    }.map { eGates =>
      val deskEGateHeader = "Desks and Egates"
      val desk: String = s"${airportConfig.desksByTerminal.getOrElse(terminal, "n/a")} desks"
      createHeaderAndBody(deskEGateHeader, s"$desk\n${eGates.getOrElse("")}")
    }
  }

  private def defaultPaxSplits(terminal: Terminals.Terminal) = {
    val passengerAllocationTitle = "Passenger Queue Allocation"
    val queueAllocationHeader = "Passenger Type,Queue,Allocation"
    val allocationString = airportConfig.terminalPaxTypeQueueAllocation(terminal)
      .toList
      .sortBy {
        case (pt, _) => pt.cleanName
      }.flatMap {
      case (pt, list) =>
        list.map {
          case (qt, ratio) =>
            s"${PaxTypes.displayName(pt)},${Queues.displayName(qt)},${Math.round(ratio * 100)}%"
        }
    }.mkString("\n")
    createTitleHeaderAndBody(passengerAllocationTitle, queueAllocationHeader, allocationString)
  }

  private def defaultWalktime(terminal: Terminals.Terminal) = {
    val MillisInMinute = 60000
    val walktimeTitle = "Walktimes"
    val walktimeHeader = "Gate,Walk time in minutes"
    val walktimeString = s"Default,${airportConfig.defaultWalkTimeMillis(terminal) / MillisInMinute}"
    createTitleHeaderAndBody(walktimeTitle, walktimeHeader, walktimeString)
  }

  private def walkTimesFromConfig(terminal: Terminals.Terminal): String = {

    val gates = walktimes(ctrl.params.gateWalkTimesFilePath)
    val stands = walktimes(ctrl.params.standWalkTimesFilePath)

    val walktimesGatesStands = WalkTimes(gates, stands)

    if (walktimesGatesStands.byTerminal.nonEmpty) {
      val gateStandTitle = "Gate/Stand Walktime"
      val gate = gateWalktimeString(walktimesGatesStands.byTerminal(terminal).gateWalktimes)
      val stand = standWalktime(walktimesGatesStands.byTerminal(terminal).standWalkTimes)
      createTitleHeaderAndBody(gateStandTitle, gate, stand)
    } else ""
  }

  val walkTimesString: Map[String, WalkTime] => String = walkTimes => walkTimes
    .map { case (k, v) => s"$k,${v.walkTimeMillis / 60000}" }.mkString("\n")

  private def gateWalktimeString(gateWalktimes: Map[String, WalkTime]) = {
    val gateWalktimeHeader = "Gate, Walk time in minutes"
    val gateWalktimeString = walkTimesString(gateWalktimes)
    createHeaderAndBody(gateWalktimeHeader, gateWalktimeString)
  }

  private def standWalktime(standWalkTimes: Map[String, WalkTime]) = {
    val standWalktimeHeader = "Stand, Walk time in minutes"
    val standWalktimeString = walkTimesString(standWalkTimes)
    createHeaderAndBody(standWalktimeHeader, standWalktimeString)
  }

  private def getAirportConfig(tn: Terminals.Terminal) = {
    s"""${processingTime(tn)}
       |${defaultPaxSplits(tn)}
       |${defaultWalktime(tn)}
       |${walkTimesFromConfig(tn)}"""
  }

  def exportConfig: Action[AnyContent] = Action.async { _ =>
    val aConfigAndDeskByTerminal = Future.sequence {
      airportConfig.terminals.map { tn =>
        val airportConfigString = getAirportConfig(tn)
        val deskAndEGates: Future[String] = getDeskAndEGates(tn)
        deskAndEGates.map { dAndE =>
          s"$dAndE$airportConfigString"
        }
      }
    }.map(_.mkString("\n"))

    for {
      eGateConfig <- getEGateConfig
      slaConfig <- getSlaConfig
      terminalConfig <- aConfigAndDeskByTerminal
    } yield Ok(
      s"""$eGateConfig
         |$slaConfig
         |$terminalConfig
         """.stripMargin)

  }


}

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
import uk.gov.homeoffice.drt.time.{MilliTimes, SDate}

import scala.concurrent.Future

class ExportPortConfigController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInterface) extends AuthController(cc, ctrl) with WalkTimeLike {

  private def createTitleHeaderAndBody(title: String, headers: String, body: String): String = {
    s"$title\n$headers\n$body"
  }

  private def createHeaderAndBody(headers: String, body: String): String = {
    s"$headers\n$body"
  }

  private def updatesByTerminalF: Future[Map[Terminals.Terminal, EgateBanksUpdates]] = {
    val eGateBanks: Future[PortEgateBanksUpdates] = ctrl.egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates]
    eGateBanks.map(_.updatesByTerminal)
  }

  private def getEGateConfig: Future[String] = {
    updatesByTerminalF.map { updatesByTerminal =>
      val eGatesCsv = updatesByTerminal.map { case (k, v) =>
        v.updates.map { updates =>
          s"$k,${SDate(updates.effectiveFrom).prettyDateTime},${updates.banks.length} bank: ${
            updates.banks.map { bank => s"${bank.openCount}/${bank.maxCapacity}" }.mkString(" ")
          }"
        }.mkString("\n")
      }.mkString("\n")
      val eGatesTitle = "E-gates schedule"
      val eGatesHeaders = "Terminal,Effective from,OpenGates per bank"
      createTitleHeaderAndBody(eGatesTitle, eGatesHeaders, eGatesCsv)
    }
  }

  private def getSlaConfig = {
    val slaUpdates: Future[SlaConfigs] = ctrl.slasActor.ask(GetState).mapTo[SlaConfigs]
    val slaTitle = "Queue SLAs"
    slaUpdates.map { sla =>
      val slaHeaders = s"Effective from,${sla.configs.head._2.map { case (q, _) => s"${Queues.displayName(q)}" }.mkString(",")}"
      val slaCsv = sla.configs.map { case (date, queues) =>
        s"${SDate(date).prettyDateTime},${queues.map { case (_, minutes) => minutes }.mkString(",")}"
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

  private def defaultWalkTime(terminal: Terminals.Terminal) = {
    val walkTimeTitle = "Walk times"
    val walkTimeHeader = "Gate,Walk time in minutes"
    val walkTimeString = s"Default,${airportConfig.defaultWalkTimeMillis(terminal) / MilliTimes.oneMinuteMillis}"
    createTitleHeaderAndBody(walkTimeTitle, walkTimeHeader, walkTimeString)
  }

  private def walkTimesFromConfig(terminal: Terminals.Terminal): String = {

    val gates = walkTimes(ctrl.params.gateWalkTimesFilePath)
    val stands = walkTimes(ctrl.params.standWalkTimesFilePath)

    val walkTimesGatesStands = WalkTimes(gates, stands)

    if (walkTimesGatesStands.byTerminal.nonEmpty) {
      val gateStandTitle = "Gate/Stand Walk time"
      val gate = walkTimeString("Gate", walkTimesGatesStands.byTerminal(terminal).gateWalktimes)
      val stand = walkTimeString("Stand", walkTimesGatesStands.byTerminal(terminal).standWalkTimes)
      createTitleHeaderAndBody(gateStandTitle, gate, stand)
    } else ""
  }

  val walkTimesString: Map[String, WalkTime] => String = walkTimes => walkTimes
    .map { case (k, v) => s"$k,${v.inMinutes}" }.mkString("","\n","")

  private def walkTimeString(header: String, walktimes: Map[String, WalkTime]) = {
    val walkTimeHeader = s"$header, Walk time in minutes"
    val walkTimeString = walkTimesString(walktimes)
    createHeaderAndBody(walkTimeHeader, walkTimeString)
  }

  private def getAirportConfig(tn: Terminals.Terminal) = {
    s"""${processingTime(tn)}\n
       |${defaultPaxSplits(tn)}\n
       |${defaultWalkTime(tn)}\n
       |${walkTimesFromConfig(tn)}"""
  }

  def exportConfig: Action[AnyContent] = Action.async { _ =>
    val aConfigAndDeskByTerminal = Future.sequence {
      airportConfig.terminals.map { tn =>
        val airportConfigString = getAirportConfig(tn)
        val deskAndEGates: Future[String] = getDeskAndEGates(tn)
        deskAndEGates.map { dAndE =>
          s"""$dAndE\n
             |$airportConfigString"""
        }
      }
    }.map(_.mkString("","\n",""))

    for {
      eGateConfig <- getEGateConfig
      slaConfig <- getSlaConfig
      terminalConfig <- aConfigAndDeskByTerminal
    } yield Ok(
      s"""$eGateConfig\n
         |$slaConfig\n
         |$terminalConfig
         """.stripMargin)

  }


}

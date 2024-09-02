package drt.client.components.scenarios

import drt.client.services.JSDateConversions.SDate
import drt.shared.SimulationParams
import drt.shared.SimulationParams.fullDay
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs
import uk.gov.homeoffice.drt.ports.{AirportConfig, PaxTypeAndQueue, Queues}
import uk.gov.homeoffice.drt.time.LocalDate

case class SimulationFormFields(terminal: Terminal,
                                date: LocalDate,
                                passengerWeighting: Option[Double],
                                processingTimes: Map[PaxTypeAndQueue, Option[Int]],
                                minDesksByQueue: Map[Queue, Option[Int]],
                                terminalDesks: Int,
                                eGateBankSizes: IndexedSeq[Option[Int]],
                                slaByQueue: Map[Queue, Option[Int]],
                                crunchOffsetMinutes: Int,
                                eGateOpenHours: Seq[Int],
                               ) {
  val isValid: Boolean = {
    passengerWeighting.isDefined &&
      processingTimes.forall(_._2.isDefined) &&
      minDesksByQueue.forall(_._2.isDefined) &&
      eGateBankSizes.forall(_.isDefined) &&
      slaByQueue.forall(_._2.isDefined)
  }

  private def eGateOpenAt(hour: Int): Boolean = eGateOpenHours.contains(hour)

  def toggleEgateHour(hour: Int): SimulationFormFields = if (eGateOpenAt(hour))
    copy(eGateOpenHours = eGateOpenHours.filter(_ != hour))
  else
    copy(eGateOpenHours = eGateOpenHours :+ hour)

  def closeEgatesAllDay: SimulationFormFields = copy(eGateOpenHours = Seq())

  def openEgatesAllDay: SimulationFormFields = copy(eGateOpenHours = SimulationParams.fullDay)

  def toQueryStringParams: String = {
    List(
      s"terminal=$terminal",
      s"date=$date",
      s"passengerWeighting=${passengerWeighting.getOrElse("")}",
      s"crunchOffsetMinutes=$crunchOffsetMinutes",
      s"eGateOpenHours=${eGateOpenHours.mkString(",")}"
    ) ::
      (if (eGateBankSizes.nonEmpty) {
        //s"eGateBankSizes=${eGateBankSizes.map(_.getOrElse("")).mkString(",")}",
        List(s"eGateBankSizes=${eGateBankSizes.flatten.mkString(",")}")
      } else {
        List()
      }) ::
      processingTimes.map {
        case (ptq, value) => s"${ptq.key}=${value.getOrElse("")}"
      } ::
      minDesksByQueue.map {
        case (q, value) => s"${q}_min=${value.getOrElse("")}"
      } ::
      List(s"desks=$terminalDesks") ::
      slaByQueue.map {
        case (q, value) => s"${q}_sla=${value.getOrElse("")}"
      } :: Nil
  }.flatten.mkString("&")
}

object SimulationFormFields {
  def apply(terminal: Terminal, date: LocalDate, airportConfig: AirportConfig, slaConfigs: SlaConfigs): SimulationFormFields = {
    val processingTimes: Map[PaxTypeAndQueue, Option[Int]] = airportConfig.terminalProcessingTimes(terminal)
      .filterNot {
        case (paxTypeAndQueue: PaxTypeAndQueue, _) =>
          paxTypeAndQueue.queueType == Queues.Transfer
      }
      .view.mapValues(m => Option((m * 60).toInt)).toMap
    val minDesks: Map[Queue, Option[Int]] = airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
      case (q, (min, _)) => q -> Option(min.max)
    }
    val terminalDesks = airportConfig.desksByTerminal.getOrElse(terminal, 0)
    val egateBankSizes: IndexedSeq[Option[Int]] = airportConfig.eGateBankSizes.getOrElse(terminal, Iterable()).toIndexedSeq.map(Option(_))
    val slas: Map[Queue, Option[Int]] = slaConfigs.configForDate(SDate(date).millisSinceEpoch)
      .getOrElse(airportConfig.slaByQueue)
      .view.mapValues(Option(_)).toMap

    val egateOpeningHours: Seq[Int] = fullDay

    SimulationFormFields(
      terminal,
      date,
      Option(1.0),
      processingTimes,
      minDesks,
      terminalDesks,
      eGateBankSizes = egateBankSizes,
      slaByQueue = slas,
      crunchOffsetMinutes = 0,
      eGateOpenHours = egateOpeningHours
    )
  }
}

package drt.client.components.scenarios

import drt.shared.{AirportConfig, PaxTypeAndQueue, Queues, SimulationParams}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import drt.shared.SimulationParams.fullDay
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import drt.shared.dates.LocalDate
import uk.gov.homeoffice.drt.ports.{AirportConfig, PaxTypeAndQueue, Queues}

case class SimulationFormFields(terminal: Terminal,
                                date: LocalDate,
                                passengerWeighting: Option[Double],
                                processingTimes: Map[PaxTypeAndQueue, Option[Int]],
                                minDesks: Map[Queue, Option[Int]],
                                maxDesks: Map[Queue, Option[Int]],
                                eGateBanksSizes: IndexedSeq[Option[Int]],
                                slaByQueue: Map[Queue, Option[Int]],
                                crunchOffsetMinutes: Int,
                                eGateOpenHours: Seq[Int],
                               ) {
  val isValid: Boolean = {
    passengerWeighting.isDefined &&
      processingTimes.forall(_._2.isDefined) &&
      minDesks.forall(_._2.isDefined) &&
      maxDesks.forall(_._2.isDefined) &&
      eGateBanksSizes.forall(_.isDefined) &&
      slaByQueue.forall(_._2.isDefined)
  }

  def eGateOpenAt(hour: Int): Boolean = eGateOpenHours.contains(hour)

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
      s"eGateBankSizes=${eGateBanksSizes.map(_.getOrElse("")).mkString(",")}",
      s"crunchOffsetMinutes=$crunchOffsetMinutes",
      s"eGateOpenHours=${eGateOpenHours.mkString(",")}"
    ) ::
      processingTimes.map {
        case (ptq, value) => s"${ptq.key}=${value.getOrElse("")}"
      } ::
      minDesks.map {
        case (q, value) => s"${q}_min=${value.getOrElse("")}"
      } ::
      maxDesks.map {
        case (q, value) => s"${q}_max=${value.getOrElse("")}"
      } ::
      slaByQueue.map {
        case (q, value) => s"${q}_sla=${value.getOrElse("")}"
      } :: Nil
  }.flatten.mkString("&")
}

object SimulationFormFields {
  def apply(terminal: Terminal, date: LocalDate, airportConfig: AirportConfig): SimulationFormFields = {
    val processingTimes: Map[PaxTypeAndQueue, Option[Int]] = airportConfig.terminalProcessingTimes(terminal)
      .filterNot {
        case (paxTypeAndQueue: PaxTypeAndQueue, _) =>
          paxTypeAndQueue.queueType == Queues.Transfer
      }
      .mapValues(m => Option((m * 60).toInt))
    val minDesks: Map[Queue, Option[Int]] = airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
      case (q, (min, _)) => q -> Option(min.max)
    }
    val maxDesks: Map[Queue, Option[Int]] = airportConfig.minMaxDesksByTerminalQueue24Hrs(terminal).map {
      case (q, (_, max)) => q -> Option(max.max)
    }
    val egateBankSizes: IndexedSeq[Option[Int]] = airportConfig.eGateBankSizes.getOrElse(terminal, Iterable()).toIndexedSeq.map(Option(_))
    val slas: Map[Queue, Option[Int]] = airportConfig.slaByQueue.mapValues(Option(_))
    val egateOpeningHours: Seq[Int] = fullDay

    SimulationFormFields(
      terminal,
      date,
      Option(1.0),
      processingTimes,
      minDesks,
      maxDesks,
      eGateBanksSizes = egateBankSizes,
      slaByQueue = slas,
      crunchOffsetMinutes = 0,
      eGateOpenHours = egateOpeningHours
    )
  }
}

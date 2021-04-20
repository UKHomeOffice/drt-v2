package drt.client.components.scenarios

import drt.client.components.styles.DefaultFormFieldsStyle
import drt.client.modules.GoogleEventTracker
import drt.shared.Queues.Queue
import drt.shared.SimulationParams.fullDay
import drt.shared.Terminals.Terminal
import drt.shared._
import drt.shared.dates.LocalDate
import io.kinoplan.scalajs.react.material.ui.core._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import scalacss.ScalaCssReactImplicits

object SimulationParamsForm {
  def apply(terminal: Terminal, date: LocalDate, airportConfig: AirportConfig): SimulationParamsForm = {
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

    SimulationParamsForm(
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

case class SimulationParamsForm(terminal: Terminal,
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

  def toggleEgateHour(hour: Int): SimulationParamsForm = if (eGateOpenAt(hour))
    copy(eGateOpenHours = eGateOpenHours.filter(_ != hour))
  else
    copy(eGateOpenHours = eGateOpenHours :+ hour)

  def closeEgatesAllDay: SimulationParamsForm = copy(eGateOpenHours = Seq())

  def openEgatesAllDay: SimulationParamsForm = copy(eGateOpenHours = SimulationParams.fullDay)

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


object ScenarioSimulationComponent extends ScalaCssReactImplicits {

  implicit val stateReuse: Reusability[State] = Reusability.by_==[State]
  implicit val propsReuse: Reusability[Props] = Reusability.by_==[Props]

  val steps = List("Passenger numbers", "Processing Times", "Queue SLAs", "Configure Desk Availability")


  case class State(simulationParams: SimulationParamsForm, panelStatus: Map[String, Boolean]) {
    def isOpen(panel: String): Boolean = panelStatus.getOrElse(panel, false)

    def toggle(panel: String): State = copy(
      panelStatus = panelStatus + (panel -> !isOpen(panel))
    )
  }

  case class Props(date: LocalDate, terminal: Terminal, airportConfig: AirportConfig)

  private val component = ScalaComponent.builder[Props]("SimulationComponent")
    .initialStateFromProps(p =>
      State(SimulationParamsForm(p.terminal, p.date, p.airportConfig), Map())
    )
    .render_PS {

      (props, state) =>

        <.div(
          <.h2("Arrival Scenario Simulation"),
          MuiPaper()(
            DefaultFormFieldsStyle.simulation,
            MuiGrid(direction = MuiGrid.Direction.row, container = true, spacing = 16)(
              MuiGrid(item = true, xs = 2)(
                ScenarioSimulationFormComponent(props.date, props.terminal, props.airportConfig)
              ),
              MuiGrid(item = true, xs = 10)(
                SimulationChartComponent(state.simulationParams, props.airportConfig, props.terminal)
              )
            )
          )
        )
    }
    .configure(Reusability.shouldComponentUpdate)
    .componentDidMount(_ => Callback {
      GoogleEventTracker.sendPageView(s"Arrival Simulations Page")
    }).build

  def apply(date: LocalDate, terminal: Terminal, airportConfig: AirportConfig, portState: PortState): VdomElement =
    component(Props(date, terminal, airportConfig))
}



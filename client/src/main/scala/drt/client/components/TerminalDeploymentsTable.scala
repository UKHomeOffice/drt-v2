package drt.client.components

import diode.data.{Pot, Ready}
import diode.react._
import drt.client.TableViewUtils
import drt.client.TableViewUtils._
import drt.client.logger._
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel.QueueCrunchResults
import drt.client.services._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared.Simulations.QueueSimulationResult
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.component.builder.Builder
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.{TableHeaderCell, TableRow}

import scala.collection.immutable.{Map, NumericRange, Seq}
import scala.scalajs.js.Date
import scala.util.Try

object TerminalDeploymentsTable {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  sealed trait QueueDeploymentsRow {
    def queueName: QueueName

    def timestamp: Long
  }

  case class QueuePaxRowEntry(timestamp: Long, queueName: QueueName, pax: Double) extends QueueDeploymentsRow

  case class QueueDeploymentsRowEntry(
                                       timestamp: Long,
                                       pax: Double,
                                       crunchDeskRec: Int,
                                       userDeskRec: DeskRecTimeslot,
                                       actualDeskRec: Option[Int] = None,
                                       waitTimeWithCrunchDeskRec: Int,
                                       waitTimeWithUserDeskRec: Int,
                                       actualWaitTime: Option[Int] = None,
                                       queueName: QueueName
                                     ) extends QueueDeploymentsRow

  case class TerminalDeploymentsRow(time: Long, queueDetails: List[QueueDeploymentsRow])

  case class Props(
                    terminalName: String,
                    items: List[TerminalDeploymentsRow],
                    flights: Pot[FlightsWithSplits],
                    airportConfig: AirportConfig,
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
                  )

  case class State(showActuals: Boolean = false)

  object jsDateFormat {

    def zeroPadTo2Digits(number: Int) = {
      if (number < 10)
        "0" + number
      else
        number.toString
    }

    def formatDate(date: Date): String = {
      val formattedDate: String = date.getFullYear() + "-" + zeroPadTo2Digits(date.getMonth() + 1) + "-" + zeroPadTo2Digits(date.getDate()) + " " + date.toLocaleTimeString().replaceAll(":00$", "")
      formattedDate
    }
  }

  def deskUnitLabel(queueName: QueueName): String = {
    queueName match {
      case "eGate" => "Banks"
      case _ => "Desks"
    }
  }

  def renderTerminalUserTable(terminalName: TerminalName, airportInfoConnectProxy: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                              flightsWithSplitsPot: Pot[FlightsWithSplits], rows: List[TerminalDeploymentsRow], airportConfig: AirportConfig): VdomElement = {
    <.div(
      TerminalDeploymentsTable(
        terminalName,
        rows,
        flightsWithSplitsPot,
        airportConfig,
        airportInfoConnectProxy
      ))
  }

  case class TerminalProps(
                            airportConfig: AirportConfig,
                            terminalName: String,
                            flightsWithSplitsPot: Pot[FlightsWithSplits],
                            simulationResult: Map[QueueName, QueueSimulationResult],
                            crunchResult: Map[QueueName, CrunchResult],
                            deployments: QueueStaffDeployments,
                            workloads: Workloads,
                            actualDesks: Map[QueueName, Map[Long, DeskStat]]
                          )

  def terminalDeploymentsComponent(props: TerminalProps) = {
    val airportWrapper = SPACircuit.connect(_.airportInfos)

    <.div(
      calculateTerminalDeploymentRows(props) match {
        case Nil => <.div("No rows yet")
        case rows =>
          renderTerminalUserTable(props.terminalName, airportWrapper, props.flightsWithSplitsPot, rows, props.airportConfig)
      }
    )
  }

  def calculateTerminalDeploymentRows(props: TerminalProps): List[TerminalDeploymentsRow] = {
    log.info(s"calculateTerminalDeploymentRows called ${props.terminalName}")
    val crv = props.crunchResult
    val srv = props.simulationResult
    val udr = props.deployments
    val terminalDeploymentRows: List[TerminalDeploymentsRow] =
      props.workloads.workloads.get(props.terminalName) match {
        case Some(terminalWorkloads) =>
          val tried: Try[List[TerminalDeploymentsRow]] = Try {
            val timestamps = props.workloads.timeStamps()
            val startFromMilli = WorkloadsHelpers.midnightBeforeNow()
            val minutesRangeInMillis: NumericRange[Long] = WorkloadsHelpers.minutesForPeriod(startFromMilli, 24)

            val paxLoad: Map[String, List[Double]] = WorkloadsHelpers.paxloadPeriodByQueue(terminalWorkloads, minutesRangeInMillis)
            val actDesksForTerminal = props.actualDesks

            TableViewUtils.terminalDeploymentsRows(props.terminalName, props.airportConfig, timestamps, paxLoad, crv, srv, udr, actDesksForTerminal)
          } recover {
            case f =>
              val terminalWorkloadsPprint = pprint.stringify(terminalWorkloads).take(1024)
              log.error(s"calculateTerminalDeploymentRows $f terminalWorkloads were: $terminalWorkloadsPprint", f.asInstanceOf[Exception])
              Nil
          }
          tried.get
        case None =>
          Nil
      }
    terminalDeploymentRows
  }

  case class RowProps(item: TerminalDeploymentsRow, index: Int,
                      terminalName: TerminalName,
                      flights: Pot[FlightsWithSplits],
                      airportConfig: AirportConfig,
                      airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                      showActuals: Boolean)

  val itemRow = ScalaComponent.builder[RowProps]("deploymentRow")
    .render_P((p) => renderRow(p))
    .build

  def renderRow(props: RowProps): TagOf[TableRow] = {
    val item = props.item
    val time = item.time
    val windowSize = 60000 * 15
    val flights: Pot[FlightsWithSplits] = props.flights.map(flights =>
      flights.copy(flights = flights.flights.filter(f => time <= f.apiFlight.PcpTime && f.apiFlight.PcpTime <= (time + windowSize))))

    val formattedDate: String = SDate(MilliDate(item.time)).toHoursAndMinutes()
    val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = props.airportInfos
    val airportInfoPopover = FlightsPopover(formattedDate, flights, airportInfo)

    val queueRowCells: Seq[TagMod] = item.queueDetails.collect {
      case (q: QueueDeploymentsRowEntry) => {
        val dangerWait = if (q.waitTimeWithUserDeskRec > props.airportConfig.slaByQueue.getOrElse(q.queueName, 0)) "table-danger" else ""

        def qtd(xs: TagMod*): TagMod = <.td((^.className := queueColour(q.queueName)) :: xs.toList: _*)

        def qtdActuals(xs: TagMod*): TagMod = <.td((^.className := queueActualsColour(q.queueName)) :: xs.toList: _*)

        val queueCells = Seq(
          qtd(q.pax),
          qtd(^.title := s"Rec: ${q.crunchDeskRec}", q.userDeskRec.deskRec),
          qtd(^.cls := dangerWait, q.waitTimeWithUserDeskRec))

        if (props.showActuals) {
          val actDesks: String = q.actualDeskRec.map(act => s"$act").getOrElse("-")
          val actWaits: String = q.actualWaitTime.map(act => s"$act").getOrElse("-")

          queueCells ++ Seq(qtdActuals(actDesks), qtdActuals(actWaits))
        }
        else queueCells
      }
    }.flatten

    val transferCells: TagMod = item.queueDetails.collect {
      case q: QueuePaxRowEntry => <.td(^.className := queueColour(q.queueName), q.pax)
    }.toTagMod

    val staffRequiredQueues: Seq[QueueDeploymentsRowEntry] = item.queueDetails.collect { case q: QueueDeploymentsRowEntry => q }
    val totalRequired: Int = staffRequiredQueues.map(_.crunchDeskRec).sum
    val totalDeployed: Int = staffRequiredQueues.map(_.userDeskRec.deskRec).sum
    val ragClass = totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => ""
    }
    import JSDateConversions._
    val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(item.time), SDate(item.time).addHours(1), "left", "-")()
    val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(item.time), SDate(item.time).addHours(1), "left", "+")()
    val queueRowCellsWithTotal: List[html_<^.TagMod] = (queueRowCells :+
      <.td(^.className := s"total-deployed $ragClass", totalRequired) :+
      <.td(^.className := s"total-deployed $ragClass staff-adjustments", <.span(downMovementPopup, <.span(^.className := "deployed", totalDeployed), upMovementPopup)) :+ transferCells
      ).toList
    <.tr(<.td(^.cls := "date-field", airportInfoPopover()) :: queueRowCellsWithTotal: _*)
  }

  def queueColour(queueName: String): String = queueName + "-user-desk-rec"

  def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

  object Backend {
    def apply(scope: Builder.Step3[Props, State, Unit]#$, props: Props, state: State) = {
      val t = Try {
        def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

        val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalName).collect {
          case queueName if queueName != Queues.Transfer =>
            val colsToSpan = if (state.showActuals) 5 else 3
            qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
        }.toList

        val transferHeading: TagMod = props.airportConfig.queues(props.terminalName).collect {
          case (queueName@Queues.Transfer) => qth(queueName, queueDisplayName(queueName), ^.colSpan := 1)
        }.toList.toTagMod

        val headings: List[TagMod] = queueHeadings :+ <.th(^.className := "total-deployed", ^.colSpan := 2, "PCP") :+ transferHeading

        val defaultNumberOfQueues = 3
        val headOption = props.items.headOption
        val numQueues = headOption match {
          case Some(item) => item.queueDetails.length
          case None => defaultNumberOfQueues
        }
        val showActsClassSuffix = if (state.showActuals) "-with-actuals" else ""
        val colsClass = s"cols-$numQueues$showActsClassSuffix"

        val toggleShowActuals = (e: ReactEventFromInput) => {
          val newValue: Boolean = e.target.checked
          scope.modState(_.copy(showActuals = newValue))
        }
        <.div(
          if (props.airportConfig.hasActualDeskStats) {
            <.div(<.input.checkbox(^.checked := state.showActuals, ^.onChange ==> toggleShowActuals, ^.id := "show-actuals"),
              <.label(^.`for` := "show-actuals", "Show actual desks & wait times"))
          } else "",
          <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs $colsClass",
            <.thead(
              ^.display := "block",
              <.tr(<.th("") :: headings: _*),
              <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(props.airportConfig.queues(props.terminalName).toList, state): _*)),
            <.tbody(
              ^.display := "block",
              ^.overflow := "scroll",
              ^.height := "500px",
              props.items.zipWithIndex.map {
                case (item, index) => renderRow(RowProps(item, index, props.terminalName, props.flights, props.airportConfig, props.airportInfos, state.showActuals))
              }.toTagMod)))
      } recover {
        case t =>
          log.error(s"Failed to render deployment table $t", t.asInstanceOf[Exception])
          <.div(s"Can't render bcause $t")
      }

      t.get
    }


    val headerGroupStart = ^.borderLeft := "solid 1px #fff"

    private def subHeadingLevel2(queueNames: List[QueueName], state: State): List[TagMod] = {
      val queueSubHeadings: List[TagMod] = queueNames.collect {
        case queueName if queueName != Queues.Transfer => <.th(^.className := queueColour(queueName), "Pax") :: staffDeploymentSubheadings(queueName, state)
      }.flatten

      val transferSubHeadings: TagMod = queueNames.collect {
        case Queues.Transfer => <.th(^.className := queueColour(Queues.Transfer), "Pax") :: Nil
      }.flatten.toTagMod

      val list: List[TagMod] = queueSubHeadings :+
        <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks") :+
        <.th(^.className := "total-deployed", "Dep", ^.title := "Total staff deployed based on assignments entered") :+
        transferSubHeadings

      list
    }

    private def thHeaderGroupStart(title: String, xs: TagMod*): VdomTagOf[TableHeaderCell] = {
      <.th(headerGroupStart, title, xs.toTagMod)
    }
  }

  private def staffDeploymentSubheadings(queueName: QueueName, state: State) = {
    val queueColumnClass = queueColour(queueName)
    val queueColumnActualsClass = queueActualsColour(queueName)
    val headings = List(
      <.th(^.title := "Suggested deployment given available staff", s"Rec ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
      <.th(^.title := "Wait times with suggested deployments", "Est wait", ^.className := queueColumnClass))

    if (state.showActuals)
      headings ++ List(
        <.th(^.title := "Actual desks used", s"Act ${deskUnitLabel(queueName)}", ^.className := queueColumnActualsClass),
        <.th(^.title := "Actual wait times", "Act wait", ^.className := queueColumnActualsClass))
    else headings
  }

  def initState() = false


  implicit val reuse = Reusability.by((props: Props) => {
    props.items.map(tdr => {
      tdr.queueDetails.collect {
        case qdre: QueueDeploymentsRowEntry => qdre.hashCode()
      }
    })
  })

  implicit val stateReuse = Reusability.caseClass[State]

  private val component = ScalaComponent.builder[Props]("TerminalDeployments")
    .initialState[State](State(false))
    .renderPS((sc, p, s) => Backend(sc, p, s))
    .componentDidMount((p) => Callback.log(s"terminal deployments component didMount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminalName: String,
            rows: List[TerminalDeploymentsRow],
            flights: Pot[FlightsWithSplits],
            airportConfig: AirportConfig,
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) =
    component(Props(terminalName, rows, flights, airportConfig, airportInfos))

}

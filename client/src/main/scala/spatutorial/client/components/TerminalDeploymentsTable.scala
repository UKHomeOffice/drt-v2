package spatutorial.client.components

import diode.data.{Pot, Ready}
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html.TableHeaderCell
import spatutorial.client.TableViewUtils._
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.QueueCrunchResults
import spatutorial.client.services.HandyStuff.QueueStaffDeployments
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._

import scala.collection.immutable.{Map, Seq}
import scala.scalajs.js.Date

object TerminalDeploymentsTable {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class QueueDeploymentsRow(
                              timestamp: Long,
                              pax: Double,
                              crunchDeskRec: Int,
                              userDeskRec: DeskRecTimeslot,
                              waitTimeWithCrunchDeskRec: Int,
                              waitTimeWithUserDeskRec: Int,
                              queueName: QueueName
                            )

  case class TerminalDeploymentsRow(time: Long, queueDetails: Seq[QueueDeploymentsRow])

  case class Props(
                    terminalName: String,
                    items: Seq[TerminalDeploymentsRow],
                    flights: Pot[Flights],
                    airportConfigPot: Pot[AirportConfig],
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    stateChange: (QueueName, DeskRecTimeslot) => Callback
                  )

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

  def renderTerminalUserTable(terminalName: TerminalName, airportWrapper: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                              peMP: ModelProxy[PracticallyEverything], rows: List[TerminalDeploymentsRow], airportConfigPotMP: ModelProxy[Pot[AirportConfig]]): ReactElement = {
    <.div(
      TerminalDeploymentsTable(
        terminalName,
        rows,
        peMP().flights,
        airportConfigPotMP(),
        airportWrapper,
        (queueName: QueueName, deskRecTimeslot: DeskRecTimeslot) =>
          peMP.dispatch(UpdateDeskRecsTime(terminalName, queueName, deskRecTimeslot))
      ))
  }

  case class PracticallyEverything(
                                    airportInfos: Map[String, Pot[AirportInfo]],
                                    flights: Pot[Flights],
                                    simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]],
                                    workload: Pot[Workloads],
                                    queueCrunchResults: Map[TerminalName, QueueCrunchResults],
                                    userDeskRec: Map[TerminalName, QueueStaffDeployments],
                                    shiftsRaw: Pot[String]
                                  )

  def terminalDeploymentsComponent(terminalName: TerminalName) = {
    log.info(s"userdeskrecs for $terminalName")
    val airportFlightsSimresWorksQcrsUdrs = SPACircuit.connect(model =>
      PracticallyEverything(
        model.airportInfos,
        model.flights,
        model.simulationResult,
        model.workload,
        model.queueCrunchResults,
        model.staffDeploymentsByTerminalAndQueue,
        model.shiftsRaw
      ))

    val terminalUserDeskRecsRows: ReactConnectProxy[Option[Pot[List[TerminalDeploymentsRow]]]] = SPACircuit.connect(model => model.calculatedDeploymentRows.getOrElse(Map()).get(terminalName))
    val airportWrapper = SPACircuit.connect(_.airportInfos)
    val airportConfigPotRCP = SPACircuit.connect(_.airportConfig)

    airportFlightsSimresWorksQcrsUdrs(peMP => {
      <.div(
        terminalUserDeskRecsRows((rowsOptMP: ModelProxy[Option[Pot[List[TerminalDeploymentsRow]]]]) => {
          rowsOptMP() match {
            case None => <.div()
            case Some(rowsPot) =>
              <.div(
                rowsPot.renderReady(rows =>
                  airportConfigPotRCP(airportConfigPotMP => {
                    renderTerminalUserTable(terminalName, airportWrapper, peMP, rows, airportConfigPotMP)
                  })))
          }
        }),
        peMP().workload.renderPending(_ => <.div("Waiting for crunch results")))
    })
  }

  class Backend($: BackendScope[Props, Unit]) {

    def render(p: Props) = {
      log.info("%%%%%%%rendering table...")

      val style = bss.listGroup

      def renderItem(itemWithIndex: (TerminalDeploymentsRow, Int)) = {
        val item = itemWithIndex._1
        val index = itemWithIndex._2

        val time = item.time
        val windowSize = 60000 * 15
        val flights: Pot[Flights] = p.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
        val date: Date = new Date(item.time)
        val formattedDate: String = jsDateFormat.formatDate(date)
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfos
        val airportInfoPopover = FlightsPopover(formattedDate, flights, airportInfo)

        val queueRowCells = item.queueDetails.flatMap(
          (q: QueueDeploymentsRow) => {
            val warningClasses = if (q.waitTimeWithCrunchDeskRec < q.waitTimeWithUserDeskRec) "table-warning" else ""
            val dangerWait = p.airportConfigPot match {
              case Ready(airportConfig) =>
                if (q.waitTimeWithUserDeskRec > airportConfig.slaByQueue(q.queueName)) "table-danger"
              case _ =>
                ""
            }

            def qtd(xs: TagMod*) = <.td((^.className := queueColour(q.queueName)) :: xs.toList: _*)

            Seq(
              qtd(q.pax),
              qtd(q.userDeskRec.deskRec),
              qtd(^.cls := dangerWait + " " + warningClasses, q.waitTimeWithUserDeskRec + " mins"))
          }
        ).toList
        val totalRequired = item.queueDetails.map(_.crunchDeskRec).sum
        val totalDeployed = item.queueDetails.map(_.userDeskRec.deskRec).sum
        val ragClass = totalRequired.toDouble / totalDeployed match {
          case diff if diff >= 1 => "red"
          case diff if diff >= 0.75 => "amber"
          case _ => ""
        }
        val queueRowCellsWithTotal = queueRowCells :+
          <.td(^.className := s"total-deployed $ragClass", totalRequired) :+
          <.td(^.className := s"total-deployed $ragClass", totalDeployed)
        <.tr(<.td(^.cls := "date-field", airportInfoPopover()) :: queueRowCellsWithTotal: _*)
      }

      def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

      val headings = p.airportConfigPot.get.queues(p.terminalName).map {
        case (queueName) =>
          qth(queueName, <.h3(queueDisplayName(queueName)), ^.colSpan := 3)
      }.toList :+ <.th(^.className := "total-deployed", ^.colSpan := 2, <.h3("Totals"))

      val numQueues = p.items.head.queueDetails.length

      <.div(
        <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs cols-${numQueues}",
          <.thead(
            ^.display := "block",
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(p.airportConfigPot.get.queues(p.terminalName).toList): _*)),
          <.tbody(
            ^.display := "block",
            ^.overflow := "scroll",
            ^.height := "500px",
            p.items.zipWithIndex map renderItem)))
    }

    private def subHeadingLevel1 = {
      val subHeadingLevel1 = queueNameMappingOrder.flatMap(queueName => {
        val qc = queueColour(queueName)
        List(<.th("", ^.className := qc),
          thHeaderGroupStart(deskUnitLabel(queueName), ^.className := qc, ^.colSpan := 1),
          thHeaderGroupStart("Wait times", ^.className := qc, ^.colSpan := 1))
      }) :+ <.th(^.className := "total-deployed", "Staff", ^.colSpan := 2)
      subHeadingLevel1
    }

    def queueColour(queueName: String): String = queueName + "-user-desk-rec"

    val headerGroupStart = ^.borderLeft := "solid 1px #fff"

    private def subHeadingLevel2(queueNames: List[QueueName]) = {
      val subHeadingLevel2 = queueNames.flatMap(queueName => {
        val depls: List[ReactTagOf[TableHeaderCell]] = List(
          <.th(^.title := "Suggested deployment given available staff", deskUnitLabel(queueName), ^.className := queueColour(queueName)),
          <.th(^.title := "Suggested deployment given available staff", "Wait times", ^.className := queueColour(queueName))
        )

        <.th(^.className := queueColour(queueName), "Pax") :: depls
      })
      subHeadingLevel2 :+
        <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks") :+
        <.th(^.className := "total-deployed", "Avail", ^.title := "Total staff available based on shifts entered")
    }

    private def thHeaderGroupStart(title: String, xs: TagMod*): ReactTagOf[TableHeaderCell] = {
      <.th(headerGroupStart, title, xs)
    }
  }

  private val component = ReactComponentB[Props]("TerminalDeployments")
    .renderBackend[Backend]
    .build

  def apply(terminalName: String, items: Seq[TerminalDeploymentsRow], flights: Pot[Flights],
            airportConfigPot: Pot[AirportConfig],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            stateChange: (QueueName, DeskRecTimeslot) => Callback) =
    component(Props(terminalName, items, flights, airportConfigPot, airportInfos, stateChange))
}

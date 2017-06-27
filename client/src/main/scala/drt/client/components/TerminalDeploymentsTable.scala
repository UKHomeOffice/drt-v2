package drt.client.components

import diode.data.Pot
import diode.react._
import drt.client.TableViewUtils._
import drt.client.logger._
import drt.client.services.HandyStuff.QueueStaffDeployments
import drt.client.services.JSDateConversions.SDate
import drt.client.services.RootModel.QueueCrunchResults
import drt.client.services._
import drt.shared.FlightsApi.{FlightsWithSplits, QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html.TableHeaderCell

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

  case class TerminalDeploymentsRow(time: Long, queueDetails: List[QueueDeploymentsRow])

  case class Props(
                    terminalName: String,
                    rows: List[TerminalDeploymentsRow],
                    flights: Pot[FlightsWithSplits],
                    airportConfig: AirportConfig,
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]
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

  case class PracticallyEverything(
                                    airportInfos: Map[String, Pot[AirportInfo]],
                                    flights: Pot[FlightsWithSplits],
                                    simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]],
                                    workload: Pot[Workloads],
                                    queueCrunchResults: Map[TerminalName, QueueCrunchResults],
                                    userDeskRec: Map[TerminalName, QueueStaffDeployments],
                                    shiftsRaw: Pot[String]
                                  )

  case class TerminalProps(terminalName: String)

  def terminalDeploymentsComponent(props: TerminalProps) = {
    val airportFlightsSimresWorksQcrsUdrs = SPACircuit.connect(model => {
      PracticallyEverything(
        model.airportInfos,
        model.flightsWithSplitsPot,
        model.simulationResult,
        model.workloadPot,
        model.queueCrunchResults,
        model.staffDeploymentsByTerminalAndQueue,
        model.shiftsRaw
      )
    })

    val terminalUserDeskRecsRows = SPACircuit.connect(model =>
      model.calculatedDeploymentRows.getOrElse(Map()).get(props.terminalName)
    )
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
                    <.div(
                      airportConfigPotMP().renderReady(airportConfig =>

                        TerminalDeploymentsTable(
                          props.terminalName,
                          rows,
                          peMP().flights,
                          airportConfig,
                          airportWrapper
                        )
                      ))
                  })))
          }
        }),
        peMP().workload.renderPending(_ => <.div("Waiting for crunch results")))
    })
  }

  object Backend {
    def apply(props: Props) = {
      log.info("%%%%%%%rendering terminal deployments table...")

      val style = bss.listGroup

      def renderItem(itemWithIndex: (TerminalDeploymentsRow, Int)) = {
        val row = itemWithIndex._1
        val rowIndex = itemWithIndex._2

        val time = row.time
        val windowSize = 60000 * 15
        val flights: Pot[FlightsWithSplits] = props.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.apiFlight.PcpTime && f.apiFlight.PcpTime <= (time + windowSize))))

        val formattedDate: String = SDate(MilliDate(row.time)).toLocalDateTimeString()
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = props.airportInfos
        val airportInfoPopover = FlightsPopover(formattedDate, flights, airportInfo)

        val queueRowCells = row.queueDetails.flatMap(
          (q: QueueDeploymentsRow) => {
            val warningClasses = if (q.waitTimeWithCrunchDeskRec < q.waitTimeWithUserDeskRec) "table-warning" else ""
            val dangerWait = if (q.waitTimeWithUserDeskRec > props.airportConfig.slaByQueue(q.queueName)) "table-danger" else ""

            def qtd(xs: TagMod*) = <.td((^.className := queueColour(q.queueName)) :: xs.toList: _*)

            Seq(
              qtd(q.pax),
              <.td((^.className := queueColour(q.queueName)), ^.title:=s"Rec: ${q.crunchDeskRec}", q.userDeskRec.deskRec),
              <.td((^.className := queueColour(q.queueName)), ^.cls :=  dangerWait + " " + warningClasses, ^.title:=s"Rec: ${q.waitTimeWithCrunchDeskRec}", q.waitTimeWithUserDeskRec))
          }
        )
        val totalRequired = row.queueDetails.map(_.crunchDeskRec).sum
        val totalDeployed = row.queueDetails.map(_.userDeskRec.deskRec).sum
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

      val headings = props.airportConfig.queues(props.terminalName).map {
        case (queueName) =>
          qth(queueName, <.h3(queueDisplayName(queueName)), ^.colSpan := 3)
      }.toList :+ <.th(^.className := "total-deployed", ^.colSpan := 2, <.h3("Totals"))

      val defaultNumberOfQueues = 3
      val headOption = props.rows.headOption
      val numQueues = headOption match {
        case Some(item) => item.queueDetails.length
        case None =>
          defaultNumberOfQueues
      }

      <.div(
        <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs cols-${numQueues}",
          <.thead(
            ^.display := "block",
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(props.airportConfig.queues(props.terminalName).toList): _*)),
          <.tbody(
            ^.display := "block",
            ^.overflow := "scroll",
            ^.height := "500px",
            props.rows.zipWithIndex.map(renderItem).toTagMod
          )))
    }

    def queueColour(queueName: String): String = queueName + "-user-desk-rec"

    val headerGroupStart = ^.borderLeft := "solid 1px #fff"

    private def subHeadingLevel2(queueNames: List[QueueName]) = {
      val subHeadingLevel2 = queueNames.flatMap(queueName => {
        val depls: List[VdomTagOf[TableHeaderCell]] = List(
          <.th(^.title := "Suggested deployment given available staff", deskUnitLabel(queueName), ^.className := queueColour(queueName)),
          <.th(^.title := "Suggested deployment given available staff", "Wait times", ^.className := queueColour(queueName))
        )

        <.th(^.className := queueColour(queueName), "Pax") :: depls
      })
      subHeadingLevel2 :+
        <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks") :+
        <.th(^.className := "total-deployed", "Deployed", ^.title := "Total staff deployed based on assignments entered")
    }

    private def thHeaderGroupStart(title: String, xs: TagMod*): VdomTagOf[TableHeaderCell] = {
      <.th(headerGroupStart, title, xs.toTagMod)
    }
  }

  implicit val deskRecTimeslotReuse = Reusability.caseClass[DeskRecTimeslot]
  implicit val doubleReuse = Reusability.double(1)
  implicit val queueRowReuse = Reusability.caseClassExcept[QueueDeploymentsRow]('timestamp, 'crunchDeskRec, 'waitTimeWithCrunchDeskRec, 'queueName)
  implicit val terminalReuse = Reusability.caseClassExcept[TerminalDeploymentsRow]('time)
  implicit val propsReuse = Reusability.caseClassExcept[Props]('terminalName, 'flights, 'airportConfig, 'airportInfos)

  private val component = ScalaComponent.builder[Props]("TerminalDeployments")
    .renderP((_$, props) => Backend(props))
//    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(terminalName: String, rows: List[TerminalDeploymentsRow], flights: Pot[FlightsWithSplits],
            airportConfig: AirportConfig,
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) =
    component(Props(terminalName, rows, flights, airportConfig, airportInfos))
}

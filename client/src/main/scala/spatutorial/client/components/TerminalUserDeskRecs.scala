package spatutorial.client.components

import diode.data.{Pot, Ready}
import diode.react._
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html.{Div, TableCell, TableHeaderCell}
import spatutorial.client.TableViewUtils
import spatutorial.client.logger._
import spatutorial.client.modules.FlightsView
import spatutorial.client.services.HandyStuff.QueueUserDeskRecs
import spatutorial.client.services._
import spatutorial.shared._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}

import scala.collection.immutable.{Map, Seq}
import scala.scalajs.js.Date

object TerminalUserDeskRecs {

  case class Props(terminalName: TerminalName,
                   workloads: Map[QueueName, Seq[Int]],
                   userDeskRecs: Map[QueueName, UserDeskRecs])

  val component = ReactComponentB[Props]("TerminalUserDeskRecs")
    .render_P(props =>
      <.table(
        <.tr(<.td())
      )
    )
}

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


object TableTerminalDeskRecs {
  // shorthand for styles
  @inline private def bss = GlobalStyles.bootstrapStyles

  case class QueueDetailsRow(
                              timestamp: Long,
                              pax: Double,
                              crunchDeskRec: Int,
                              userDeskRec: DeskRecTimeslot,
                              waitTimeWithCrunchDeskRec: Int,
                              waitTimeWithUserDeskRec: Int,
                              queueName: QueueName
                            )

  case class TerminalUserDeskRecsRow(time: Long, queueDetails: Seq[QueueDetailsRow])


  case class Props(
                    terminalName: String,
                    items: Seq[TerminalUserDeskRecsRow],
                    flights: Pot[Flights],
                    airportConfigPot: Pot[AirportConfig],
                    airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                    stateChange: (QueueName, DeskRecTimeslot) => Callback
                  )

  case class HoverPopoverState(hovered: Boolean = false)

  def HoverPopover(trigger: String,
                   matchingFlights: Pot[Flights],
                   airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]]) = ReactComponentB[Unit]("HoverPopover")
    .initialState_P((p) =>
      HoverPopoverState()
    ).renderS((scope, state) => {
    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(s => s.copy(hovered = true))),
      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger)(
          airportInfos(airportInfo =>
            FlightsTable(FlightsView.Props(matchingFlights, airportInfo.value))))
      } else {
        trigger
      })
    popover
  }).build

  case class PracticallyEverything(
                                    airportInfos: Map[String, Pot[AirportInfo]],
                                    flights: Pot[Flights],
                                    simulationResult: Map[TerminalName, Map[QueueName, Pot[SimulationResult]]],
                                    workload: Pot[Workloads],
                                    queueCrunchResults: Map[TerminalName, Map[QueueName, Pot[(Pot[CrunchResult], Pot[UserDeskRecs])]]],
                                    userDeskRec: Map[TerminalName, QueueUserDeskRecs]
                                  )

  def buildTerminalUserDeskRecsComponent(terminalName: TerminalName) = {
    val airportFlightsSimresWorksQcrsUdrs = SPACircuit.connect(model =>
      PracticallyEverything(
        model.airportInfos,
        model.flights,
        model.simulationResult,
        model.workload,
        model.queueCrunchResults,
        model.userDeskRec
      ))
    val airportWrapper = SPACircuit.connect(_.airportInfos)
    val airportConfigPotRCP = SPACircuit.connect(_.airportConfig)
    airportFlightsSimresWorksQcrsUdrs(peMP => {
      <.div(
        <.h1(terminalName + " Desks"),
        peMP().workload.renderReady((workloads: Workloads) => {
          val crv = peMP().queueCrunchResults.getOrElse(terminalName, Map())
          val srv = peMP().simulationResult.getOrElse(terminalName, Map())
          val timestamps = workloads.timeStamps
          val paxloads: Map[String, List[Double]] = WorkloadsHelpers.paxloadsByQueue(peMP().workload.get.workloads(terminalName))
          val rows = TableViewUtils.terminalUserDeskRecsRows(timestamps, paxloads, crv, srv)
          airportConfigPotRCP(airportConfigPotMP => {
            <.div(
                TableTerminalDeskRecs(
                  terminalName,
                  rows,
                  peMP().flights,
                  airportConfigPotMP(),
                  airportWrapper,
                  (queueName: QueueName, deskRecTimeslot: DeskRecTimeslot) => {
                    peMP.dispatch(UpdateDeskRecsTime(terminalName, queueName, deskRecTimeslot))
                  }
                )
            )
          })
        }),
        peMP().workload.renderPending(_ => <.div("Waiting for crunch results")))
    })
  }

  class Backend($: BackendScope[Props, Unit]) {

    import jsDateFormat.formatDate

    def render(p: Props) = {
      log.info("%%%%%%%rendering table...")
      val style = bss.listGroup
      def renderItem(itemWithIndex: (TerminalUserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        val time = item.time
        val windowSize = 60000 * 15
        val flights: Pot[Flights] = p.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
        val date: Date = new Date(item.time)
        val formattedDate: String = formatDate(date)
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfos
        val popover = HoverPopover(formattedDate, flights, airportInfo)
        val fill = item.queueDetails.flatMap(
          (q: QueueDetailsRow) => {
            val warningClasses = if (q.waitTimeWithCrunchDeskRec < q.waitTimeWithUserDeskRec) "table-warning" else ""
            val dangerWait = p.airportConfigPot match {
              case Ready(airportConfig) =>
                if (q.waitTimeWithUserDeskRec > airportConfig.slaByQueue(q.queueName)) "table-danger"
              case _ =>
                ""
            }
            val hasChangeClasses = if (q.userDeskRec.deskRec != q.crunchDeskRec) "table-info" else ""
            Seq(
              <.td(q.pax),
              <.td(q.crunchDeskRec),
              <.td(
                ^.cls := hasChangeClasses,
                <.input.number(
                  ^.className := "desk-rec-input",
                  ^.value := q.userDeskRec.deskRec,
                  ^.onChange ==> ((e: ReactEventI) => p.stateChange(q.queueName, DeskRecTimeslot(q.userDeskRec.id, deskRec = e.target.value.toInt)))
                )),
              <.td(q.waitTimeWithCrunchDeskRec + " mins"),
              <.td(^.cls := dangerWait + " " + warningClasses, q.waitTimeWithUserDeskRec + " mins"))
          }
        ).toList
        <.tr(<.td(^.cls := "date-field", popover()) :: fill: _*)
      }
      val queueNames = TableViewUtils.queueNameMapping.values.toList
      val flatten: List[TagMod] = List.fill(3)(List(<.th(""), <.th("Desks", ^.colSpan := 2), <.th("Wait Times", ^.colSpan := 2))).flatten
      val fill: List[TagMod] = List.fill(3)(List(<.th("Pax"), <.th("Required"), <.th("Available"), <.th("With Reqs"), <.th("With Available"))).flatten
      <.table(^.cls := "table table-striped table-hover table-sm user-desk-recs",
        <.tbody(
          <.tr(<.th("") :: queueNames.map(queueName => <.th(<.h2(queueName), ^.colSpan := 5)): _*),
          <.tr(<.th("") :: flatten: _*),
          <.tr(<.th("Time") :: fill: _*),
          p.items.zipWithIndex map renderItem))
    }

  }

  private val component = ReactComponentB[Props]("TerminalUserDeskRecs")
    .renderBackend[Backend]
    .build

  def apply(terminalName: String, items: Seq[TerminalUserDeskRecsRow], flights: Pot[Flights],
            airportConfigPot: Pot[AirportConfig],
            airportInfos: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
            stateChange: (QueueName, DeskRecTimeslot) => Callback) =
    component(Props(terminalName, items, flights, airportConfigPot, airportInfos, stateChange))
}


package spatutorial.client.components

import diode.data.{Pot, Ready}
import diode.react
import diode.react._
import japgolly.scalajs.react.ReactComponentC.ConstProps
import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.ReactTagOf
import japgolly.scalajs.react.vdom.prefix_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.{TableCell, TableHeaderCell}
import spatutorial.client.TableViewUtils
import spatutorial.client.TableViewUtils._
import spatutorial.client.components.TableTerminalDeskRecs.TerminalUserDeskRecsRow
import spatutorial.client.logger._
import spatutorial.client.modules.Dashboard.QueueCrunchResults
import spatutorial.client.modules.FlightsView
import spatutorial.client.services.HandyStuff.QueueStaffDeployments
import spatutorial.client.services.JSDateConversions.SDate
import spatutorial.client.services._
import spatutorial.shared.FlightsApi.{Flights, QueueName, TerminalName}
import spatutorial.shared._

import scala.collection.immutable.{Map, NumericRange, Seq}
import scala.scalajs.js.Date
import scala.util.{Failure, Success}

object TerminalUserDeskRecs {

  case class Props(terminalName: TerminalName,
                   workloads: Map[QueueName, Seq[Int]],
                   userDeskRecs: Map[QueueName, DeskRecTimeSlots])

  val component = ReactComponentB[Props]("TerminalUserDeskRecs")
    .render_P(props =>
      <.table(
        <.tr(<.td())
      )
    )


  def timeIt[T](name: String)(f: => T): T = {
    val start = new Date()
    log.info(s"${name}: Starting timer at ${start}")
    val ret = f
    val end = new Date()
    log.info(s"${name} Trial done at ${end}")
    val timeTaken = (end.getTime() - start.getTime())
    log.info(s"${name} Time taken runs ${timeTaken}ms per run")
    ret
  }
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

  case class StaffMovementPopoverState(
                                        hovered: Boolean = false,
                                        reason: String = "",
                                        date: String = "",
                                        startTimeHours: Int = 0,
                                        startTimeMinutes: Int = 0,
                                        endTimeHours: Int = 0,
                                        endTimeMinutes: Int = 0,
                                        numberOfStaff: Int = 1
                                      )

  def staffMovementPopover(trigger: String, reason: String, startDate: SDate, endDate: SDate, bottom: String) = ReactComponentB[Unit]("staffMovementPopover")
    .initialState_P((p) => {
      StaffMovementPopoverState(
        reason = reason,
        date = f"${startDate.getDate}%02d/${startDate.getMonth}%02d/${startDate.getFullYear - 2000}%02d",
        startTimeHours = startDate.getHours(),
        startTimeMinutes = startDate.getMinutes(),
        endTimeHours = endDate.getHours(),
        endTimeMinutes = endDate.getMinutes())
    }).renderS((scope, state) => {

    def selectFromRange(range: Range, value: Int, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState) = {
      <.select(
        ^.defaultValue := value,
        ^.onChange ==> ((e: ReactEventI) => {
          val newValue: String = e.target.value
          scope.modState(callback(newValue))
        }),
        range.map(x => <.option(^.value := x, f"$x%02d")))
    }


    def trySaveMovement = (e: ReactEventI) => {
      val shiftTry = Shift(state.reason, state.date, f"${state.startTimeHours}%02d:${state.startTimeMinutes}%02d", f"${state.endTimeHours}%02d:${state.endTimeMinutes}%02d", s"-${state.numberOfStaff.toString}")
      shiftTry match {
        case Success(shift) =>
          for (movement <- StaffMovements.shiftsToMovements(Seq(shift))) yield {
            SPACircuit.dispatch(AddStaffMovement(movement))
            log.info(s"Dispatched AddStaffMovement(${movement}")
          }
          scope.modState(_.copy(hovered = false))
        case Failure(e) =>
          log.info("Invalid shift")
          scope.modState(_.copy(hovered = true))
      }
    }


    val popover = <.div(
      ^.onMouseEnter ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = true))),
      //      ^.onMouseLeave ==> ((e: ReactEvent) => scope.modState(_.copy(hovered = false))),
      if (state.hovered) {
        PopoverWrapper(trigger = trigger, className = "staff-movement-popover", position = bottom)({
          def labelledInput(labelText: String, value: String, callback: (String) => (StaffMovementPopoverState) => StaffMovementPopoverState): ReactTagOf[html.Div] = {
            popoverFormRow(labelText, <.input.text(^.value := value, ^.onChange ==> ((e: ReactEventI) => {
              val newValue: String = e.target.value
              scope.modState(callback(newValue))
            })))
          }

          def popoverFormRow(label: String, xs: TagMod*) = {
            <.div(^.className := "form-group row",
              <.label(label, ^.className := "col-sm-2 col-form-label"),
              <.div(
                ^.className := "col-sm-10",
                xs))
          }

          <.div(^.className := "container", ^.key := "IS81",
            labelledInput("Reason", state.reason, (v: String) => (s: StaffMovementPopoverState) => s.copy(reason = v)),
            labelledInput("Date", state.date, (v: String) => (s: StaffMovementPopoverState) => s.copy(date = v)),
            popoverFormRow("Start time",
              selectFromRange(
                0 to 23, startDate.getHours(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeHours = v.toInt)
              ), ":",
              selectFromRange(
                0 to 59,
                startDate.getMinutes(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(startTimeMinutes = v.toInt))
            ),
            popoverFormRow("End time",
              selectFromRange(
                0 to 23, endDate.getHours(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeHours = v.toInt)
              ), ":",
              selectFromRange(
                0 to 59,
                endDate.getMinutes(),
                (v: String) => (s: StaffMovementPopoverState) => s.copy(endTimeMinutes = v.toInt))
            ),
            popoverFormRow("Number of staff", <.input.number(^.value := state.numberOfStaff.toString, ^.onChange ==> ((e: ReactEventI) => {
              val newValue: String = e.target.value
              scope.modState((s: StaffMovementPopoverState) => s.copy(numberOfStaff = newValue.toInt))
            }))),

            <.div(^.className := "form-group-row",
              <.div(^.className := "col-sm-2"),
              <.div(^.className := "offset-sm-2 col-sm-10 btn-toolbar",
                <.button("Save", ^.className := "btn btn-primary", ^.onClick ==> trySaveMovement),
                <.button("Cancel", ^.className := "btn btn-default", ^.onClick ==> ((e: ReactEventI) => {
                  scope.modState(_.copy(hovered = false))
                }))
              )
            )
          )
        })
      } else {
        <.div(^.className := "popover-trigger", trigger)
      })
    popover
  }).build

  def renderTerminalUserTable(terminalName: TerminalName, airportWrapper: ReactConnectProxy[Map[String, Pot[AirportInfo]]],
                              peMP: ModelProxy[PracticallyEverything], rows: List[TerminalUserDeskRecsRow], airportConfigPotMP: ModelProxy[Pot[AirportConfig]]): ReactElement = {
    <.div(
      TableTerminalDeskRecs(
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

  def buildTerminalUserDeskRecsComponent(terminalName: TerminalName) = {
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

    val terminalUserDeskRecsRows: ReactConnectProxy[Option[Pot[List[TerminalUserDeskRecsRow]]]] = SPACircuit.connect(model => model.calculatedRows.getOrElse(Map()).get(terminalName))
    val airportWrapper = SPACircuit.connect(_.airportInfos)
    val airportConfigPotRCP = SPACircuit.connect(_.airportConfig)

    airportFlightsSimresWorksQcrsUdrs(peMP => {
      <.div(
        <.h1(terminalName + " Desks"),
        terminalUserDeskRecsRows((rowsOptMP: ModelProxy[Option[Pot[List[TerminalUserDeskRecsRow]]]]) => {
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


    import jsDateFormat.formatDate


    def render(p: Props) = {
      log.info("%%%%%%%rendering table...")


      val style = bss.listGroup


      def userDeskRecOverride(q: QueueDetailsRow, qtd: (TagMod *) => ReactTagOf[TableCell], hasChangeClasses: QueueName) = {
        qtd(
          ^.cls := hasChangeClasses,
          <.input.number(
            ^.className := "desk-rec-input",
            ^.disabled := true,
            ^.value := q.userDeskRec.deskRec,
            ^.onChange ==> ((e: ReactEventI) => p.stateChange(q.queueName, DeskRecTimeslot(q.userDeskRec.timeInMillis, deskRec = e.target.value.toInt)))
          ))
      }

      def renderItem(itemWithIndex: (TerminalUserDeskRecsRow, Int)) = {
        val item = itemWithIndex._1
        val index = itemWithIndex._2

        val time = item.time
        val windowSize = 60000 * 15
        val flights: Pot[Flights] = p.flights.map(flights =>
          flights.copy(flights = flights.flights.filter(f => time <= f.PcpTime && f.PcpTime <= (time + windowSize))))
        val date: Date = new Date(item.time)
        val formattedDate: String = formatDate(date)
        val airportInfo: ReactConnectProxy[Map[String, Pot[AirportInfo]]] = p.airportInfos
        val airportInfoPopover = HoverPopover(formattedDate, flights, airportInfo)

        val queueRowCells = item.queueDetails.flatMap(
          (q: QueueDetailsRow) => {
            val warningClasses = if (q.waitTimeWithCrunchDeskRec < q.waitTimeWithUserDeskRec) "table-warning" else ""
            val dangerWait = p.airportConfigPot match {
              case Ready(airportConfig) =>
                if (q.waitTimeWithUserDeskRec > airportConfig.slaByQueue(q.queueName)) "table-danger"
              case _ =>
                ""
            }

            def qtd(xs: TagMod*) = <.td(((^.className := queueColour(q.queueName)) :: xs.toList): _*)

            val hasChangeClasses = if (q.userDeskRec.deskRec != q.crunchDeskRec) "table-info" else ""
            Seq(
              qtd(q.pax),
              qtd(q.crunchDeskRec),
              userDeskRecOverride(q, qtd _, hasChangeClasses),
              qtd(q.waitTimeWithCrunchDeskRec + " mins"),
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

      def qth(queueName: String, xs: TagMod*) = <.th(((^.className := queueName + "-user-desk-rec") :: xs.toList): _*)

      val headings = queueNameMappingOrder.map {
        case (queueName) =>
          qth(queueName, <.h3(queueDisplayName(queueName)), ^.colSpan := 5)
      } :+ <.th(^.className := "total-deployed", ^.colSpan := 2, <.h3("Totals"))


      <.div(
        <.table(^.cls := "table table-striped table-hover table-sm user-desk-recs",
          <.thead(
            ^.display := "block",
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("") :: subHeadingLevel1: _*),
            <.tr(<.th("Time") :: subHeadingLevel2: _*)),
          <.tbody(
            ^.display := "block",
            ^.overflow := "scroll",
            ^.height := "500px",
            p.items.zipWithIndex map renderItem)))
    }

    private def subHeadingLevel1 = {

      val subHeadingLevel1 = queueNameMappingOrder.flatMap(queueName => {
        val deskUnitLabel = DeskRecsTable.deskUnitLabel(queueName)
        val qc = queueColour(queueName)
        List(<.th("", ^.className := qc),
          thHeaderGroupStart(deskUnitLabel, ^.className := qc, ^.colSpan := 2),
          thHeaderGroupStart("Wait times", ^.className := qc, ^.colSpan := 2))
      }) :+ <.th(^.className := "total-deployed", "Staff", ^.colSpan := 2)
      subHeadingLevel1
    }

    def queueColour(queueName: String): String = queueName + "-user-desk-rec"

    val headerGroupStart = ^.borderLeft := "solid 1px #fff"

    private def subHeadingLevel2 = {
      val subHeadingLevel2 = queueNameMappingOrder.flatMap(queueName => {
        val reqSug: List[ReactTagOf[TableHeaderCell]] = List(thHeaderGroupStart("Req", ^.className := queueColour(queueName)),
          <.th(^.title := "Suggested deployment given available staff", "Sug", ^.className := queueColour(queueName)))

        <.th(^.className := queueColour(queueName), "Pax") :: (reqSug ::: reqSug)
      })
      subHeadingLevel2 :+
        <.th(^.className := "total-deployed", "Req", ^.title := "Total staff required for desks") :+
        <.th(^.className := "total-deployed", "Avail", ^.title := "Total staff available based on shifts entered")
    }

    private def thHeaderGroupStart(title: String, xs: TagMod*): ReactTagOf[TableHeaderCell] = {
      <.th(headerGroupStart, title, xs)
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

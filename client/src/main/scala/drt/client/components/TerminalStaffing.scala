package drt.client.components

import java.util.UUID

import diode.data.Pot
import drt.client.actions.Actions._
import drt.client.components.FixedPoints._
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions._
import drt.client.services._
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.Terminals.Terminal
import drt.shared._
import japgolly.scalajs.react.{CtorType, _}
import japgolly.scalajs.react.component.Scala.{Component, Unmounted}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.{Anchor, Div, Table}
import org.scalajs.dom.raw.HTMLElement

import scala.collection.immutable.NumericRange
import scala.scalajs.js.Date
import scala.util.Success


object TerminalStaffing {
  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  val oneMinute = 60000L

  case class Props(
                    terminalName: Terminal,
                    potShifts: Pot[ShiftAssignments],
                    potFixedPoints: Pot[FixedPointAssignments],
                    potStaffMovements: Pot[Seq[StaffMovement]],
                    airportConfig: AirportConfig,
                    loggedInUser: Pot[LoggedInUser],
                    viewMode: ViewMode
                  )

  implicit val propsReuse: Reusability[Props] = Reusability.by(p => {
    (p.potShifts.getOrElse(ShiftAssignments.empty), p.potFixedPoints.getOrElse(FixedPointAssignments.empty), p.potStaffMovements.getOrElse(Seq[StaffMovement]())).hashCode()
  })

  def movementsForDay(movements: Seq[StaffMovement], day: SDateLike): Seq[StaffMovement] = {
    val startOfDayMillis = startOfDay(day).millisSinceEpoch
    val endOfDayMillis = endOfDay(day).millisSinceEpoch

    movements
      .groupBy(_.uUID)
      .filter { case (_, movementsPair) => areInWindow(startOfDayMillis, endOfDayMillis, movementsPair) }
      .values
      .flatten
      .toSeq
  }

  def areInWindow(startOfDayMillis: MillisSinceEpoch,
                  endOfDayMillis: MillisSinceEpoch,
                  movementsPair: Seq[StaffMovement]): Boolean = {
    val chronologicalMovementsPair = movementsPair.sortBy(_.time.millisSinceEpoch).toList

    chronologicalMovementsPair match {
      case singleMovement :: Nil =>
        val movementMillis = singleMovement.time.millisSinceEpoch
        isInWindow(startOfDayMillis, endOfDayMillis, movementMillis)

      case start :: end :: Nil =>
        val firstInWindow = isInWindow(startOfDayMillis, endOfDayMillis, start.time.millisSinceEpoch)
        val lastInWindow = isInWindow(startOfDayMillis, endOfDayMillis, end.time.millisSinceEpoch)
        firstInWindow || lastInWindow

      case _ => false
    }
  }

  def isInWindow(startOfDayMillis: MillisSinceEpoch,
                 endOfDayMillis: MillisSinceEpoch,
                 movementMillis: MillisSinceEpoch): Boolean = {
    startOfDayMillis <= movementMillis && movementMillis <= endOfDayMillis
  }

  class Backend() {
    def render(props: Props): VdomTagOf[Div] = <.div(
      props.potShifts.render(shifts => {
        props.potFixedPoints.render(fixedPoints => {
          props.potStaffMovements.render((movements: Seq[StaffMovement]) => {
            <.div(
              <.div(^.className := "container",
                <.div(^.className := "col-md-3", FixedPointsEditor(FixedPointsProps(FixedPointAssignments(fixedPoints.forTerminal(props.terminalName)), props.airportConfig, props.terminalName, props.loggedInUser))),
                <.div(^.className := "col-md-4", movementsEditor(movementsForDay(movements, props.viewMode.time), props.terminalName))
              ),
              <.div(^.className := "container",
                <.div(^.className := "col-md-10", staffOverTheDay(movementsForDay(movements, props.viewMode.time), shifts, props.terminalName)))
            )
          })
        })
      })
    )

    def filterByTerminal(fixedPoints: String, terminalName: String): String = fixedPoints
      .split("\n")
      .filter { line =>
        val cells = line.split(",").map(cell => cell.trim())
        cells(1) == terminalName
      }
      .mkString("\n")

    def staffOverTheDay(movements: Seq[StaffMovement],
                        shifts: ShiftAssignments,
                        terminalName: Terminal): VdomTagOf[Div] = {
      val terminalShifts = ShiftAssignments(shifts.forTerminal(terminalName))
      val staffWithShiftsAndMovementsAt = StaffMovements.terminalStaffAt(terminalShifts)(movements) _
      <.div(
        <.h2("Staff over the day"),
        staffingTableHourPerColumn(terminalName, daysWorthOf15Minutes(SDate.midnightThisMorning()), staffWithShiftsAndMovementsAt)
      )
    }

    def movementsEditor(movements: Seq[StaffMovement], terminalName: Terminal): VdomTagOf[Div] = {
      val terminalMovements = movements.filter(_.terminal == terminalName)
      <.div(<.h2("Movements"), movementsListTagMod(terminalMovements, terminalName))
    }

    case class FixedPointsProps(fixedPoints: FixedPointAssignments,
                                airportConfig: AirportConfig,
                                terminalName: Terminal,
                                loggedInUser: Pot[LoggedInUser])

    case class FixedPointsState(rawFixedPoints: String)

    object FixedPointsEditor {
      val component: Component[FixedPointsProps, FixedPointsState, Unit, CtorType.Props] = ScalaComponent.builder[FixedPointsProps]("FixedPointsEditor")
        .initialStateFromProps(props => FixedPointsState(StaffAssignmentHelper.fixedPointsFormat(props.fixedPoints)))
        .renderPS((scope, props, state) => {

          val defaultExamples = Seq("Roving Officer, 00:00, 23:59, 1")
          val examples = if (props.airportConfig.fixedPointExamples.nonEmpty)
            props.airportConfig.fixedPointExamples
          else
            defaultExamples

          <.div(
            <.h2("Miscellaneous Staff"),
            props.loggedInUser.render(loggedInUser => {
              if (loggedInUser.roles.contains(StaffEdit)) <.div(
                <.p("One entry per line with values separated by commas, e.g.:"),
                <.pre(<.div(examples.map(line => <.div(line)).toTagMod)),
                <.textarea(^.value := state.rawFixedPoints, ^.className := "staffing-editor"),
                ^.onChange ==> ((e: ReactEventFromInput) => {
                  log.info(s"fixed points changed")
                  val newRawFixedPoints = e.target.value
                  scope.modState(_.copy(rawFixedPoints = newRawFixedPoints))
                }),
                <.button("Save", ^.onClick ==> ((_: ReactEventFromInput) => {
                  val withTerminalName = addTerminalNameAndDate(state.rawFixedPoints, props.terminalName)
                  val newAssignments = FixedPointAssignments(StaffAssignmentParser(withTerminalName).parsedAssignments.toList.collect { case Success(sa) => sa })
                  GoogleEventTracker.sendEvent(withTerminalName, "Save Fixed Points", FixedPointAssignments(newAssignments.assignments.map(_.copy(createdBy = None))).toString)
                  Callback(SPACircuit.dispatch(SaveFixedPoints(newAssignments, props.terminalName)))
                }))
              ) else <.pre(state.rawFixedPoints, ^.className := "staffing-editor")
            }
            ))
        }).build

      def apply(props: FixedPointsProps): Unmounted[FixedPointsProps, FixedPointsState, Unit] = component(props)
    }

    def daysWorthOf15Minutes(startOfDay: SDateLike): NumericRange[Long] = {
      val timeMinPlusOneDay = startOfDay.addDays(1)
      startOfDay.millisSinceEpoch until timeMinPlusOneDay.millisSinceEpoch by (oneMinute * 15)
    }

    def staffingTableHourPerColumn(terminalName: Terminal,
                                   daysWorthOf15Minutes: NumericRange[Long],
                                   staffWithShiftsAndMovements: (Terminal, SDateLike) => Int): VdomTagOf[Table] =
      <.table(
        ^.className := "table table-striped table-xcondensed table-sm",
        <.tbody(
          daysWorthOf15Minutes.grouped(16).flatMap {
            hoursWorthOf15Minutes =>
              Seq(
                <.tr(^.key := s"hr-${hoursWorthOf15Minutes.headOption.getOrElse("empty")}", {
                  hoursWorthOf15Minutes.map((t: Long) => {
                    val d = new Date(t)
                    val display = f"${d.getHours()}%02d:${d.getMinutes()}%02d"
                    <.th(^.key := t, display)
                  }).toTagMod
                }),
                <.tr(^.key := s"vr-${hoursWorthOf15Minutes.headOption.getOrElse("empty")}",
                  hoursWorthOf15Minutes.map(t => {
                    <.td(^.key := t, s"${staffWithShiftsAndMovements(terminalName, SDate(t))}")
                  }).toTagMod
                ))
          }.toTagMod
        )
      )
  }

  def movementsListTagMod(terminalMovements: Seq[StaffMovement], terminalName: Terminal): TagOf[HTMLElement] = {
    if (terminalMovements.nonEmpty)
      <.ul(^.className := "list-unstyled", movementsLiElements(terminalMovements, terminalName).toTagMod)
    else
      <.p("No movements recorded")
  }

  def movementsLiElements(terminalMovements: Seq[StaffMovement], terminalName: Terminal): Seq[TagMod] = sortedMovements(terminalMovements).map {
    case (_, movementPair) =>
      labelAndLink(terminalName, movementPair) match {
        case Some((label, link)) => <.li(link, " ", <.span(^.`class` := "movement-display", label))
        case None => TagMod()
      }
  }

  def sortedMovements(terminalMovements: Seq[StaffMovement]): Seq[(UUID, Seq[StaffMovement])] = terminalMovements
    .groupBy(_.uUID)
    .toSeq
    .sortBy {
      case (_, head :: _) => head.time.millisSinceEpoch
      case (_, _) => 0L
    }

  def labelAndLink(terminalName: Terminal,
                   movementPair: Seq[StaffMovement]): Option[(String, html_<^.VdomTagOf[Anchor])] =
    movementPair
      .toList
      .sortBy(_.time.millisSinceEpoch) match {
      case first :: second :: Nil => Option(Tuple2(MovementDisplay.displayPair(first, second), removeLink(terminalName, first)))
      case mm :: Nil => Option(Tuple2(MovementDisplay.displaySingle(mm), removeLink(terminalName, mm)))
      case x =>
        log.info(s"didn't get a pair: $x")
        None
    }

  def removeLink(terminal: Terminal, movement: StaffMovement): VdomTagOf[Anchor] =
    <.a(Icon.remove, ^.key := movement.uUID.toString, ^.onClick ==> ((_: ReactEventFromInput) =>
      Callback {
        GoogleEventTracker.sendEvent(terminal.toString, "Remove Staff Movement", movement.copy(createdBy = None).toString)
        SPACircuit.dispatch(RemoveStaffMovements(movement.uUID))
      }))

  def apply(props: Props): VdomElement = component(props)

  private val component = ScalaComponent.builder[Props]("TerminalStaffing")
    .renderBackend[Backend]
    .configure(Reusability.shouldComponentUpdate)
    .build

  object MovementDisplay {
    def toCsv(movement: StaffMovement): String = {
      s"${movement.terminal}, ${movement.reason}, ${displayDate(movement.time)}, ${displayTime(movement.time)}, ${movement.delta} staff"
    }

    def displayPair(start: StaffMovement, end: StaffMovement): String = {
      val startDate = displayDate(start.time)
      val endDate = displayDate(end.time)
      val startDateForDisplay = if (startDate != displayDate(SDate.now().millisSinceEpoch)) startDate else ""
      val endDateForDisplay = if (startDate != endDate) endDate else ""
      val reasonForDisplay = start.reason.replace(" start", "") match {
        case "" => " (no reason given) "
        case r => r
      }
      val createdBy = start.createdBy.getOrElse("unknown")
      s"${start.delta} @ $startDateForDisplay ${displayTime(start.time)} -> $endDateForDisplay ${displayTime(end.time)} $reasonForDisplay by $createdBy"
    }

    def displaySingle(movement: StaffMovement): String = {
      val startDate = displayDate(movement.time)
      val startDateForDisplay = if (startDate != displayDate(SDate.now().millisSinceEpoch)) startDate else ""
      val reasonForDisplay = movement.reason.replace(" start", "") match {
        case "" => " - "
        case r => r
      }
      val createdBy = movement.createdBy.getOrElse("unknown")
      s"${movement.delta} @ $startDateForDisplay ${displayTime(movement.time)} -> ongoing $reasonForDisplay by $createdBy"
    }

    def displayTime(time: MilliDate): String = {
      val startDate: SDateLike = SDate(time)
      f"${startDate.getHours()}%02d:${startDate.getMinutes()}%02d"
    }

    def displayDate(time: MilliDate): String = {
      val startDate: SDateLike = SDate(time)
      f"${startDate.getDate()}%02d/${startDate.getMonth()}%02d/${startDate.getFullYear - 2000}%02d"
    }
  }

}

object FixedPoints {
  def removeTerminalNameAndDate(rawFixedPoints: String): String = {
    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withTerminal = line.split(",").toList.map(_.trim)
      val withOutTerminal = withTerminal match {
        case fpName :: _ :: _ :: tail => fpName.toString :: tail
        case _ => Nil
      }
      withOutTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }

  def addTerminalNameAndDate(rawFixedPoints: String, terminalName: Terminal): String = {
    val today: SDateLike = SDate.midnightThisMorning()
    val todayString = today.ddMMyyString

    val lines = rawFixedPoints.split("\n").toList.map(line => {
      val withoutTerminal = line.split(",").toList.map(_.trim)
      val withTerminal = withoutTerminal match {
        case fpName :: tail => fpName.toString :: terminalName.toString :: todayString :: tail
        case _ => Nil
      }
      withTerminal.mkString(", ")
    })
    lines.mkString("\n")
  }
}

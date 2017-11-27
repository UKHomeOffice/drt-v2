package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{ViewDeps, ViewRecs, ViewType, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.services.JSDateConversions
import drt.shared.CrunchApi.{CrunchMinute, CrunchState, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom
import org.scalajs.dom.raw.Node
import org.scalajs.dom.{DOMList, Element, Event, NodeListOf}

import scala.util.{Success, Try}


object TerminalDesksAndQueuesRow {

  def ragStatus(totalRequired: Int, totalDeployed: Int): String = {
    totalRequired.toDouble / totalDeployed match {
      case diff if diff >= 1 => "red"
      case diff if diff >= 0.75 => "amber"
      case _ => ""
    }
  }

  case class Props(minuteMillis: MillisSinceEpoch,
                   queueMinutes: Seq[CrunchMinute],
                   staffMinutes: Seq[StaffMinute],
                   airportConfig: AirportConfig,
                   terminalName: TerminalName,
                   showActuals: Boolean,
                   viewType: ViewType)

  implicit val rowPropsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    (props.queueMinutes.hashCode, props.showActuals, props.viewType.hashCode)
  })

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P((props) => {
      val crunchMinutesByQueue = props.queueMinutes.map(qm => Tuple2(qm.queueName, qm)).toMap
      val queueTds = crunchMinutesByQueue.flatMap {
        case (qn, cm) =>
          val paxLoadTd = <.td(^.className := queueColour(qn), s"${Math.round(cm.paxLoad)}")
          val queueCells = props.viewType match {
            case ViewDeps =>
              val ragClass = cm.deployedWait.getOrElse(0).toDouble / props.airportConfig.slaByQueue(qn) match {
                case pc if pc >= 1 => "red"
                case pc if pc >= 0.7 => "amber"
                case _ => ""
              }
              List(paxLoadTd,
                <.td(^.className := queueColour(qn), ^.title := s"Rec: ${cm.deskRec}", s"${cm.deployedDesks.getOrElse("-")}"),
                <.td(^.className := s"${queueColour(qn)} $ragClass", ^.title := s"With rec: ${cm.waitTime}", s"${cm.deployedWait.map(Math.round(_)).getOrElse("-")}"))
            case ViewRecs =>
              val ragClass = cm.waitTime.toDouble / props.airportConfig.slaByQueue(qn) match {
                case pc if pc >= 1 => "red"
                case pc if pc >= 0.7 => "amber"
              }
              List(paxLoadTd,
              <.td(^.className := queueColour(qn), ^.title := s"Dep: ${cm.deskRec}", s"${cm.deskRec}"),
              <.td(^.className := s"${queueColour(qn)} $ragClass", ^.title := s"With Dep: ${cm.waitTime}", s"${Math.round(cm.waitTime)}"))
          }

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")
            queueCells ++ Seq(<.td(^.className := queueActualsColour(qn), actDesks), <.td(^.className := queueActualsColour(qn), actWaits))
          } else queueCells
      }
      val fixedPoints = if (props.staffMinutes.nonEmpty) props.staffMinutes.map(_.fixedPoints).max else 0
      val movements = if (props.staffMinutes.nonEmpty) props.staffMinutes.map(_.movements).max else 0
      val available = if (props.staffMinutes.nonEmpty) props.staffMinutes.map(_.available).max else 0
      val totalRequired = crunchMinutesByQueue.map(_._2.deskRec).sum
      val totalDeployed = crunchMinutesByQueue.map(_._2.deployedDesks.getOrElse(0)).sum
      val ragClass = ragStatus(totalRequired, totalDeployed)
      import JSDateConversions._
      val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "-")()
      val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "+")()

      val pcpTds = List(
        <.td(^.className := s"non-pcp", fixedPoints),
        <.td(^.className := s"non-pcp", movements),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed $ragClass", totalDeployed),
        <.td(^.className := s"total-deployed $ragClass staff-adjustments", ^.colSpan := 2, <.span(downMovementPopup, <.span(^.className := "deployed", available), upMovementPopup)))
      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount((p) => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalDesksAndQueues {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def queueDisplayName(name: String): QueueName = Queues.queueDisplayNames.getOrElse(name, name)

  def queueColour(queueName: String): String = queueName + "-user-desk-rec"

  def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

  case class Props(crunchState: CrunchState, airportConfig: AirportConfig, terminalName: TerminalName)

  sealed trait ViewType

  case object ViewRecs extends ViewType

  case object ViewDeps extends ViewType

  case class State(showActuals: Boolean, viewType: ViewType)

  implicit val propsReuse: Reusability[Props] = Reusability.by((props: Props) => {
    val lastUpdatedCm = props.crunchState.crunchMinutes.map(_.lastUpdated)
    val lastUpdatedFs = props.crunchState.flights.map(_.lastUpdated)
    (lastUpdatedCm, lastUpdatedFs)
  })

  implicit val stateReuse: Reusability[State] = Reusability.by((state: State) => {
    state.showActuals
  })

  val component = ScalaComponent.builder[Props]("Loader")
    .initialState[State](State(showActuals = false, ViewDeps))
    .renderPS((scope, props, state) => {
      def groupCrunchMinutesBy15 = CrunchApi.groupCrunchMinutesByX(15) _

      def groupStaffMinutesBy15 = CrunchApi.groupStaffMinutesByX(15) _

      val queueNames = props.airportConfig.queues(props.terminalName).collect {
        case queueName: String if queueName != Queues.Transfer => queueName
      }

      def deskUnitLabel(queueName: QueueName): String = {
        queueName match {
          case "eGate" => "Banks"
          case _ => "Desks"
        }
      }

      def staffDeploymentSubheadings(queueName: QueueName) = {
        val queueColumnClass = queueColour(queueName)
        val queueColumnActualsClass = queueActualsColour(queueName)
        val headings = state.viewType match {
          case ViewDeps =>
            List(
              <.th(^.title := "Suggested deployment given available staff", s"Dep ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
              <.th(^.title := "Wait times with suggested deployments", "Est wait", ^.className := queueColumnClass))
          case ViewRecs =>
            List(
              <.th(^.title := "Recommendations to best meet SLAs", s"Rec ${deskUnitLabel(queueName)}", ^.className := queueColumnClass),
              <.th(^.title := "Wait times with recommendations", "Est wait", ^.className := queueColumnClass))
        }

        if (state.showActuals)
          headings ++ List(
            <.th(^.title := "Actual desks used", s"Act ${deskUnitLabel(queueName)}", ^.className := queueColumnActualsClass),
            <.th(^.title := "Actual wait times", "Act wait", ^.className := queueColumnActualsClass))
        else headings
      }

      def subHeadingLevel2(queueNames: Seq[QueueName]) = {
        val queueSubHeadings = queueNames.flatMap(queueName => <.th(^.className := queueColour(queueName), "Pax") :: staffDeploymentSubheadings(queueName)).toTagMod

        List(queueSubHeadings,
          <.th(^.className := "non-pcp", "Misc", ^.title := "Miscellaneous staff"),
          <.th(^.className := "non-pcp", "Moves", ^.title := "Staff movements"),
          <.th(^.className := "total-deployed", "Rec", ^.title := "Total staff recommended for desks"),
          <.th(^.className := "total-deployed", "Dep", ^.title := "Total staff deployed based on assignments entered"),
          <.th(^.className := "total-deployed", "Avail", ^.colSpan := 2, ^.title := "Total staff available based on staff entered"))
      }

      val showActsClassSuffix = if (state.showActuals) "-with-actuals" else ""
      val colsClass = s"cols-${queueNames.length}$showActsClassSuffix"

      def qth(queueName: String, xs: TagMod*) = <.th((^.className := queueName + "-user-desk-rec") :: xs.toList: _*)

      val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalName).collect {
        case queueName if queueName != Queues.Transfer =>
          val colsToSpan = if (state.showActuals) 5 else 3
          qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }.toList

      val headings: List[TagMod] = queueHeadings ++ List(
        <.th(^.className := "non-pcp", ^.colSpan := 2, ""),
        <.th(^.className := "total-deployed", ^.colSpan := 4, "PCP")
      )

      val terminalCrunchMinutes = groupCrunchMinutesBy15(
        CrunchApi.terminalMinutesByMinute(props.crunchState.crunchMinutes, props.terminalName),
        props.terminalName,
        Queues.queueOrder
      )
      val terminalStaffMinutes = groupStaffMinutesBy15(
        CrunchApi.terminalMinutesByMinute(props.crunchState.staffMinutes, props.terminalName),
        props.terminalName
      ).toMap

      val toggleShowActuals = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked
        scope.modState(_.copy(showActuals = newValue))
      }

      def toggleViewType(newViewType: ViewType) = (e: ReactEventFromInput) => {
        scope.modState(_.copy(viewType = newViewType))
      }

      def viewTypeControls(viewDepsClass: String, viewRecsClass: String): TagMod = {
        List(
          <.div(^.className := s"selector-control view-type-control $viewRecsClass",
            <.input.radio(^.checked := state.viewType == ViewRecs, ^.onChange ==> toggleViewType(ViewRecs), ^.id := "show-recs"),
            <.label(^.`for` := "show-recs", "Recommendations")
          ),
          <.div(^.className := s"selector-control view-type-control $viewDepsClass",
            <.input.radio(^.checked := state.viewType == ViewDeps, ^.onChange ==> toggleViewType(ViewDeps), ^.id := "show-deps"),
            <.label(^.`for` := "show-deps", "Available staff deployments")
          )).toTagMod
      }

      def showActualsClass = if (state.showActuals) "active-control" else ""

      def viewRecsClass = if (state.viewType == ViewRecs) "active-control" else ""

      def viewDepsClass = if (state.viewType == ViewDeps) "active-control" else ""

      def thing = VdomAttr("data-sticky")

      def floatingHeader = {
        <.div(
          ^.id := "toStick",
          ^.className := "container sticky",
          <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs",
            <.thead(
              <.tr(<.th("") :: headings: _*),
              <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames): _*)),
            <.tbody()
          ))
      }

      <.div(
        floatingHeader,
        <.div(
          if (props.airportConfig.hasActualDeskStats) {
            <.div(^.className := s"selector-control deskstats-control $showActualsClass",
              <.input.checkbox(^.checked := state.showActuals, ^.onChange ==> toggleShowActuals, ^.id := "show-actuals"),
              <.label(^.`for` := "show-actuals", "Show BlackJack Data")
            )
          } else "",
          viewTypeControls(viewDepsClass, viewRecsClass)
        ),
        <.table(^.cls := s"table table-striped table-hover table-sm user-desk-recs",
          <.thead(
            thing := "data-sticky",
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames): _*)),
          <.tbody(
            ^.id := "sticky-body",
            terminalCrunchMinutes.map {
              case (millis, minutes) =>
                val rowProps = TerminalDesksAndQueuesRow.Props(millis, minutes, terminalStaffMinutes.getOrElse(millis, List()), props.airportConfig, props.terminalName, state.showActuals, state.viewType)
                TerminalDesksAndQueuesRow(rowProps)
            }.toTagMod))
      )
    })
    .componentDidMount((_) => {
      Callback.log("TerminalDesksAndQueues did mount")

      def toIntOrElse(intString: String, stickyInitial: Int): Int = {
        Try {
          intString.toDouble.round.toInt
        } match {
          case Success(x) => x
          case _ => stickyInitial
        }
      }

      def handleStickyClass(top: Double, bottom: Double, mainWidth: Double, elements: NodeListSeq[Element], toStick: Element): Unit = {
        elements.foreach(sticky => {
          val stickyEnter = toIntOrElse(sticky.getAttribute("data-sticky-initial"), 0)
          val stickyExit = bottom.round.toInt

          if (top >= stickyEnter && top <= stickyExit)
            toStick.classList.add("sticky-show")
          else toStick.classList.remove("sticky-show")
        })
      }

      def setInitialHeights(elements: NodeListSeq[Element]): Unit = {
        elements.foreach(element => {
          val scrollTop = dom.document.documentElement.scrollTop
          val relativeTop = element.getBoundingClientRect().top
          val actualTop = relativeTop + scrollTop
          log.info(s"initial top: $relativeTop, scrollTop: $scrollTop")
          element.setAttribute("data-sticky-initial", actualTop.toString)
        })
      }

      val stickies: NodeListSeq[Element] = dom.document.querySelectorAll("[data-sticky]").asInstanceOf[NodeListOf[Element]]

      dom.document.addEventListener("scroll", (e: Event) => {
        val top = dom.document.documentElement.scrollTop // || dom.document.body.scrollTop
        val bottom = dom.document.documentElement.scrollHeight // || dom.document.body.scrollHeight
        val mainWidth = dom.document.querySelector("#sticky-body").getBoundingClientRect().width

        handleStickyClass(top, bottom, mainWidth, stickies, dom.document.querySelector("#toStick"))
      })

      Callback(setInitialHeights(stickies))

    })
    .build

  def apply(props: Props): VdomElement = component(props)

  implicit class NodeListSeq[T <: Node](nodes: DOMList[T]) extends IndexedSeq[T] {
    override def foreach[U](f: T => U): Unit = {
      for (i <- 0 until nodes.length) {
        f(nodes(i))
      }
    }

    override def length: Int = nodes.length

    override def apply(idx: Int): T = nodes(idx)
  }

}

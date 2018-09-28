package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlViewType}
import drt.client.actions.Actions.UpdateShowActualDesksAndQueues
import drt.client.components.TerminalDesksAndQueues.{NodeListSeq, ViewDeps, ViewRecs, ViewType, documentScrollHeight, documentScrollTop, queueActualsColour, queueColour}
import drt.client.logger.{Logger, LoggerFactory}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions._
import drt.client.services.{SPACircuit, ViewMode}
import drt.shared.CrunchApi.{CrunchMinute, CrunchState, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared._
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
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
                   staffMinute: StaffMinute,
                   airportConfig: AirportConfig,
                   terminalName: TerminalName,
                   showActuals: Boolean,
                   viewType: ViewType,
                   hasActualDeskStats: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser
                  )

  implicit val rowPropsReuse: Reusability[Props] = Reusability.by(_.hashCode())

  val component = ScalaComponent.builder[Props]("TerminalDesksAndQueuesRow")
    .render_P((props) => {
      val crunchMinutesByQueue = props.queueMinutes.filter(qm=> props.airportConfig.queues(props.terminalName).contains(qm.queueName)).map(qm => Tuple2(qm.queueName, qm)).toMap
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
                case _ => ""
              }
              List(paxLoadTd,
                <.td(^.className := queueColour(qn), ^.title := s"Dep: ${cm.deployedDesks.getOrElse("-")}", s"${cm.deskRec}"),
                <.td(^.className := s"${queueColour(qn)} $ragClass", ^.title := s"With Dep: ${cm.waitTime}", s"${Math.round(cm.waitTime)}"))
          }

          if (props.showActuals) {
            val actDesks: String = cm.actDesks.map(act => s"$act").getOrElse("-")
            val actWaits: String = cm.actWait.map(act => s"$act").getOrElse("-")
            queueCells ++ Seq(<.td(^.className := queueActualsColour(qn), actDesks), <.td(^.className := queueActualsColour(qn), actWaits))
          } else queueCells
      }
      val fixedPoints = props.staffMinute.fixedPoints
      val movements = props.staffMinute.movements
      val available = props.staffMinute.available
      val crunchMinutes = crunchMinutesByQueue.values.toSet
      val totalRequired = DesksAndQueues.totalRequired(props.staffMinute, crunchMinutes)
      val totalDeployed = DesksAndQueues.totalDeployed(props.staffMinute, crunchMinutes)
      val ragClass = ragStatus(totalRequired, available)
      val downMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "-", "Staff decrease...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "-", props.loggedInUser)()
      val upMovementPopup = StaffDeploymentsAdjustmentPopover(props.airportConfig.terminalNames, Option(props.terminalName), "+", "Staff increase...", SDate(props.minuteMillis), SDate(props.minuteMillis).addHours(1), "left", "+", props.loggedInUser)()

      def allowAdjustments: Boolean = props.viewMode.time.millisSinceEpoch > SDate.midnightThisMorning().millisSinceEpoch


      val pcpTds = List(
        <.td(^.className := s"non-pcp", fixedPoints),
        <.td(^.className := s"non-pcp", movements),
        <.td(^.className := s"total-deployed $ragClass", totalRequired),
        <.td(^.className := s"total-deployed", totalDeployed),
        if(allowAdjustments)
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(downMovementPopup, <.span(^.className := "deployed", available), upMovementPopup))
        else
          <.td(^.className := s"total-deployed staff-adjustments", ^.colSpan := 2, <.span(^.className := "deployed", available)))


      <.tr((<.td(SDate(MilliDate(props.minuteMillis)).toHoursAndMinutes()) :: queueTds.toList ++ pcpTds).toTagMod)
    })
    .componentDidMount(_ => Callback.log("TerminalDesksAndQueuesRow did mount"))
    .configure(Reusability.shouldComponentUpdate)
    .build

  def apply(props: Props): VdomElement = component(props)
}

object TerminalDesksAndQueues {

  val log: Logger = LoggerFactory.getLogger(getClass.getName)

  def queueDisplayName(name: String): QueueName = Queues.queueDisplayNames.getOrElse(name, name)

  def queueColour(queueName: String): String = queueName + "-user-desk-rec"

  def queueActualsColour(queueName: String): String = s"${queueColour(queueName)} actuals"

  case class Props(router: RouterCtl[Loc],
                   crunchState: CrunchState,
                   airportConfig: AirportConfig,
                   terminalPageTab: TerminalPageTabLoc,
                   showActuals: Boolean,
                   viewMode: ViewMode,
                   loggedInUser: LoggedInUser
                  )

  sealed trait ViewType

  case object ViewRecs extends ViewType

  case object ViewDeps extends ViewType

  case class State(showActuals: Boolean, viewType: ViewType)

  val component = ScalaComponent.builder[Props]("Loader")
    .initialStateFromProps(p => {
      State(showActuals = p.airportConfig.hasActualDeskStats && p.showActuals, p.terminalPageTab.viewType)
    })
    .renderPS((scope, props, state) => {
      def groupCrunchMinutesBy15 = CrunchApi.groupCrunchMinutesByX(15) _

      def groupStaffMinutesBy15 = CrunchApi.groupStaffMinutesByX(15) _

      val queueNames = props.airportConfig.queues(props.terminalPageTab.terminal).collect {
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

        if (props.airportConfig.hasActualDeskStats && state.showActuals)
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

      val queueHeadings: List[TagMod] = props.airportConfig.queues(props.terminalPageTab.terminal).collect {
        case queueName if queueName != Queues.Transfer =>
          val colsToSpan = if (state.showActuals) 5 else 3
          qth(queueName, queueDisplayName(queueName), ^.colSpan := colsToSpan, ^.className := "top-heading")
      }.toList

      val headings: List[TagMod] = queueHeadings ++ List(
        <.th(^.className := "non-pcp", ^.colSpan := 2, ""),
        <.th(^.className := "total-deployed", ^.colSpan := 4, "PCP")
      )

      val terminalCrunchMinutes = groupCrunchMinutesBy15(
        CrunchApi.terminalMinutesByMinute(props.crunchState.crunchMinutes, props.terminalPageTab.terminal),
        props.terminalPageTab.terminal,
        Queues.queueOrder
      )
      val staffMinutesByMillis = CrunchApi
        .terminalMinutesByMinute(props.crunchState.staffMinutes, props.terminalPageTab.terminal)
        .map {
          case (millis, minutes) => (millis, minutes.head)
        }
      val terminalStaffMinutes = groupStaffMinutesBy15(staffMinutesByMillis, props.terminalPageTab.terminal).toMap

      val toggleShowActuals = (e: ReactEventFromInput) => {
        val newValue: Boolean = e.target.checked

        SPACircuit.dispatch(UpdateShowActualDesksAndQueues(newValue))

        scope.modState(_.copy(showActuals = newValue))
      }

      def toggleViewType(newViewType: ViewType) = (e: ReactEventFromInput) => {
        GoogleEventTracker.sendEvent(s"${props.terminalPageTab.terminal}", "Desks & Queues", newViewType.toString)
        props.router.set(
          props.terminalPageTab.withUrlParameters(Array(UrlViewType(Option(newViewType))))
        )
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

      val dataStickyAttr = VdomAttr("data-sticky") := "data-sticky"

      val classesAttr = ^.cls := s"table table-striped table-hover table-sm user-desk-recs"

      def floatingHeader = {
        <.div(^.id := "toStick", ^.className := "container sticky",
          <.table(classesAttr,
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
        <.table(
          ^.id := "sticky",
          classesAttr,
          <.thead(
            dataStickyAttr,
            <.tr(<.th("") :: headings: _*),
            <.tr(<.th("Time", ^.className := "time") :: subHeadingLevel2(queueNames): _*)),
          <.tbody(
            ^.id := "sticky-body",
            terminalCrunchMinutes.map {
              case (millis, minutes) =>
                val rowProps = TerminalDesksAndQueuesRow.Props(
                  millis,
                  minutes,
                  terminalStaffMinutes.getOrElse(millis, StaffMinute.empty),
                  props.airportConfig,
                  props.terminalPageTab.terminal,
                  state.showActuals,
                  state.viewType,
                  props.airportConfig.hasActualDeskStats,
                  props.viewMode,
                  props.loggedInUser
                )
                TerminalDesksAndQueuesRow(rowProps)
            }.toTagMod))
      )
    })
    .componentDidMount(_ => StickyTableHeader("[data-sticky]"))
    .build

  def documentScrollTop: Double = Math.max(dom.document.documentElement.scrollTop, dom.document.body.scrollTop)

  def documentScrollHeight: Double = Math.max(dom.document.documentElement.scrollHeight, dom.document.body.scrollHeight)

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

object StickyTableHeader {
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
      val scrollTop = documentScrollTop
      val relativeTop = element.getBoundingClientRect().top
      val actualTop = relativeTop + scrollTop
      element.setAttribute("data-sticky-initial", actualTop.toString)
    })
  }

  def apply(selector: String): Callback = {

    val stickies: NodeListSeq[Element] = dom.document.querySelectorAll(selector).asInstanceOf[NodeListOf[Element]]

    dom.document.addEventListener("scroll", (e: Event) => {
      val top = documentScrollTop
      val bottom = documentScrollHeight
      Option(dom.document.querySelector("#sticky-body")).foreach(stickyBody => {
        val mainWidth = stickyBody.getBoundingClientRect().width
        handleStickyClass(top, bottom, mainWidth, stickies, dom.document.querySelector("#toStick"))
      })
    })

    Callback(setInitialHeights(stickies))
  }
}

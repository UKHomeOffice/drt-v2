package drt.client.components

import diode.UseValueEq
import drt.client.SPAMain
import drt.client.SPAMain.{Loc, TerminalPageTabLoc, UrlDateParameter}
import drt.client.modules.GoogleEventTracker
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{ForecastPeriodWithHeadlines, ForecastTimeSlot, MillisSinceEpoch}
import drt.shared.{Forecast, MilliDate}
import io.kinoplan.scalajs.react.material.ui.core.MuiButton._
import io.kinoplan.scalajs.react.material.ui.core.{MuiButton, MuiDivider}
import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons
import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.GetApp
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{Callback, CtorType, ReactEventFromInput, ScalaComponent}
import org.scalajs.dom.html.Select
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.time.SDateLike

object TerminalPlanningComponent {

  def getLastSunday(start: SDateLike): SDateLike = {
    val sunday = start.getLastSunday

    SDate(f"${sunday.getFullYear}-${sunday.getMonth}%02d-${sunday.getDate}%02dT00:00:00")
  }

  case class Props(forecastPeriod: ForecastPeriodWithHeadlines, page: TerminalPageTabLoc, router: RouterCtl[Loc]) extends UseValueEq {
    def hash: Int = {
      forecastPeriod.forecast.days.toList.map {
        case (_, slots) => slots.hashCode
      }
    }.hashCode
  }

  private val forecastWeeks: Seq[SDateLike] = (-4 to 30).map(w => getLastSunday(SDate.now()).addDays(w * 7))

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("TerminalForecast")
    .renderP { (_, props) =>
      val sortedDays = props.forecastPeriod.forecast.days.toList.sortBy(_._1)
      val byTimeSlot: Seq[List[Option[ForecastTimeSlot]]] = Forecast.periodByTimeSlotAcrossDays(props.forecastPeriod.forecast)

      def drawSelect(names: Seq[String], values: List[String], value: String): VdomTagOf[Select] = {
        <.select(^.className := "form-control", ^.value := value,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            props.router.set(props.page.withUrlParameters(UrlDateParameter(Option(SDate(e.target.value).toLocalDate.toISOString))))
          }),
          values.zip(names).map {
            case (value, name) => <.option(^.value := value, name)
          }.toTagMod)
      }

      val slotStartTimes = Forecast.timeSlotStartTimes(
        props.forecastPeriod.forecast,
        (millis: MillisSinceEpoch) => SDate(millis).toHoursAndMinutes
      )
      <.div(
        <.div(^.className := "terminal-content-header",
          <.div(^.className := "staffing-controls-wrapper",
            <.div(^.className := "staffing-controls-row",
              <.label(^.className := "staffing-controls-label", "Select week start day"),
              <.div(^.className := "staffing-controls-select",
                drawSelect(
                  forecastWeeks.map(_.ddMMyyString),
                  forecastWeeks.map(_.toISOString).toList,
                  defaultStartDate(props.page.dateFromUrlOrNow).toISOString)
              )
            ),
            MuiDivider()(),
            <.div(^.className := "staffing-controls-row",
              <.span(
                MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                  MuiIcons(GetApp)(fontSize = "small"),
                  "Export Headlines",
                  ^.className := "btn btn-link muiButton",
                  ^.href := SPAMain.absoluteUrl(s"export/headlines/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}"),
                  ^.target := "_blank"
                )),
              <.span(^.className := "planning-export",
                MuiButton(color = Color.default, variant = "outlined", size = "medium")(
                  MuiIcons(GetApp)(fontSize = "small"),
                  "Export Week",
                  ^.className := "btn btn-link muiButton",
                  ^.href := SPAMain.absoluteUrl(s"export/planning/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}"),
                  ^.target := "_blank"
                ))
            )
          ),
        ),
        <.h3("Headline Figures"),
        <.table(^.className := "headlines",
          <.thead(
            <.tr(
              <.th(^.className := "queue-heading"),
              props.forecastPeriod.headlines.queueDayHeadlines.map(_.day).toSet.toList.sorted.map(
                day => <.th(s"${SDate(MilliDate(day)).getDate}/${SDate(MilliDate(day)).getMonth}")
              ).toTagMod
            ), {
              val groupedByQ = props.forecastPeriod.headlines.queueDayHeadlines.groupBy(_.queue)
              Queues.queueOrder.flatMap(q => groupedByQ.get(q).map(qhls => <.tr(
                <.th(^.className := "queue-heading", s"${Queues.displayName(q)}"), qhls.toList.sortBy(_.day).map(qhl => <.td(qhl.paxNos)).toTagMod
              ))).toTagMod
            }, {
              val byDay = props.forecastPeriod.headlines.queueDayHeadlines.groupBy(_.day).toList
              List(
                <.tr(^.className := "total", <.th(^.className := "queue-heading", "Total Pax"), byDay.sortBy(_._1).map(hl => <.th(hl._2.map(_.paxNos).sum)).toTagMod),
                <.tr(^.className := "total", <.th(^.className := "queue-heading", "Workloads"), byDay.sortBy(_._1).map(hl => <.th(hl._2.map(_.workload).sum)).toTagMod)
              ).toTagMod
            }
          )
        ),
        <.h3("Total staff required at each hour of the day"),
        <.table(^.className := "forecast",
          <.thead(^.className := "sticky-top",
            <.tr(
              <.th(^.className := "heading"), sortedDays.map {
                case (day, _) =>
                  <.th(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(day)).getDate}/${SDate(MilliDate(day)).getMonth}")
              }.toTagMod
            ),
            <.tr(
              <.th(^.className := "heading", "Time"), sortedDays.flatMap(_ => List(<.th(^.className := "sub-heading", "Avail"), <.th(^.className := "sub-heading", "Rec"))).toTagMod
            )),
          <.tbody(
            byTimeSlot.zip(slotStartTimes).map {
              case (row, startTime) =>
                <.tr(
                  <.td(s"$startTime"),
                  row.flatMap {
                    case Some(col) =>
                      val ragClass = TerminalDesksAndQueuesRow.ragStatus(col.required, col.available)
                      List(<.td(^.className := ragClass, col.available), <.td(col.required))
                    case None =>
                      List(<.td(), <.td())
                  }.toTagMod
                )
            }.toTagMod
          )
        )
      )
    }
    .componentDidMount(p => Callback {
      GoogleEventTracker.sendPageView(s"${p.props.page.terminal}/planning/${defaultStartDate(p.props.page.dateFromUrlOrNow).toISODateOnly}")
    })
    .build

  def defaultStartDate(date: SDateLike): SDateLike = getLastSunday(date)

  def apply(props: Props): VdomElement = component(props)
}

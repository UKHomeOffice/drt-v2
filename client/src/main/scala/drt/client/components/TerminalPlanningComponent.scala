package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{ForecastPeriodWithHeadlines, ForecastTimeSlot}
import drt.shared.{MilliDate, Queues, SDateLike}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}
import org.scalajs.dom

import scala.collection.immutable.Seq

object TerminalPlanningComponent {

  def getLastSunday(start: SDateLike) = {
    val sunday = if (start.getDayOfWeek() == 7) start else start.addDays(-1 * start.getDayOfWeek())

    SDate(f"${sunday.getFullYear()}-${sunday.getMonth()}%02d-${sunday.getDate()}%02dT00:00:00")
  }

  case class Props(forecastPeriod: ForecastPeriodWithHeadlines, page: TerminalPageTabLoc, router: RouterCtl[Loc]) {

    def hash = {
      forecastPeriod.forecast.days.toList.map {
        case (day, slots) => slots.hashCode
      }
    }.hashCode
  }

  val forecastWeeks: Seq[SDateLike] = (0 to 18).map(w => getLastSunday(SDate.now()).addDays(w * 7))

  implicit val propsReuse = Reusability.by((_: Props).hash)

  val component = ScalaComponent.builder[Props]("TerminalForecast")
    .renderP((scope, props) => {
      val sortedDays = props.forecastPeriod.forecast.days.toList.sortBy(_._1)
      val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.transpose(_._2.take(96))

      def drawSelect(names: Seq[String], values: List[String], value: String) = {
        <.select(^.className := "form-control", ^.value := value.toString,
          ^.onChange ==> ((e: ReactEventFromInput) => {
            props.router.set(props.page.copy(date = Option(SDate(e.target.value).toLocalDateTimeString())))
          }),
          values.zip(names).map {
            case (value, name) => <.option(^.value := value.toString, name)
          }.toTagMod)
      }

      <.div(
        <.div(^.className := "form-group row planning-week",
          <.div(^.className := "col-sm-3 no-gutters", <.label(^.className := "col-form-label", "Select week start day")),
          <.div(^.className := "col-sm-2 no-gutters",
            drawSelect(
              forecastWeeks.map(_.ddMMyyString),
              forecastWeeks.map(_.toISOString()).toList,
              defaultStartDate(props.page.dateFromUrlOrNow).toISOString())
          )
        ),
        <.div(^.className := "export-links",
          <.a(
            "Export Headlines",
            ^.className := "btn btn-link",
            ^.href := s"${dom.window.location.pathname}/export/headlines/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}",
            ^.target := "_blank"
          ),
          <.a(
            "Export Week",
            ^.className := "btn btn-link",
            ^.href := s"${dom.window.location.pathname}/export/planning/${defaultStartDate(props.page.dateFromUrlOrNow).millisSinceEpoch}/${props.page.terminal}",
            ^.target := "_blank"
          )
        ),
        <.h3("Headline Figures"),
        <.table(^.className := "headlines",
          <.thead(
            <.tr(
              <.th(^.className := "queue-heading"),
              props.forecastPeriod.headlines.queueDayHeadlines.map(_.day).toList.sorted.map(
                day => <.th(s"${SDate(MilliDate(day)).getDate()}/${SDate(MilliDate(day)).getMonth()}")
              ).toTagMod
            ), {
              val groupedByQ = props.forecastPeriod.headlines.queueDayHeadlines.groupBy(_.queue)
              Queues.queueOrder.flatMap(q => groupedByQ.get(q).map(qhls => <.tr(
                <.th(^.className := "queue-heading", s"${Queues.queueDisplayNames.getOrElse(q, q)}"), qhls.toList.sortBy(_.day).map(qhl => <.td(qhl.paxNos)).toTagMod
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
        <.h3("Total desks required at each hour of the day"),
        <.table(^.className := "forecast",
          <.thead(
            <.tr(
              <.th(^.colSpan := 2, ^.className := "heading"), sortedDays.map {
                case (day, _) =>
                  <.th(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(day)).getDate()}/${SDate(MilliDate(day)).getMonth()}")
              }.toTagMod
            ),
            <.tr(
              <.th(^.colSpan := 2, ^.className := "heading", "Time"), sortedDays.flatMap(_ => List(<.th(^.className := "sub-heading", "Avail"), <.th(^.className := "sub-heading", "Rec"))).toTagMod
            )),
          <.tbody(
            byTimeSlot.map(row => {
              <.tr(
                <.td(^.colSpan := 2, SDate(MilliDate(row.head.startMillis)).toHoursAndMinutes()),
                row.flatMap(col => {
                  val ragClass = TerminalDesksAndQueuesRow.ragStatus(col.required, col.available)
                  List(<.td(^.className := ragClass, col.available), <.td(col.required))
                }).toTagMod
              )
            }).toTagMod
          )
        )
      )
    })
    .configure(Reusability.shouldComponentUpdate)
    .build

  def defaultStartDate(date: SDateLike) = getLastSunday(date)

  def apply(props: Props): VdomElement = component(props)
}

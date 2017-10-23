package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.CrunchApi.{ForecastPeriod, ForecastTimeSlot}
import drt.shared.{MilliDate, SDateLike}
import japgolly.scalajs.react.ScalaComponent
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.vdom.html_<^.{<, _}

object TerminalForecastComponent {

  def getNextMonday(start: SDateLike) = {
    val monday = if(start.getDayOfWeek() == 1) start else start.addDays(8 - start.getDayOfWeek())

    SDate(f"${monday.getFullYear()}-${monday.getMonth()}%02d-${monday.getDate()}%02dT00:00:00")
  }

  case class Props(forecastPeriod: ForecastPeriod) {

    def hash = {
      forecastPeriod.days.toList.map {
        case (day, slots) => slots.hashCode
      }
    }.hashCode
  }

  implicit val propsReuse = Reusability.by((_: Props).hash)

  val component = ScalaComponent.builder[Props]("TerminalForecast")
    .renderP((scope, props) => {
      val sortedDays = props.forecastPeriod.days.toList.sortBy(_._1)
      val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.transpose(_._2.take(96))

      <.div(
        <.table(^.className := "forecast",
          <.thead(
            <.tr(
              <.th(^.className :="heading"), sortedDays.map {
                case (day, _) =>
                  <.th(^.colSpan := 2, ^.className := "heading", s"${SDate(MilliDate(day)).getDate()}/${SDate(MilliDate(day)).getMonth()}")
              }.toTagMod
            ),
            <.tr(
              <.th(^.className := "heading", "Time"), sortedDays.flatMap(_ => List(<.th(^.className := "sub-heading", "Avail"), <.th(^.className := "sub-heading", "Rec"))).toTagMod
            )),
          <.tbody(
            byTimeSlot.map(row => {
              <.tr(
                <.td(SDate(MilliDate(row.head.startMillis)).toHoursAndMinutes()), row.flatMap(
                  col => {
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

  def apply(props: Props): VdomElement = {
    component(props)
  }
}



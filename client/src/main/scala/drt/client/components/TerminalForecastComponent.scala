package drt.client.components

import drt.client.SPAMain.{Loc, TerminalPageTabLoc}
import drt.client.actions.Actions.GetForecastWeek
import drt.client.services.JSDateConversions.SDate
import drt.client.services.SPACircuit
import drt.shared.CrunchApi.{ForecastPeriod, ForecastTimeSlot}
import drt.shared.{MilliDate, SDateLike}
import japgolly.scalajs.react.extra.Reusability
import japgolly.scalajs.react.extra.router.RouterCtl
import japgolly.scalajs.react.vdom.html_<^.{<, _}
import japgolly.scalajs.react.{Callback, ReactEventFromInput, ScalaComponent}

import scala.collection.immutable.Seq

object TerminalForecastComponent {

  def getNextMonday(start: SDateLike) = {
    val monday = if (start.getDayOfWeek() == 1) start else start.addDays(8 - start.getDayOfWeek())

    SDate(f"${monday.getFullYear()}-${monday.getMonth()}%02d-${monday.getDate()}%02dT00:00:00")
  }

  case class Props(forecastPeriod: ForecastPeriod, page: TerminalPageTabLoc, router: RouterCtl[Loc]) {

    def hash = {
      forecastPeriod.days.toList.map {
        case (day, slots) => slots.hashCode
      }
    }.hashCode
  }

  val yearOfMondays: Seq[SDateLike] = (0 to 51).map(w => getNextMonday(SDate.now()).addDays(w * 7))

  implicit val propsReuse = Reusability.by((_: Props).hash)

  val component = ScalaComponent.builder[Props]("TerminalForecast")
    .renderP((scope, props) => {
      val sortedDays = props.forecastPeriod.days.toList.sortBy(_._1)
      val byTimeSlot: Iterable[Iterable[ForecastTimeSlot]] = sortedDays.transpose(_._2.take(96))

      def drawSelect(names: Seq[String], values: List[String], value: String) = {
        <.select(^.className := "form-control", ^.value := value.toString,
          ^.onChange ==> ((e: ReactEventFromInput) =>
              props.router.set(props.page.copy(date = Option(SDate(e.target.value).toLocalDateTimeString())))
          ),
          values.zip(names).map {
            case (value, name) => <.option(^.value := value.toString, name)
          }.toTagMod)
      }

      <.div(
        <.div(^.className := "form-group row planning-week",
          <.div(^.className := "col-sm-3 no-gutters", <.label(^.className := "col-form-label", "Select week start day")),
          <.div(^.className := "col-sm-2 no-gutters",
            drawSelect(
              yearOfMondays.map(_.ddMMyyString),
              yearOfMondays.map(_.toISOString()).toList,
              defaultStartDate(props.page.date).toISOString())
          )
        ),
        <.table(^.className := "forecast",
          <.thead(
            <.tr(
              <.th(^.className := "heading"), sortedDays.map {
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

  def defaultStartDate(dateStringOption: Option[String]) = {
    dateStringOption.map(SDate(_)).getOrElse(getNextMonday(SDate.now()))
  }

  def apply(props: Props): VdomElement = component(props)
}



package drt.client.components

import diode.react.ModelProxy
import drt.client.actions.Actions.SetPointInTime
import drt.client.logger.LoggerFactory
import drt.client.services.JSDateConversions.SDate
import drt.client.services._
import drt.shared.{MilliDate, SDateLike}
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{ReactEventFromInput, ScalaComponent}

import scala.scalajs.js.Date

object DateViewSelector {

  val log = LoggerFactory.getLogger("DateTimeSelector")

  case class Props()

  case class State(dateSelected: SDateLike)

  val initialState = State(SDate.now())

  val component = ScalaComponent.builder[Props]("DateTimeSelector")
    .initialState(initialState).renderS((scope, state) => {
    val pointInTimeRCP = SPACircuit.connect(
      m => m.pointInTime
    )
    pointInTimeRCP((pointInTimeMP: ModelProxy[Option[SDateLike]]) => {

      val pointInTime = pointInTimeMP()

      def today = {
        val now = new Date()
        new Date(now.getFullYear(), now.getMonth(), now.getDate(), 23, 59)
      }

      def callback(v: String) = (s: State) => {
        val date = SDate(MilliDate(v.toLong))
        SPACircuit.dispatch(SetPointInTime(date.millisSinceEpoch))
        State(date)
      }

      val earliestAvailable = SDate.parse("2017-08-26").millisSinceEpoch

      val todayMillis = today.getTime().toLong
      val defaultValue = state.dateSelected.millisSinceEpoch.toString
      <.div(
        <.div(^.className := "date-view-picker-container",
          <.div(
            "Show: ",
            <.select(
              ^.defaultValue := defaultValue,
              ^.onChange ==> ((e: ReactEventFromInput) => scope.modState(callback(e.target.value))),
              (todayMillis to earliestAvailable by -(60000 * 60 * 24)).map {
                case millis => {
                  val date = SDate(MilliDate(millis))
                  val dateLabel = if (millis == todayMillis) "today" else f"${date.getDate}%02d/${date.getMonth}%02d/${date.getFullYear}"
                  <.option(^.value := s"$millis", dateLabel)
                }
              }.toTagMod))
        )
      )
    })
  }).build


  def apply(): VdomElement = component(Props())
}

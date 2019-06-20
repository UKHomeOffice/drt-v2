package drt.client.components

import diode.data.Pot
import drt.client.services.JSDateConversions.SDate
import drt.client.services.{ViewLive, ViewMode}
import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, PortState}
import drt.shared.FlightsApi.{QueueName, TerminalName}
import drt.shared.{Queues, SDateLike, TQM}
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.{Callback, ScalaComponent}
import org.scalajs.dom.html.Div

case class PcpPaxSummary(startMillis: MillisSinceEpoch,
                         durationMinutes: Long,
                         totalPax: Double,
                         queuesPax: Map[QueueName, Double])

object PcpPaxSummary {
  def apply(startMillis: MillisSinceEpoch, durationMillis: Long, crunchMinutes: Map[TQM, CrunchMinute], terminalName: TerminalName, queues: Set[QueueName]): PcpPaxSummary = {
    val queueLoads: Map[QueueName, Double] = crunchMinutes
      .values
      .filter(cm => cm.minute >= startMillis && cm.minute < startMillis + (durationMillis * 60000) && cm.terminalName == terminalName)
      .groupBy(_.queueName)
      .map {
        case (qn, cms) =>
          val sum = cms.toSeq.map(_.paxLoad).sum
          (qn, sum)
      }
    val relevantQueueLoads = queueLoads.filter { case (qn, _) => queues.contains(qn) }
    PcpPaxSummary(startMillis, durationMillis, queueLoads.values.sum, relevantQueueLoads)
  }
}

object PcpPaxSummariesComponent {

  case class Props(crunchStatePot: Pot[PortState], viewMode: ViewMode, terminalName: TerminalName, minuteTicker: Int)

  class Backend {
    def render(props: Props): TagOf[Div] = {
      val now = SDate.now()
      val fiveMinutes = 5
      val queues = Seq(Queues.EeaDesk, Queues.NonEeaDesk)
      <.div(^.className := "pcp-pax-summaries",
        if (props.viewMode == ViewLive) {
          props.crunchStatePot.render(cs => {
            val boxes = Seq("next 5 mins", "5-10 mins", "10-15 mins")
            <.div(
              boxes.zipWithIndex.map {
                case (label, box) =>
                  val start = now.addMinutes(box * 5)
                  val summary = PcpPaxSummary(start.millisSinceEpoch, fiveMinutes, cs.crunchMinutes, props.terminalName, queues.toSet)
                  summaryBox(box, label, start, queues, summary)
              }.toTagMod
            )
          })
        } else ""
      )
    }
  }

  def summaryBox(boxNumber: Int, label: String, now: SDateLike, queues: Seq[QueueName], summary1: PcpPaxSummary): TagOf[Div] = {
    <.div(^.className := s"pcp-pax-summary b$boxNumber",
      <.div(^.className := "total", s"${summary1.totalPax.round}"),
      <.div(^.className := "queues",
        queues.map(qn => {
          <.div(^.className := "queue",
            <.div(^.className := "queue-name", <.div(Queues.queueDisplayNames(qn))),
            <.div(^.className := "queue-pax", <.div(s"${summary1.queuesPax.getOrElse(qn, 0d).round}"))
          )
        }).toTagMod
      ),
      <.div(^.className := "vertical-spacer"),
      <.div(^.className := "time-range-label", label),
      <.div(^.className := "time-range", s"${now.toHoursAndMinutes()} - ${now.addMinutes(5).toHoursAndMinutes()}")
    )
  }

  val component = ScalaComponent.builder[Props]("PcpPaxSummariesComponent")
    .renderBackend[PcpPaxSummariesComponent.Backend]
    .componentDidMount(p => {
      Callback.log(s"PcpPaxSummaries component didMount")
    })
    .build

  def apply(crunchStatePot: Pot[PortState], viewMode: ViewMode, terminalName: TerminalName, minuteTicker: Int): VdomElement =
    component(Props(crunchStatePot, viewMode, terminalName, minuteTicker))
}

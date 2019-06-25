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

case class PcpPaxSummary(totalPax: Int, queuesPax: Map[QueueName, Double])

object PcpPaxSummary {
  def apply(start: SDateLike, durationMinutes: Int, crunchMinutes: Map[TQM, CrunchMinute], terminalName: TerminalName, queues: Set[QueueName]): PcpPaxSummary = {
    val startRounded = start.roundToMinute()
    val minuteMillisRange = (startRounded.millisSinceEpoch until startRounded.addMinutes(durationMinutes).millisSinceEpoch by 60000).toList
    val relevantMinutes = crunchMinutes.filterKeys {
      case TQM(tn, _, minute) => tn == terminalName && minuteMillisRange.contains(minute)
    }

    val paxTotal = relevantMinutes.map { case (_, cm) => cm.paxLoad }.sum
    val queueLoads = relevantMinutes.values
      .filter(cm => queues.contains(cm.queueName))
      .groupBy(_.queueName)
      .map {
        case (qn, cms) => (qn, cms.map(_.paxLoad).sum)
      }

    PcpPaxSummary(paxTotal.toInt, queueLoads)
  }
}

object PcpPaxSummariesComponent {

  case class Props(portStatePot: Pot[PortState], viewMode: ViewMode, terminalName: TerminalName, minuteTicker: Int)

  class Backend {
    def render(props: Props): TagOf[Div] = {
      val now = SDate.now()
      val fiveMinutes = 5
      val queues = Seq(Queues.EeaDesk, Queues.NonEeaDesk)
      <.div(^.className := "pcp-pax-summaries",
        if (props.viewMode == ViewLive) {
          props.portStatePot.render { cs =>
            val boxes = Seq("next 5 mins", "5-10 mins", "10-15 mins")
            <.div(
              boxes.zipWithIndex.map {
                case (label, box) =>
                  val start = now.addMinutes(box * 5)
                  val crunchMinutes = cs.window(start, start.addMinutes(5), Map(props.terminalName -> queues)).crunchMinutes
                  val summary = PcpPaxSummary(start, fiveMinutes, crunchMinutes, props.terminalName, queues.toSet)
                  summaryBox(box, label, start, queues, summary)
              }.toTagMod
            )
          }
        } else ""
      )
    }
  }

  def summaryBox(boxNumber: Int, label: String, now: SDateLike, queues: Seq[QueueName], summary: PcpPaxSummary): TagOf[Div] = {
    <.div(^.className := s"pcp-pax-summary b$boxNumber",
      <.div(^.className := "total", s"${summary.totalPax}"),
      <.div(^.className := "queues",
        queues.map(qn => {
          <.div(^.className := "queue",
            <.div(^.className := "queue-name", <.div(Queues.queueDisplayNames(qn))),
            <.div(^.className := "queue-pax", <.div(s"${summary.queuesPax.getOrElse(qn, 0d).round}"))
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

  def apply(portStatePot: Pot[PortState], viewMode: ViewMode, terminalName: TerminalName, minuteTicker: Int): VdomElement =
    component(Props(portStatePot, viewMode, terminalName, minuteTicker))
}

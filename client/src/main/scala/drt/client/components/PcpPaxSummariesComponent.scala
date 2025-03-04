package drt.client.components

import diode.UseValueEq
import diode.data.Pot
import drt.client.services.JSDateConversions.SDate
import drt.client.services.ViewMode
import japgolly.scalajs.react.{CtorType, ScalaComponent}
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import japgolly.scalajs.react.vdom.{TagOf, html_<^}
import org.scalajs.dom.html.Div
import uk.gov.homeoffice.drt.model.CrunchMinute
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.SDateLike

case class PcpPaxSummary(totalPax: Int, queuesPax: Map[Queue, Double])

object PcpPaxSummary {
  def apply(start: SDateLike, durationMinutes: Int, crunchMinutes: Seq[CrunchMinute], queues: Set[Queue]): PcpPaxSummary = {
    val startRounded = start.roundToMinute()
    val minuteMillisRange = (startRounded.millisSinceEpoch until startRounded.addMinutes(durationMinutes).millisSinceEpoch by 60000).toList
    val relevantMinutes = crunchMinutes.filter(cm => minuteMillisRange.contains(cm.minute))

    val paxTotal = relevantMinutes.map {
      _.paxLoad
    }.sum
    val queueLoads: Map[Queue, Double] = relevantMinutes
      .filter(cm => queues.contains(cm.queue))
      .groupBy(_.queue)
      .map {
        case (qn, cms) => (qn, cms.map(_.paxLoad).sum)
      }

    PcpPaxSummary(paxTotal.toInt, queueLoads)
  }
}

object PcpPaxSummariesComponent {

  case class Props(viewMode: ViewMode, minuteTicker: Pot[Int], crunchMinutesPot: Pot[Seq[CrunchMinute]]) extends UseValueEq

  class Backend {
    def render(props: Props): html_<^.VdomNode = {
      val now = SDate.now()
      val fiveMinutes = 5
      val queues = Seq(Queues.EeaDesk, Queues.NonEeaDesk)
      val boxes = Seq("next 5 minutes", "5-10 minutes", "10-15 minutes")
      if (props.viewMode.isLive) {
        props.crunchMinutesPot.render(cms =>
          <.div {
            <.div(
              ^.className := "pcp-pax-summaries",
              boxes.zipWithIndex.map {
                case (label, box) =>
                  val start = now.addMinutes(box * 5)
                  val summary = PcpPaxSummary(start, fiveMinutes, cms, queues.toSet)
                  summaryBox(box, label, start, queues, summary)
              }.toVdomArray
            )
          }
        )
      }
      else EmptyVdom
    }
  }

  private def summaryBox(boxNumber: Int, label: String, now: SDateLike, queues: Seq[Queue], summary: PcpPaxSummary): TagOf[Div] = {
    <.div(^.className := s"pcp-pax-summary b$boxNumber", ^.key := boxNumber,
      <.div(^.className := "total", s"${summary.totalPax}"),
      <.div(^.className := "queues",
        queues.map(qn => {
          <.div(^.className := "queue",
            <.div(^.className := "queue-name", <.div(Queues.displayName(qn))),
            <.div(^.className := "queue-pax", <.div(s"${summary.queuesPax.getOrElse(qn, 0d).round}"))
          )
        }).toTagMod
      ),
      <.div(^.className := "vertical-spacer"),
      <.div(^.className := "time-range-label", label),
      <.div(^.className := "time-range", s"${now.toHoursAndMinutes} - ${now.addMinutes(5).toHoursAndMinutes}")
    )
  }

  val component: Component[Props, Unit, Backend, CtorType.Props] = ScalaComponent.builder[Props]("PcpPaxSummariesComponent")
    .renderBackend[PcpPaxSummariesComponent.Backend]
    .build

  def apply(viewMode: ViewMode, minuteTicker: Pot[Int], crunchMinutes: Pot[Seq[CrunchMinute]]): VdomElement =
    component(Props(viewMode, minuteTicker, crunchMinutes))
}

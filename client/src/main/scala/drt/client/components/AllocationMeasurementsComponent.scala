package drt.client.components

import drt.client.services.JSDateConversions.SDate
import drt.shared.AllocationPreview.{ MeasurementSummary, Measurements, Result }
import japgolly.scalajs.react.{ CtorType, ScalaComponent }
import japgolly.scalajs.react.component.Scala.Component
import japgolly.scalajs.react.vdom.html_<^._
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

object AllocationMeasurementsComponent {
  case class Props(
      terminal: Terminal,
      date: LocalDate,
      kind: String,
      startMillis: Long,
      endMillis: Long,
      result: Result
  )

  private case class Metric(label: String, definition: String, value: Measurements => Long, staffHours: Boolean = false)

  private val metrics: Seq[Metric] = Seq(
    Metric(
      "SLA-breach minutes",
      "Minutes when waiting time is at or above the queue's SLA. Totals count queue-minutes: simultaneous queues count separately.",
      _.slaBreachMinutes
    ),
    Metric(
      "Breach severity (passenger-minutes)",
      "Waiting time above SLA, summed across passengers, including passengers still waiting at the end of the calculation.",
      _.breachPassengerMinutes
    ),
    Metric(
      "Queue burden (passenger-minutes)",
      "Queue size summed for every minute, including waits below the SLA.",
      _.queuePassengerMinutes
    ),
    Metric(
      "Staff-hours",
      "Allocated manual desk and gate bank minutes divided by 60. One bank counts as one member of staff, not one per gate.",
      _.staffMinutes,
      staffHours = true
    )
  )

  def format(value: Long, staffHours: Boolean = false): String =
    if (staffHours) (BigDecimal(value) / 60).setScale(2, BigDecimal.RoundingMode.HALF_UP).toString
    else value.toString

  def change(legacy: Long, neutral: Long, staffHours: Boolean = false): String = {
    val difference = neutral - legacy
    s"${if (difference > 0) "+" else ""}${format(difference, staffHours)}"
  }

  private def queueValue(summary: MeasurementSummary, queue: Queue): Option[Measurements] =
    if (summary.unavailableQueues.contains(queue)) None else summary.byQueue.get(queue)

  private def incomplete(summary: MeasurementSummary, queues: Seq[Queue]): Boolean =
    summary.total.isEmpty || summary.unavailableQueues.nonEmpty || queues.exists(queueValue(summary, _).isEmpty)

  private def row(
      label: String,
      legacy: Option[Measurements],
      neutral: Option[Measurements],
      metric: Metric
  ): VdomElement = {
    val left = legacy.map(metric.value)
    val right = neutral.map(metric.value)
    val difference = for { l <- left; r <- right } yield change(l, r, metric.staffHours)
    val legacyLabel: String = left.map(format(_, metric.staffHours)).getOrElse("Unavailable")
    val neutralLabel: String = right.map(format(_, metric.staffHours)).getOrElse("Unavailable")
    val changeLabel: String = difference.getOrElse("Unavailable")
    <.tr(
      ^.key := label,
      <.th(^.scope := "row", label),
      <.td(legacyLabel),
      <.td(neutralLabel),
      <.td(changeLabel)
    )
  }

  val component: Component[Props, Unit, Unit, CtorType.Props] = ScalaComponent.builder[Props]("AllocationMeasurements")
    .render_P { props =>
      val kind = if (props.kind == "available") "Available" else "Recommended"
      <.section(
        ^.className := "allocation-measurements",
        ^.aria.label := "Legacy and Neutral allocation measurements",
        <.h2(s"$kind measurements: ${props.terminal}, ${props.date.toISOString}"),
        <.p(
          s"Full calculation window: ${SDate(props.startMillis).toISOString} to ${SDate(props.endMillis).toISOString}."
        ),
        <.p("These measurements cover the full calculation window, not the displayed hours or interval."),
        <.p("Both algorithms recalculated from the same captured inputs; saved Legacy rows may differ."),
        <.p("Change is Neutral minus Legacy."),
        props.result.measurements match {
          case None             => <.p("Measurements unavailable for this result")
          case Some(comparison) =>
            val legacy = comparison.legacy
            val neutral = comparison.neutral
            <.div(
              if (incomplete(legacy, props.result.queues) || incomplete(neutral, props.result.queues))
                <.p(^.role := "status", "Comparison incomplete: measurements are unavailable for one or more queues.")
              else EmptyVdom,
              Seq("Legacy" -> legacy, "Neutral" -> neutral).map { case (name, summary) =>
                if (!summary.resourceFeasible && summary.total.nonEmpty)
                  <.p(^.role := "status", s"$name allocation exceeds the captured resource limits")
                else EmptyVdom
              }.toTagMod,
              <.div(
                ^.className := "allocation-measurements-grid",
                metrics.map { metric =>
                  <.article(
                    ^.key := metric.label,
                    ^.className := "allocation-measurements-card",
                    <.h3(metric.label),
                    <.p(metric.definition),
                    <.table(
                      ^.aria.label := s"$kind ${metric.label}: Legacy, Neutral and change",
                      <.thead(<.tr(
                        Seq("Queue", "Legacy", "Neutral", "Change").map(label =>
                          <.th(^.key := label, ^.scope := "col", label)
                        ).toTagMod
                      )),
                      <.tbody(
                        props.result.queues.map(queue =>
                          row(Queues.displayName(queue), queueValue(legacy, queue), queueValue(neutral, queue), metric)
                        ).toTagMod,
                        row(
                          if (metric.label == "SLA-breach minutes") "Total (queue-minutes)" else "Terminal total",
                          legacy.total,
                          neutral.total,
                          metric
                        )
                      )
                    )
                  )
                }.toTagMod
              )
            )
        }
      )
    }
    .build

  def apply(props: Props): VdomElement = component(props)
}

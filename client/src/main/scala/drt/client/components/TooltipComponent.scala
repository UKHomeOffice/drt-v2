package drt.client.components

import drt.shared.Queues.{EGate, Queue}
import japgolly.scalajs.react.vdom.TagOf
import japgolly.scalajs.react.vdom.html_<^._
import org.scalajs.dom.html
import org.scalajs.dom.html.Span

object TooltipComponent {

  val tooltipR: String => VdomTagOf[Span] = text =>
    <.span(^.className := "tooltipFaq",
      Icon.infoCircle,
      <.span(^.className := "tooltipText tooltipText-right",text))

  val tooltip: String => VdomTagOf[Span] = text =>
    <.span(^.className := "tooltipFaq",
      Icon.infoCircle,
      <.span(^.className := "tooltipText tooltipText-left", text))

  val tooltipPTag: Seq[TagOf[html.Paragraph]] => VdomTagOf[Span] = text =>
    <.span(^.className := "tooltipFaq",
      Icon.infoCircle,
      <.span(^.className := "tooltipText tooltipText-left", text.toTagMod))

  val depTooltip: Queue => VdomTagOf[Span] = queue => if (queue == EGate) depBanksTooltip else depDesksTooltip

  val staffMovementsTabTooltip = tooltip("The Staff Movements tab provides more information about adjustments.")

  val defaultProcessingTimesTooltip = tooltip("Processing times are unique to each port. If they don't look right contact us and we'll get them changed for you.")

  val walkTimesTooltip = tooltip("Walk times measure the average time it takes for passengers to get from the gate or stand to the arrival hall. If they don't look right contact us.")

  val currentTooltip = tooltip("The Current view uses the latest data available to provide information about the day being viewed.")

  val monthlyStaffingTooltip = tooltip("You have permission to add staff to DRT. Add them directly into the spreadsheet in hourly or 15 minute slots, or copy and paste from an existing spreadsheet if you have one in the same format.")

  val snapshotTooltip = tooltip("The Snapshot view uses the data that was available to DRT at the point in time selected.")

  val miscTooltip = tooltip("The values under Misc represent the number of staff not allocated to a desk or e-passport gate (these are often referred to as fixed points). You can edit this value by clicking on the Staff Movements tab.")

  val movesTooltip = tooltip("The value displayed in the Moves column reflects adjustments.")

  val recToolTip = tooltipPTag(List(<.p("The values under Rec in the PCP area represent the number of staff DRT recommends to avoid breaching SLAs. If a recommended number of staff is equal to, or greater than the maximum number of staff that are actually available, DRT will colour code it red to warn you (because it could result in SLA breaches)."),
    <.p("If a recommended number of staff is less than, but getting close to the maximum number of staff available, DRT will colour code it amber.")))

  val availTooltip = tooltipR("Use the + or - buttons to make adjustments to staff available (eg due to sickness and lunch breaks). You can select from a list of reasons when making the adjustment.")

  val recommendationsTooltip = tooltipR("In the Recommendations view DRT uses the ideal number of staff. It's not constrained by staff available.")

  val availableStaffDeploymentsTooltip = tooltip("In the Available staff deployment view, DRT allocates the minimum number of staff needed to help you avoid breaching SLAs within the constraints of staff available.")

  val estWaitTooltip = tooltip("DRT will colour code Dep desks/Dep banks and their associated Est wait values amber when estimated wait times are close to SLA and red when estimated wait times in excess of the SLA.")

  val depDesksTooltip = tooltip("The values under Dep desks represent the number of desks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val depBanksTooltip = tooltip("The values under Dep banks represent the number of banks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val splitsTableTooltip = tooltipPTag(List(<.p("RAG colours are used to indicate DRT's confidence levels in the accuracy of its passenger number forecasts. Confidence levels depend on the source of data being displayed."),
    <.p("green -> live API data is available for the flight and we have high confidence levels when applying splits to queues"),
    <.p("amber-> live API data is not available for the flight so we rely on historic API data and have lower confidence levels when applying splits to queues"),
    <.p("red -> both live and relevant historic API data are not available and we depend on an historic average for flights across the port")))

  val wbrFlightColorTooltip = tooltipPTag(List(<.p("Flights are highlighted in different colours to show their whereabouts."),
    <.p("white -> it has arrived and its passengers are at the PCP"),
    <.p("blue -> its passengers have not arrived in the PCP yet"),
    <.p("red -> it's expected to arrive more than 1 hour outside the scheduled arrival time")))

  val arrivalStatusTooltip = tooltip("When no status is provided to DRT in any feeds an Unknown status (UNK) is displayed.")

  val apiDataTooltip = tooltip("Live API data should become available for all flights soon after they depart. However, there are occasionally circumstances where live API data is not made available.")
}
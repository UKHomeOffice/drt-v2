package drt.client.components

import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue}
import japgolly.scalajs.react.vdom.html_<^._

object ToolTips {

  def depBanksOrDesksTip(queue: Queue) = if (queue == EGate) depBanksTooltip else depDesksTooltip

  def recBanksOrDesksTip(queue: Queue) = if (queue == EGate) recBanksTooltip else recDesksTooltip

  val apiDataTooltip = Tippy.info("api-data-tooltip", "Live API data should become available for all flights soon after they depart. However, there are occasionally circumstances where live API data is not made available.")

  val availTooltip = Tippy.info("avail-tooltip", "Use the + or - buttons to make adjustments to staff available (eg due to sickness and lunch breaks). You can select from a list of reasons when making the adjustment.")

  val availableStaffDeploymentsTooltip = Tippy.infoHover("availableStaffDeployments-tooltip", "This view shows the minimum number of staff needed to help avoid breaching SLAs within the constraints of staff available.")

  val countryTooltip = Tippy.info("country-tooltip", <.span("In relevant historical time periods, countries on the COVID-19 UK red list are underlined in red."))

  val processingTimesTooltip = Tippy.info("processing-times-tooltip", "Processing times are unique to each port. If they don't look right contact us and we'll get them changed for you.")

  val depBanksTooltip = Tippy.info("dep-banks-tooltip", "The values under Dep banks represent the number of banks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val depDesksTooltip = Tippy.info("dep-desks-tooltip", "The values under Dep desks represent the number of desks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val estWaitTooltip = Tippy.info("est-wait-tooltip", "DRT will colour code Dep desks/Dep banks and their associated Est wait values amber when estimated wait times are close to SLA and red when estimated wait times in excess of the SLA.")

  val expTimeTooltip = Tippy.info("exp-time-tooltip", "This represents the best arrival time available. You can view all times by clicking on the expected time.")

  val miscTooltip = Tippy.info("misc-tooltip", "The values under Misc represent the number of staff not allocated to a desk or e-passport gate (these are often referred to as fixed points). You can edit this value by clicking on the Staff Movements tab.")

  val monthlyStaffingTooltip = Tippy.info("monthly-staffing-tooltip", "You have permission to add staff to DRT. Add them directly into the spreadsheet in hourly or 15 minutes slots, or copy and paste from an existing spreadsheet if you have one in the same format.")

  val movesTooltip = Tippy.info("moves-tooltip", "The value displayed in the Moves column reflects adjustments.")

  val recBanksTooltip = Tippy.info("rec-banks-tooltip", "The values under Rec banks represent the number of banks DRT recommends are deployed to avoid an SLA breach without taking available staff into consideration.")

  val recDesksTooltip = Tippy.info("rec-desks-tooltip", "The values under Rec desks represent the number of desks DRT recommends are deployed to avoid an SLA breach without taking available staff into consideration.")

  val recommendationsTooltip = Tippy.infoHover("recommendations-tooltip", "This view shows the ideal number of staff needed to help avoid breaching SLAs. It's not constrained by staff available.")

  val recToolTip = Tippy.info("rec-tooltip", <.div(
    <.p("The values under Rec in the PCP area represent the number of staff DRT recommends to avoid breaching SLAs. If a recommended number of staff is equal to, or greater than the maximum number of staff that are actually available, DRT will colour code it red to warn you (because it could result in SLA breaches)."),
    <.p("If a recommended number of staff is less than, but getting close to the maximum number of staff available, DRT will colour code it amber."))
  )

  val splitsTableTooltip = Tippy.info("splits-table-tooltip", <.div(<.p("RAG colours are used to indicate DRT's confidence levels in the accuracy of its passenger number forecasts. Confidence levels depend on the source of data being displayed."),
    <.p("green -> live API data is available for the flight and we have high confidence levels when applying splits to queues"),
    <.p("amber-> live API data is not available for the flight so we rely on historic API data and have lower confidence levels when applying splits to queues"),
    <.p("red -> both live and relevant historic API data are not available and we depend on an historic average for flights across the port")))

  val staffMovementsTabTooltip = Tippy.infoHover("staff-movements-tab-tooltip", "The Staff Movements tab provides more information about adjustments.")

  val walkTimesTooltip = Tippy.info("walk-times-tooltip", "Walk times measure the average time it takes for passengers to get from the gate or stand to the arrival hall. If they don't look right contact us.")

  val wbrFlightColorTooltip = Tippy.info("wbr-flight-color-tooltip", <.div(<.p("Flights are highlighted in different colours to show their whereabouts."),
    <.p("white -> it has arrived and its passengers are at the PCP"),
    <.p("blue -> its passengers have not arrived in the PCP yet"),
    <.p("red -> it's expected to arrive more than 1 hour outside the scheduled arrival time")))
}

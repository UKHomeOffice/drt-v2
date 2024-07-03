package drt.client.components

import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue}
import japgolly.scalajs.react.vdom.html_<^._

object ToolTips {

  def depBanksOrDesksTip(queue: Queue) = if (queue == EGate) depBanksTooltip else depDesksTooltip

  def recBanksOrDesksTip(queue: Queue) = if (queue == EGate) recBanksTooltip else recDesksTooltip

  val apiDataTooltip = Tippy.info("Live API data should become available for all flights soon after they depart. However, there are occasionally circumstances where live API data is not made available.")

  val arrivalStatusTooltip = Tippy.info("When no status is provided to DRT in any feeds an Unknown status (UNK) is displayed.")

  val availTooltip = Tippy.info("Use the + or - buttons to make adjustments to staff available (eg due to sickness and lunch breaks). You can select from a list of reasons when making the adjustment.")

  val availableStaffDeploymentsTooltip = Tippy.infoHover("This view shows the minimum number of staff needed to help avoid breaching SLAs within the constraints of staff available.")

  val countryTooltip = Tippy.info(<.span("In relevant historical time periods, countries on the COVID-19 UK red list are underlined in red."))

  val processingTimesTooltip = Tippy.info("Processing times are unique to each port. If they don't look right contact us and we'll get them changed for you.")

  val depBanksTooltip = Tippy.info("The values under Dep banks represent the number of banks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val depDesksTooltip = Tippy.info("The values under Dep desks represent the number of desks DRT is able to recommend given the maximum number of staff available for allocation to desks and queues.")

  val estWaitTooltip = Tippy.info("DRT will colour code Dep desks/Dep banks and their associated Est wait values amber when estimated wait times are close to SLA and red when estimated wait times in excess of the SLA.")

  val expTimeTooltip = Tippy.info("This represents the best arrival time available. You can view all times by clicking on the expected time.")

  val miscTooltip = Tippy.info("The values under Misc represent the number of staff not allocated to a desk or e-passport gate (these are often referred to as fixed points). You can edit this value by clicking on the Staff Movements tab.")

  val monthlyStaffingTooltip = Tippy.info("You have permission to add staff to DRT. Add them directly into the spreadsheet in hourly or 15 minutes slots, or copy and paste from an existing spreadsheet if you have one in the same format.")

  val movesTooltip = Tippy.info("The value displayed in the Moves column reflects adjustments.")

  val recBanksTooltip = Tippy.info("The values under Rec banks represent the number of banks DRT recommends are deployed to avoid an SLA breach without taking available staff into consideration.")

  val recDesksTooltip = Tippy.info("The values under Rec desks represent the number of desks DRT recommends are deployed to avoid an SLA breach without taking available staff into consideration.")

  val recommendationsTooltip = Tippy.infoHover("This view shows the ideal number of staff needed to help avoid breaching SLAs. It's not constrained by staff available.")

  val recToolTip = Tippy.info(<.div(
    <.p("The values under Rec in the PCP area represent the number of staff DRT recommends to avoid breaching SLAs. If a recommended number of staff is equal to, or greater than the maximum number of staff that are actually available, DRT will colour code it red to warn you (because it could result in SLA breaches)."),
    <.p("If a recommended number of staff is less than, but getting close to the maximum number of staff available, DRT will colour code it amber."))
  )

  val snapshotTooltip = Tippy.infoHover("The Snapshot view uses the data that was available to DRT at the point in time selected.")

  val splitsTableTooltip = Tippy.info(<.div(<.p("RAG colours are used to indicate DRT's confidence levels in the accuracy of its passenger number forecasts. Confidence levels depend on the source of data being displayed."),
    <.p("green -> live API data is available for the flight and we have high confidence levels when applying splits to queues"),
    <.p("amber-> live API data is not available for the flight so we rely on historic API data and have lower confidence levels when applying splits to queues"),
    <.p("red -> both live and relevant historic API data are not available and we depend on an historic average for flights across the port")))

  val totalPaxTooltip = Tippy.info(<.div(<.p("RAG colours are used to indicate DRT's confidence levels in the accuracy of its passenger number forecasts. Confidence levels depend on the source of data being displayed."),
    <.p("green -> live API or Port data is available for the flight and we have high confidence levels"),
    <.p("amber-> port forecast is available or historic API data is used as a fallback"),
    <.p("red ->  acl Forecast data is available")))


  val staffMovementsTabTooltip = Tippy.infoHover("The Staff Movements tab provides more information about adjustments.")

  val walkTimesTooltip = Tippy.info("Walk times measure the average time it takes for passengers to get from the gate or stand to the arrival hall. If they don't look right contact us.")

  val wbrFlightColorTooltip = Tippy.info(<.div(<.p("Flights are highlighted in different colours to show their whereabouts."),
    <.p("white -> it has arrived and its passengers are at the PCP"),
    <.p("blue -> its passengers have not arrived in the PCP yet"),
    <.p("red -> it's expected to arrive more than 1 hour outside the scheduled arrival time")))
}

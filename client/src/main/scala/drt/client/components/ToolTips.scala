package drt.client.components

import drt.client.components.TerminalDesksAndQueues.{Deployments, DeskType}
import japgolly.scalajs.react.component.Scala.Unmounted
import uk.gov.homeoffice.drt.ports.Queues.{EGate, Queue}
import japgolly.scalajs.react.vdom.html_<^._

object ToolTips {
  def depBanksOrDesksTip(queue: Queue): Unmounted[Tippy.Props, Unit, Unit] = if (queue == EGate) depBanksTooltip else depDesksTooltip
  def recBanksOrDesksTip(queue: Queue): Unmounted[Tippy.Props, Unit, Unit] = if (queue == EGate) recBanksTooltip else recDesksTooltip
  def paxBanksOrDesksTip(queue: Queue): Unmounted[Tippy.Props, Unit, Unit] = if (queue == EGate) incomingPaxBanksTooltip else incomingPaxDesksTooltip
  def pcpStaffDeployed(deskType: DeskType): Unmounted[Tippy.Props, Unit, Unit] = if (deskType == Deployments) deployedAvailableTooltip else deployedRecommendedTooltip
  def pcpStaffRecommended(deskType: DeskType): Unmounted[Tippy.Props, Unit, Unit] = if (deskType == Deployments) recToolTip else availableRecToolTip

  val apiDataTooltip = Tippy.info("api-data-tooltip", "Live API data should become available for all flights soon after they depart. However, there are occasionally circumstances where live API data is not made available.")

  val availTooltip = Tippy.info("avail-tooltip", "Staffing available to cover all duties at this terminal. You can change this.")

  val availableStaffDeploymentsTooltip = Tippy.infoHover("availableStaffDeployments-tooltip", "This view shows the minimum number of staff needed to help avoid breaching SLAs within the constraints of staff available.")

  val countryTooltip = Tippy.info("country-tooltip", <.span("In relevant historical time periods, countries on the COVID-19 UK red list are underlined in red."))

  val processingTimesTooltip = Tippy.info("processing-times-tooltip", "Processing times are unique to each port. If they don't look right contact us and we'll get them changed for you.")

  val depBanksTooltip = Tippy.info("dep-banks-tooltip", "Maximum number of eGate banks which DRT can deploy to process these passengers given the staff number available.")

  val depDesksTooltip = Tippy.info("dep-desks-tooltip", "Maximum number of desks which DRT can deploy to process these passengers given the staff number available.")

  val estWaitTooltip = Tippy.info("est-wait-tooltip", "Number of minutes passengers would wait in a queue for PCP under these conditions. Amber wait times are close to SLA. Red wait times exceed SLA.")

  val expTimeTooltip = Tippy.info("exp-time-tooltip", "This represents the best arrival time available. You can view all times by clicking on the expected time.")

  val miscTooltip = Tippy.info("misc-tooltip", "Number of staff not allocated to a desk or eGate bank (also known as fixed points). Change this number in Staff Movements tab.")

  val incomingPaxDesksTooltip = Tippy.info("incoming-pax-tooltip", "Number of passengers into arrivals hall (likely to queue for desks).")
  val incomingPaxBanksTooltip = Tippy.info("incoming-pax-tooltip", "Number of passengers into arrivals hall (likely to queue for eGate banks).")

  val deployedRecommendedTooltip = Tippy.info("deployed-rec-tooltip", "Maximum number of staff DRT can deploy to cover desks and eGate banks, as well as duties covered under Misc, given the staff number available.")
  val deployedAvailableTooltip= Tippy.info("deployed-avail-tooltip", "Maximum number of staff DRT can deploy to cover desks and eGate banks, as well as duties covered under Misc, given the staff number available. If amber, the number deployed is close to the number required. If red, it exceeds the number required.")

  val movesTooltip = Tippy.info("moves-tooltip", "Number of changes made in Staff Movements.")
  val recBanksTooltip = Tippy.info("rec-banks-tooltip", "Number of eGate banks needed to avoid queue breach (SLA) at this port.")
  val recDesksTooltip = Tippy.info("rec-desks-tooltip", "Number of desks needed to avoid queue breach (SLA) at this port.")

  val recommendationsTooltip = Tippy.infoHover("recommendations-tooltip", "This view shows the ideal number of staff needed to help avoid breaching SLAs. It's not constrained by staff available.")

  val availableRecToolTip= Tippy.info("avail-rec-tooltip", "Minimum staffing needed for required desks and eGate banks, as well as duties covered under Misc. If amber, number required is close to number available. If red, it exceeds the number available.")
  val recToolTip = Tippy.info("rec-tooltip", "Minimum staff number needed to cover required desks and eGate banks, as well as duties covered under Misc. If amber, the number required is close to the number available. If red, it exceeds the number available. ")

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

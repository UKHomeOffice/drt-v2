package drt.client.services

import diode.UseValueEq
import drt.client.actions.AllocationPreviewActions._
import drt.shared.AllocationPreview.{ Response, Result }
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView }
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

case class AllocationPreviewState(
    context: Option[AllocationPreviewState.Context] = None,
    active: Boolean = false,
    generation: Long = 0L,
    selectedKind: String = "recommended",
    pendingKind: Option[String] = None,
    attemptedKinds: Set[String] = Set.empty,
    response: Option[Response] = None,
    error: Option[String] = None
) extends UseValueEq {
  def pending: Boolean = pendingKind.nonEmpty
  def resultFor(kind: String): Option[Result] =
    response.flatMap(r => if (kind == "available") r.available else r.recommended)
}

object AllocationPreviewState {
  case class Context(terminal: Terminal, date: LocalDate, eligible: Boolean, ownerId: String = "")

  def eligible(flag: Boolean, user: LoggedInUser, portAccess: Boolean, timeMachine: Boolean): Boolean =
    flag && portAccess && !timeMachine && user.roles.contains(AccessOnlyPreprod) &&
      user.roles.contains(Debug) && user.hasRole(DesksAndQueuesView)

  def safeError(status: Int): String = status match {
    case 0         => "Connection closed before Neutral returned. Please retry or select Legacy."
    case -1        => "Neutral returned an invalid response. Please retry or select Legacy."
    case 404       => "Neutral endpoint was not found. Reload the application or check the development proxy."
    case 429       => "Neutral calculation is busy. Please retry."
    case 401 | 403 => "Neutral allocation is not available for your account."
    case 503       => "Neutral calculation is temporarily unavailable. Please retry."
    case 504       => "Neutral calculation timed out. Please retry."
    case _         => "Neutral allocation could not be calculated. Please retry or select Legacy."
  }

  private def calculate(state: AllocationPreviewState): AllocationPreviewState =
    state.copy(
      generation = state.generation + 1,
      pendingKind = Some(state.selectedKind),
      attemptedKinds = state.attemptedKinds + state.selectedKind,
      error = None
    )

  private def select(state: AllocationPreviewState): AllocationPreviewState =
    if (
      state.pending ||
      state.resultFor(state.selectedKind).nonEmpty
    ) state.copy(error = None)
    else calculate(state)

  private def prepare(state: AllocationPreviewState, kind: String): AllocationPreviewState = {
    val next = state.copy(selectedKind = kind, error = if (kind == state.selectedKind) state.error else None)
    if (next.pending || next.resultFor(kind).nonEmpty || next.attemptedKinds.contains(kind)) next
    else calculate(next)
  }

  def transition(state: AllocationPreviewState, action: PreviewAction): AllocationPreviewState = action match {
    case SetPreviewContext(context) if !state.context.contains(context) =>
      AllocationPreviewState(context = Some(context), generation = state.generation + 1)
    case ExitAllocationPreview =>
      AllocationPreviewState(generation = state.generation + 1)
    case SelectNeutralAllocation(false, _) =>
      state.copy(active = false)
    case PrepareNeutralAllocation(kind)
        if !state.active && state.context.exists(_.eligible) && Set("recommended", "available").contains(kind) =>
      prepare(state, kind)
    case SelectNeutralAllocation(true, kind)
        if state.context.exists(_.eligible) && Set("recommended", "available").contains(kind) =>
      select(state.copy(active = true, selectedKind = kind))
    case RetryNeutralAllocation if !state.pending && state.context.exists(_.eligible) =>
      calculate(state)
    case PreviewReceived(generation, response)
        if state.pending && state.generation == generation && state.context.exists(_.eligible) =>
      val merged = response.copy(
        recommended = response.recommended.orElse(state.response.flatMap(_.recommended)),
        available = response.available.orElse(state.response.flatMap(_.available))
      )
      val next = state.copy(pendingKind = None, response = Some(merged), error = None)
      if (next.active) select(next) else prepare(next, next.selectedKind)
    case PreviewRequestFailed(generation, status)
        if state.pending && state.generation == generation =>
      val next = state.copy(pendingKind = None, error = Some(safeError(status)))
      if (next.active && !state.pendingKind.contains(next.selectedKind)) select(next)
      else if (!next.active) prepare(next, next.selectedKind)
      else next
    case _ => state
  }
}

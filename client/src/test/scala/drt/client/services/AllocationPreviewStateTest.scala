package drt.client.services

import drt.client.actions.AllocationPreviewActions._
import drt.client.services.AllocationPreviewState._
import drt.client.services.JSDateConversions.SDate
import drt.shared.AllocationPreview.{ Response, Result }
import uk.gov.homeoffice.drt.auth.LoggedInUser
import uk.gov.homeoffice.drt.auth.Roles.{ AccessOnlyPreprod, Debug, DesksAndQueuesView, SuperAdmin }
import uk.gov.homeoffice.drt.ports.Terminals.{ T1, T2 }
import utest._

object AllocationPreviewStateTest extends TestSuite {
  val day = SDate("2026-10-30T00:00Z")
  val context: Context = Context(T1, day.toLocalDate, eligible = true, ownerId = "owner")
  val initial: AllocationPreviewState = AllocationPreviewState(context = Some(context))
  val started: AllocationPreviewState = transition(initial, SelectNeutralAllocation(true))
  val result: Result = Result(Seq.empty, Seq.empty, Seq.empty, Map.empty)
  val response: Response = Response(day.millisSinceEpoch, day.addDays(1).millisSinceEpoch, Some(result), None)
  val ready: AllocationPreviewState = transition(started, PreviewReceived(started.generation, response))

  val tests: Tests = Tests {
    test("preparing the visible kind leaves Legacy selected and deduplicates ordinary updates") {
      val prepared = transition(initial, PrepareNeutralAllocation("recommended"))
      assert(!prepared.active, prepared.pendingKind.contains("recommended"))
      val updated = (1 to 3000).foldLeft(prepared) { (state, _) =>
        transition(transition(state, SetPreviewContext(context)), PrepareNeutralAllocation("recommended"))
      }
      assert(updated == prepared)
      val completed = transition(prepared, PreviewReceived(prepared.generation, response))
      assert(!completed.active, !completed.pending, completed.response.contains(response))
      assert(transition(completed, PrepareNeutralAllocation("recommended")) == completed)
      val neutral = transition(completed, SelectNeutralAllocation(true))
      assert(neutral.active, !neutral.pending, neutral.generation == completed.generation)
    }
    test("failed preparation never retries itself; explicit Retry works without selecting Neutral") {
      val prepared = transition(initial, PrepareNeutralAllocation("recommended"))
      val failed = transition(prepared, PreviewRequestFailed(prepared.generation, 404))
      val updated = (1 to 3000).foldLeft(failed)((state, _) =>
        transition(state, PrepareNeutralAllocation("recommended"))
      )
      assert(updated == failed, failed.error.exists(_.contains("endpoint was not found")))
      val retry = transition(failed, RetryNeutralAllocation)
      assert(!retry.active, retry.pending, retry.generation == prepared.generation + 1)
      assert(transition(retry, PrepareNeutralAllocation("recommended")) == retry)
      val unavailable = initial.copy(context = Some(context.copy(eligible = false)))
      assert(transition(unavailable, PrepareNeutralAllocation("recommended")) == unavailable)
      assert(transition(initial, PrepareNeutralAllocation("invalid")) == initial)
    }
    test("visiting another Legacy kind prepares it once; context changes permit a fresh preparation") {
      val prepared = transition(initial, PrepareNeutralAllocation("recommended"))
      val availableSelected = transition(prepared, PrepareNeutralAllocation("available"))
      assert(availableSelected.generation == prepared.generation)
      val next = transition(availableSelected, PreviewReceived(prepared.generation, response))
      assert(!next.active, next.pendingKind.contains("available"), next.generation == prepared.generation + 1)
      val complete = transition(
        next,
        PreviewReceived(next.generation, response.copy(recommended = None, available = Some(result)))
      )
      val recommendedAgain = transition(complete, PrepareNeutralAllocation("recommended"))
      assert(!recommendedAgain.pending, recommendedAgain.generation == complete.generation)
      val changed = transition(recommendedAgain, SetPreviewContext(context.copy(date = day.addDays(1).toLocalDate)))
      val fresh = transition(changed, PrepareNeutralAllocation("recommended"))
      assert(fresh.pending, fresh.response.isEmpty)
      assert(transition(fresh, PreviewReceived(prepared.generation, response)) == fresh)
    }
    test("capability, both explicit roles, airport and view access are required; Time Machine is disabled") {
      val user = LoggedInUser("test", "test", "", Set(AccessOnlyPreprod, Debug, DesksAndQueuesView))
      assert(eligible(flag = true, user, portAccess = true, timeMachine = false))
      assert(!eligible(flag = false, user, portAccess = true, timeMachine = false))
      assert(!eligible(flag = true, user, portAccess = false, timeMachine = false))
      assert(!eligible(flag = true, user, portAccess = true, timeMachine = true))
      Seq(AccessOnlyPreprod, Debug, DesksAndQueuesView).foreach { role =>
        assert(!eligible(flag = true, user.copy(roles = user.roles - role), portAccess = true, timeMachine = false))
      }
      assert(!eligible(flag = true, user.copy(roles = Set(SuperAdmin)), portAccess = true, timeMachine = false))
    }
    test("Legacy is default; only the first Neutral selection starts work") {
      assert(!initial.active, !initial.pending, initial.response.isEmpty)
      assert(started.active, started.pending, started.generation == initial.generation + 1)
      assert(started.selectedKind == "recommended", started.pendingKind.contains("recommended"))
      assert(ready.response.contains(response), !ready.pending, ready.response.get.available.isEmpty)
      assert(transition(started, SelectNeutralAllocation(true)) == started)
      assert(transition(
        initial.copy(context = Some(context.copy(eligible = false))),
        SelectNeutralAllocation(true)
      ).response.isEmpty)
      assert(!transition(AllocationPreviewState(), SelectNeutralAllocation(true)).active)
    }
    test("selecting the other kind starts one new request and merges only that result") {
      val available = transition(ready, SelectNeutralAllocation(true, "available"))
      assert(available.pendingKind.contains("available"), available.selectedKind == "available")
      assert(available.response == ready.response)
      assert(transition(available, SelectNeutralAllocation(true, "available")) == available)
      val finished = transition(
        available,
        PreviewReceived(available.generation, response.copy(recommended = None, available = Some(result)))
      )
      assert(!finished.pending, finished.response.get.recommended.contains(result))
      assert(finished.response.get.available.contains(result))
      val recommended = transition(finished, SelectNeutralAllocation(true, "recommended"))
      assert(!recommended.pending, recommended.generation == finished.generation)
    }
    test("a different selected kind waits for outstanding work, then starts only its own request") {
      val switched = transition(started, SelectNeutralAllocation(true, "available"))
      assert(switched.selectedKind == "available", switched.pendingKind.contains("recommended"))
      assert(switched.generation == started.generation)
      val received = transition(switched, PreviewReceived(started.generation, response))
      assert(received.response.get.recommended.contains(result), received.response.get.available.isEmpty)
      assert(received.pendingKind.contains("available"), received.generation == started.generation + 1)
      assert(transition(received, PreviewReceived(started.generation, response)) == received)
    }
    test("Legacy is immediate and late responses cannot reselect Neutral") {
      val legacy = transition(started, SelectNeutralAllocation(false))
      assert(!legacy.active, legacy.pending, legacy.generation == started.generation)
      val completed = transition(legacy, PreviewReceived(started.generation, response))
      assert(!completed.active, !completed.pending, completed.response.contains(response))
      val selected = transition(completed, SelectNeutralAllocation(true))
      assert(selected.active, !selected.pending, selected.generation == started.generation)
    }
    test("returning to Neutral before completion reuses the outstanding request") {
      val legacy = transition(started, SelectNeutralAllocation(false))
      val neutral = transition(legacy, SelectNeutralAllocation(true))
      assert(neutral == started)
      val completed = transition(neutral, PreviewReceived(started.generation, response))
      assert(completed.active, !completed.pending, completed.response.contains(response))
      val failedInLegacy = transition(legacy, PreviewRequestFailed(started.generation, 504))
      assert(!failedInLegacy.active, !failedInLegacy.pending, failedInLegacy.error.nonEmpty)
    }
    test("navigation, day, terminal, owner and capability changes invalidate the response") {
      Seq(
        context.copy(date = day.addDays(1).toLocalDate),
        context.copy(terminal = T2),
        context.copy(ownerId = "other"),
        context.copy(eligible = false)
      ).foreach { changed =>
        val next = transition(started, SetPreviewContext(changed))
        assert(!next.active, next.response.isEmpty, !next.pending)
        assert(transition(next, PreviewReceived(started.generation, response)) == next)
      }
      val exited = transition(started, ExitAllocationPreview)
      assert(exited.context.isEmpty, transition(exited, PreviewReceived(started.generation, response)) == exited)
      assert(transition(ready, SetPreviewContext(context)) == ready)
    }
    test("failure remains explicitly Neutral with no saved fallback; retry starts one new request") {
      Seq(-1, 0, 400, 403, 429, 500, 503, 504).foreach { status =>
        val failed = transition(started, PreviewRequestFailed(started.generation, status))
        assert(failed.active, !failed.pending, failed.response.isEmpty, failed.error.nonEmpty)
        val retry = transition(failed, RetryNeutralAllocation)
        assert(retry.pending, retry.generation == started.generation + 1, retry.error.isEmpty)
        assert(transition(retry, RetryNeutralAllocation) == retry)
      }
      assert(safeError(0).contains("Connection closed"))
      assert(safeError(-1).contains("invalid response"))
      assert(safeError(404).contains("development proxy"))
    }
  }
}

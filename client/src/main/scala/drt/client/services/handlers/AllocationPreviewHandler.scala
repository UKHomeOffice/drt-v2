package drt.client.services.handlers

import diode.{ ActionResult, Effect, ModelRW }
import drt.client.actions.AllocationPreviewActions._
import drt.client.services.{ AllocationPreviewState, DrtApi, RootModel }
import drt.shared.AllocationPreview.{ Request, Response }
import org.scalajs.dom.ext.AjaxException
import upickle.default.{ read, write }

import scala.concurrent.Future
import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
import scala.util.Try

object AllocationPreviewHandler {
  def calculate(context: AllocationPreviewState.Context, generation: Long, kind: String): Future[PreviewAction] =
    DrtApi.post("queue-allocation/neutral", write(Request(context.terminal, context.date, kind))).map { response =>
      if (response.status == 200) Try(read[Response](response.responseText)).toOption
        .filter(r => (if (kind == "available") r.available else r.recommended).nonEmpty)
        .map(r => PreviewReceived(generation, r): PreviewAction)
        .getOrElse(PreviewRequestFailed(generation, -1))
      else PreviewRequestFailed(generation, response.status)
    }.recover {
      case error: AjaxException => PreviewRequestFailed(generation, error.xhr.status)
      case _                    => PreviewRequestFailed(generation, -1)
    }
}

class AllocationPreviewHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingActionHandler(modelRW) {
  private def validContext(context: AllocationPreviewState.Context): Boolean =
    context.eligible && context.date == value.viewMode.localDate &&
      (for {
        flags <- value.featureFlags
        user <- value.loggedInUserPot
        access <- value.userHasPortAccess
      } yield user.id == context.ownerId && AllocationPreviewState.eligible(
        flags.enableQueueAllocationPreview,
        user,
        access,
        value.viewMode.maybePointInTime.nonEmpty
      )).getOrElse(false)

  override protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case action: PreviewAction =>
      val original = value.allocationPreview
      val guarded = if (original.context.exists(c => c.eligible && !validContext(c)))
        AllocationPreviewState(generation = original.generation + 1)
      else original
      val guardedAction = action match {
        case SetPreviewContext(context) => SetPreviewContext(context.copy(eligible = validContext(context)))
        case other                      => other
      }
      val next = AllocationPreviewState.transition(guarded, guardedAction)
      if (next == original) noChange
      else if (next.pending && next.generation != original.generation)
        updated(
          value.copy(allocationPreview = next),
          Effect(AllocationPreviewHandler.calculate(next.context.get, next.generation, next.pendingKind.get))
        )
      else updated(value.copy(allocationPreview = next))
  }
}

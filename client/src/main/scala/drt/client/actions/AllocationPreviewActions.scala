package drt.client.actions

import diode.Action
import drt.client.services.AllocationPreviewState.Context
import drt.shared.AllocationPreview.Response

object AllocationPreviewActions {
  sealed trait PreviewAction extends Action
  case class SetPreviewContext(context: Context) extends PreviewAction
  case class PrepareNeutralAllocation(kind: String) extends PreviewAction
  case class SelectNeutralAllocation(neutral: Boolean, kind: String = "recommended") extends PreviewAction
  case object RetryNeutralAllocation extends PreviewAction
  case object ExitAllocationPreview extends PreviewAction
  case class PreviewReceived(generation: Long, response: Response) extends PreviewAction
  case class PreviewRequestFailed(generation: Long, status: Int) extends PreviewAction
}

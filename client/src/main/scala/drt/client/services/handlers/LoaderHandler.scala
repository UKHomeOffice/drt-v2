package drt.client.services.handlers

import diode.{ActionResult, ModelRW}
import drt.client.actions.Actions.{HideLoader, ShowLoader}
import drt.client.services.LoadingState

class LoaderHandler[M](modelRW: ModelRW[M, LoadingState]) extends LoggingActionHandler(modelRW) {
  protected def handle: PartialFunction[Any, ActionResult[M]] = {
    case ShowLoader() => updated(LoadingState(isLoading = true))
    case HideLoader() => updated(LoadingState(isLoading = false))
  }
}

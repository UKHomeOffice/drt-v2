package drt.client.services.handlers


import diode.{ActionResult, ModelRW}
case object ShowAccessibilityStatement
case object HideAccessibilityStatement

class AccessibilityStatementHandler[M](modelRW: ModelRW[M, Boolean]) extends LoggingActionHandler(modelRW) {
  override def handle: PartialFunction[Any, ActionResult[M]] = {
    case ShowAccessibilityStatement =>
      updated(true)
    case HideAccessibilityStatement =>
      updated(false)
  }
}

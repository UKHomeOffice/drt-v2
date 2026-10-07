package drt.client.components

import org.scalajs.dom

import scala.scalajs.js

object FocusTracker {
  private var lastFocusedId: Option[String] = None
  private var lastInteractionWasKeyboard = false
  private var initialised = false

  def init(): Unit = {
    if (!initialised) {
      initialised = true
      // Capture before component handlers can replace the focused control or stop propagation.
      dom.document.addEventListener("keydown", (_: dom.KeyboardEvent) => {
        lastInteractionWasKeyboard = true
        recordFocus(dom.document.activeElement)
      }, useCapture = true)
      dom.document.addEventListener("pointerdown", (_: dom.PointerEvent) => clearFocusTarget(), useCapture = true)
      dom.document.addEventListener("mousedown", (_: dom.MouseEvent) => clearFocusTarget(), useCapture = true)
      dom.document.addEventListener("touchstart", (_: dom.TouchEvent) => clearFocusTarget(), useCapture = true)
      dom.document.addEventListener(
        "focusin",
        (e: dom.FocusEvent) => {
          if (lastInteractionWasKeyboard) {
            recordFocus(e.target)
          }
        },
        useCapture = true
      )
    }
  }

  def restore(): Unit = {
    val activeElement = dom.document.activeElement
    val focusLost = activeElement == null || activeElement == dom.document.body ||
      activeElement == dom.document.documentElement
    for {
      id <- lastFocusedId if lastInteractionWasKeyboard && focusLost
      el <- Option(dom.document.getElementById(id))
    } {
      el.asInstanceOf[js.Dynamic].focus(js.Dynamic.literal("preventScroll" -> true))
    }
  }

  private def recordFocus(target: dom.EventTarget): Unit = {
    lastFocusedId = target match {
      case el: dom.html.Element if el.id.nonEmpty && el != dom.document.body && el != dom.document.documentElement =>
        Some(el.id)
      case _ => None
    }
  }

  private def clearFocusTarget(): Unit = {
    lastInteractionWasKeyboard = false
    lastFocusedId = None
  }

  private[components] def resetForTests(): Unit = clearFocusTarget()
}

package drt.client.components

import org.scalajs.dom

object FocusTracker {
  private var lastFocusedId: Option[String] = None

  def init(): Unit = {
    dom.document.addEventListener(
      "focusin",
      (e: dom.FocusEvent) => {
        e.target match {
          case el: dom.html.Element if el.id.nonEmpty =>
            lastFocusedId = Some(el.id)
          case _ =>
        }
      }
    )
  }

  def restore(): Unit = {
    val focusLost = dom.document.activeElement match {
      case ae => ae == dom.document.body || ae == dom.document.documentElement
      case _  => true
    }
    for {
      id <- lastFocusedId if focusLost
      el <- Option(dom.document.getElementById(id))
    } {
      el.asInstanceOf[dom.html.Element].focus()
    }
  }
}

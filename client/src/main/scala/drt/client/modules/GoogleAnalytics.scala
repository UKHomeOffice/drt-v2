package drt.client.modules

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

object GoogleEventTracker {
  def trackingCode: String = dom.document.getElementById("ga-code").getAttribute("value")

  def port: String = dom.document.getElementById("port-code").getAttribute("value")

  def sendPageView(title: String, url: String): Unit = {
      if (trackingCode.nonEmpty) {
        GoogleAnalytics.gtag("event", "page_view", js.Dictionary(
          "page_title" -> title,
          "page_location" -> url,
        ))
      }
  }

  def sendEvent(category: String, action: String, label: String): Unit = {
    if (trackingCode.nonEmpty) GoogleAnalytics.gtag("event", s"${port}_$category", js.Dictionary("action" -> action, "label" -> label))
  }

  def sendEvent(category: String, action: String, label: String, value: String): Unit = {
    if (trackingCode.nonEmpty) GoogleAnalytics.gtag("event", s"${port}_$category", js.Dictionary("action" -> action, "label" -> label, "value" -> value))
  }

  def sendError(description: String, fatal: Boolean): Unit = {
    if (trackingCode.nonEmpty) GoogleAnalytics.gtag("event", "exception", js.Dictionary("exDescription" -> description, "exFatal" -> fatal))
  }
}


@js.native
@JSGlobalScope
object GoogleAnalytics extends js.Any {
  def gtag(event: String, eventName: String, fieldObjs: js.Any): Unit = js.native
}

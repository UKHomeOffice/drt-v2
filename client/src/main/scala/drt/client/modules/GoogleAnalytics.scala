package drt.client.modules

import java.util.UUID

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

object GoogleEventTracker {
  def trackingCode: String = dom.document.getElementById("ga-code").getAttribute("value")
  def port: String = dom.document.getElementById("port-code").getAttribute("value")
  def userId: String = dom.document.getElementById("user-id").getAttribute("value")
  def isScriptLoaded: Boolean = js.Dynamic.global.analytics.isInstanceOf[js.Function]
  private var hasCreateTrackerRun = false

  private def runCreateTracker(): Unit = {
    if (!hasCreateTrackerRun && !userId.isEmpty && !port.isEmpty && !trackingCode.isEmpty) {
      val userUUID = if (userId.contains("@")) UUID.randomUUID else userId
      GoogleAnalytics.analytics("create", trackingCode, "auto", js.Dictionary("userId"->userUUID))
      hasCreateTrackerRun = true
    }
  }

  def sendPageView(page: String): Unit = {
    if (isScriptLoaded) {
      runCreateTracker()
      if (hasCreateTrackerRun) {
        GoogleAnalytics.analytics("set", "page", s"/$port/$page")
        GoogleAnalytics.analytics("send", "pageView")
      }
    }
  }

  def sendEvent(category: String, action: String, label: String): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.analytics("send", "event", s"$port-$category", action, label)
  }

  def sendEvent(category: String, action: String, label: String, value: String): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.analytics("send", "event", s"$port-$category", action, label, value)
  }

  def sendError(description: String, fatal: Boolean): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.analytics("send", "exception", js.Dictionary("exDescription" -> description, "exFatal" -> fatal))
  }
}

@js.native
@JSGlobalScope
object GoogleAnalytics extends js.Any {
  def analytics(send: String, event: String): Unit = js.native

  def analytics(send: String, event: String, category: String): Unit = js.native

  def analytics(send: String, event: String, fieldObjs: js.Any): Unit = js.native

  def analytics(send: String, event: String, category: String, action: String): Unit = js.native

  def analytics(send: String, event: String, category: String, fieldObjs: js.Any): Unit = js.native

  def analytics(send: String, event: String, category: String, action: String, label: String): Unit = js.native

  def analytics(send: String, event: String, category: String, action: String, label: String, value: js.Any): Unit = js.native
}

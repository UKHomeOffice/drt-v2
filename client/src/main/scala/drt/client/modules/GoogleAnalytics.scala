package drt.client.modules

import org.scalajs.dom
import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

object GoogleEventTracker {
  val trackingCode: GoogleTrackingCode = GoogleTrackingCode.getTrackingCode(dom.document.location.href)
  def port: String = dom.document.getElementById("port-code").getAttribute("value")
  def userId: String = dom.document.getElementById("user-id").getAttribute("value")
  def isScriptLoaded: Boolean = js.Dynamic.global.analytics.isInstanceOf[js.Function]
  private var hasCreateTrackerRun = false

  private def runCreateTracker(): Unit = {
    if (!hasCreateTrackerRun && !userId.isEmpty && !port.isEmpty) {
      GoogleAnalytics.analytics("create", trackingCode.code, "auto", js.Dictionary("userId"->userId))
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

trait GoogleTrackingCode {
  val code: String
}

case object ProductionTrackingCode extends GoogleTrackingCode {
  val code = "UA-125317287-1"
}
case object TestTrackingCode extends GoogleTrackingCode {
  val code = "UA-125317287-2"
}

object GoogleTrackingCode {
  def getTrackingCode(location: String): GoogleTrackingCode = {
    if (location.contains("demand.bf.drt") || location.contains("prod") && !location.contains("notprod")) {
      ProductionTrackingCode
    }
    else {
      TestTrackingCode
    }
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

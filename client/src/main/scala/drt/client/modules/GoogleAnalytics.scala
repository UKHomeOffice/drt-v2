package drt.client.modules

import org.scalajs.dom

import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope
import scala.util.Try
import com.dedipresta.crypto.hash.sha256.Sha256

object GoogleEventTracker {

  @js.native
  @JSGlobalScope
  object Globals extends js.Object {
    var gtag: js.Any = js.native
  }

  def trackingCode: String = dom.document.getElementById("ga-code").getAttribute("value")

  def port: String = dom.document.getElementById("port-code").getAttribute("value")

  def userId: String = dom.document.getElementById("user-id").getAttribute("value")

  def isScriptLoaded: Boolean = Try(Globals.gtag.isInstanceOf[js.Function]).isSuccess

  private var hasCreateTrackerRun = false

  private def runCreateTracker(): Unit = {
    if (!hasCreateTrackerRun && userId.nonEmpty && port.nonEmpty && trackingCode.nonEmpty) {
      val userUUID = if (userId.contains("@")) Sha256.hashString(userId) else userId
      GoogleAnalytics.gtag("config", trackingCode, js.Dictionary("user_id" -> userUUID, "anonymize_ip" -> true))
      hasCreateTrackerRun = true
    }
  }

  def sendPageView(page: String): Unit = {
    if (isScriptLoaded) {
      runCreateTracker()
      if (hasCreateTrackerRun) {
        GoogleAnalytics.gtag("event", "page_view", js.Dictionary("page" -> s"/$port/$page"))
      }
    }
  }

  def sendEvent(category: String, action: String, label: String): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.gtag("event", s"${port}_$category", js.Dictionary("action" -> action, "label" -> label))
  }

  def sendEvent(category: String, action: String, label: String, value: String): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.gtag("event", s"${port}_$category", js.Dictionary("action" -> action, "label" -> label, "value" -> value))
  }

  def sendError(description: String, fatal: Boolean): Unit = {
    if (isScriptLoaded && hasCreateTrackerRun) GoogleAnalytics.gtag("event", "exception", js.Dictionary("exDescription" -> description, "exFatal" -> fatal))
  }
}


@js.native
@JSGlobalScope
object GoogleAnalytics extends js.Any {
  def gtag(event: String, eventName: String, fieldObjs: js.Any): Unit = js.native
}

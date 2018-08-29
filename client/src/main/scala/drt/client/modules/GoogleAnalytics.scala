package drt.client.modules


import scala.scalajs.js
import scala.scalajs.js.annotation.JSGlobalScope

object GoogleEventTracker {
  def isScriptLoaded: Boolean =js.Dynamic.global.analytics.isInstanceOf[js.Function]
  def sendPageView(page:String):Unit={
    if (isScriptLoaded) GoogleAnalytics.analytics("send","event","pageView", page)
  }
  def sendEvent(category:String,action:String,label:String):Unit={
    if (isScriptLoaded) GoogleAnalytics.analytics("send","event",category,action,label)
  }
  def sendEvent(category:String,action:String,label:String,value:String):Unit={
    if (isScriptLoaded) GoogleAnalytics.analytics("send","event",category,action,label,value)
  }
}

@js.native
@JSGlobalScope
object GoogleAnalytics extends js.Any {
  def analytics(send:String,event:String,category:String,action:String): Unit = js.native
  def analytics(send:String,event:String,category:String,action:String,label:String ): Unit = js.native
  def analytics(send:String,event:String,category:String,action:String,label:String,value:js.Any ): Unit = js.native
}

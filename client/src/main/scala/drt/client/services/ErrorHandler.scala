package drt.client.services

import drt.client.logger.LoggerFactory
import drt.client.modules.GoogleEventTracker
import org.scalajs.dom
import org.scalajs.dom.Event

object ErrorHandler {
  def registerGlobalErrorHandler(): Unit = {
    LoggerFactory.getLogger("ErrorHandler").debug("Registering global error handler for uncaught exceptions")
    dom.window.onerror = (ev: Event, url: String, line: Int, col: Int, error: Any) => {
      val serverLogger = LoggerFactory.getXHRLogger("error")

      val message = s"Event: $ev, Url: $url, Line: $line, Col: $col, Error: $error, Browser: ${dom.window.navigator.appVersion}"
      GoogleEventTracker.sendError(message, fatal = true)

      error match {
        case e: Exception =>
          serverLogger.error(message, e)
        case _ =>
          serverLogger.error(message)
      }
      val reload = dom.window.confirm("Sorry, we have encountered an error. The error has been logged. Would you like to reload the page?")
      if (reload) {
        dom.window.location.reload(true)
      }
    }
  }
}

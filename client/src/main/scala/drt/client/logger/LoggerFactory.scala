package drt.client.logger

import drt.client.SPAMain

trait Logger {
  def trace(msg: String, e: Exception): Unit
  def trace(msg: String): Unit
  def debug(msg: String, e: Exception): Unit
  def debug(msg: String): Unit
  def info(msg: String, e: Exception): Unit
  def info(msg: String): Unit
  def warn(msg: String, e: Exception): Unit
  def warn(msg: String): Unit
  def error(msg: String, e: Exception): Unit
  def error(msg: String): Unit
  def fatal(msg: String, e: Exception): Unit
  def fatal(msg: String): Unit

  def enableServerLogging(url: String): Unit
  def disableServerLogging(): Unit
}


object LoggerFactory {
  private[logger] def createLogger(name: String): Unit = {}

  lazy val consoleAppender = new BrowserConsoleAppender
  lazy val popupAppender = new PopUpAppender
  lazy val ajaxAppender = new AjaxAppender(SPAMain.serverLogEndpoint)

  /**
   * Create a logger that outputs to browser console
   */
  def getLogger(name: String): Logger = {
    val nativeLogger = Log4JavaScript.getLogger(name)
    nativeLogger.addAppender(consoleAppender)
    new L4JSLogger(nativeLogger)
  }

  /**
   * Create a logger that sends logs to the server
   */
  def getXHRLogger(name: String): Logger = {
    val nativeLogger = Log4JavaScript.getLogger(name)
    nativeLogger.addAppender(ajaxAppender)
    new L4JSLogger(nativeLogger)
  }

  /**
   * Create a logger that outputs to a separate popup window
   */
  def getPopUpLogger(name: String): Logger = {
    val nativeLogger = Log4JavaScript.getLogger(name)
    nativeLogger.addAppender(popupAppender)
    new L4JSLogger(nativeLogger)
  }
}

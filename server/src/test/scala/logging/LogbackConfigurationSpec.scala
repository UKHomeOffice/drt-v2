package logging

import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.util.LogbackMDCAdapter
import ch.qos.logback.classic.{ AsyncAppender, LoggerContext }
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.status.Status
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult
import org.slf4j.Logger

import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters._

class LogbackConfigurationSpec extends Specification {
  "The application logging configuration" should {
    "emit messages and exception stack traces with the default output format" in {
      checkConsoleOutput(textOutput = false)
    }

    "emit messages and exception stack traces with readable text enabled" in {
      checkConsoleOutput(textOutput = true)
    }
  }

  private def checkConsoleOutput(textOutput: Boolean): MatchResult[String] = {
    val context = new LoggerContext
    context.setMDCAdapter(new LogbackMDCAdapter)
    val output = new ByteArrayOutputStream
    context.putProperty("ENV_LOG_LEVEL", "DEBUG")
    context.putProperty("SLICK_DEBUG_LEVEL", "WARN")
    if (textOutput) context.putProperty("NO_JSON_LOGGING", "true")
    val configurator = new JoranConfigurator
    configurator.setContext(context)

    try {
      configurator.doConfigure(getClass.getClassLoader.getResource("logback.xml"))
      val errors = context.getStatusManager.getCopyOfStatusList.asScala.filter(_.getLevel == Status.ERROR)
      errors must beEmpty

      val asyncAppender = context.getLogger(Logger.ROOT_LOGGER_NAME).getAppender("ASYNC") match {
        case appender: AsyncAppender => appender
        case unexpected => throw new IllegalStateException(s"Expected an async appender, got $unexpected")
      }
      val consoleAppender = asyncAppender.getAppender("STDOUT") match {
        case appender: ConsoleAppender[_] => appender
        case unexpected => throw new IllegalStateException(s"Expected a console appender, got $unexpected")
      }
      consoleAppender.isStarted must beTrue
      consoleAppender.setOutputStream(output)

      val logger = context.getLogger("drt.server.feeds.legacy.bhx.BHXForecastFeedLegacy")
      logger.debug("BHX debug remains enabled")
      logger.info("About to get BHX forecast arrivals.")
      logger.warn("Failed to fetch BHX forecast arrivals.", new SocketTimeoutException("Diagnostic timeout"))
      Seq("slick.jdbc.JdbcBackend.statement", "slick.jdbc.JdbcBackend.benchmark", "slick.basic.BasicBackend")
        .foreach { name =>
          val slickLogger = context.getLogger(name)
          slickLogger.debug("Slick debug noise")
          slickLogger.info("Slick info noise")
        }
      context.getLogger("slick.jdbc.JdbcBackend.statement").warn("Slick warning remains visible")
      asyncAppender.stop()

      val logged = output.toString(StandardCharsets.UTF_8.name())
      logged must contain("BHX debug remains enabled")
      logged must not contain("Slick debug noise")
      logged must not contain("Slick info noise")
      logged.linesIterator.count(_.contains("Slick warning remains visible")) mustEqual 1
      logged must contain("About to get BHX forecast arrivals.")
      logged must contain("Failed to fetch BHX forecast arrivals.")
      logged must contain("java.net.SocketTimeoutException: Diagnostic timeout")
      if (textOutput || sys.env.contains("NO_JSON_LOGGING") || sys.props.contains("NO_JSON_LOGGING"))
        logged must contain("[WARN]")
      else
        logged must contain("\"level\":\"WARN\"")
    } finally context.stop()
  }
}

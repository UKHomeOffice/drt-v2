package services.mocklogger

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Appender
import org.mockito.ArgumentMatcher
import org.mockito.Matchers.argThat
import org.mockito.Mockito.{mock, verify, when}
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification

trait MockLoggingLike {
  def withMockAppender[R](f: (Appender[ILoggingEvent]) => R): R = {
    val root: ch.qos.logback.classic.Logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
    val mockAppender: Appender[ILoggingEvent] = mock(classOf[Appender[ILoggingEvent]])
    when(mockAppender.getName()).thenReturn("MOCK")
    root.addAppender(mockAppender)
    val r: R = try {
      f(mockAppender)
    } finally {
      root.detachAppender(mockAppender)
    }
    r
  }

  def logMessageMatcher(contains: String, level: Level = Level.INFO) = new ArgumentMatcher() {
    def matches(argument: scala.Any): Boolean = {
      val le: ILoggingEvent = argument.asInstanceOf[ILoggingEvent]
      le.getFormattedMessage().contains(contains) && le.getLevel() == level
    }
  }
}

object MockLogging extends MockLoggingLike

class CanMockLogging extends Specification with MockLoggingLike {
  sequential
  "can mock logging" >> {
    withMockAppender { mockAppender =>
      val root: ch.qos.logback.classic.Logger = LoggerFactory.getLogger("").asInstanceOf[ch.qos.logback.classic.Logger]

      root.info("hello there")

      verify(mockAppender).doAppend(argThat(logMessageMatcher("hello there")))

      success
    }
  }
  "can assert about the log level" >> {
    withMockAppender { mockAppender =>
      val logger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)

      logger.error("nobody there")

      verify(mockAppender).doAppend(argThat(logMessageMatcher("nobody there", Level.ERROR)))

      success
    }
  }
  "can assert on subloggers" >> {
    withMockAppender { mockAppender =>
      val logger = LoggerFactory.getLogger("drt.test.123")

      logger.error("nobody there")

      verify(mockAppender).doAppend(argThat(logMessageMatcher("nobody there", Level.ERROR)))

      success
    }
  }


}

package services

import akka.stream.{ActorAttributes, Attributes, Supervision}
import org.slf4j.{Logger, LoggerFactory}

object StreamSupervision {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def resumeStrategyWithLog(description: String): Attributes =
    ActorAttributes.supervisionStrategy(resumeWithLog(description))

  def resumeWithLog(description: String): Supervision.Decider = {
    exception =>
      LoggerFactory.getLogger(description).error(s"Resuming from exception in stream", exception)
      Supervision.Resume
  }
}

package services.`export`

import akka.actor.{Actor, ActorRef}
import services.exports.summaries.{GetSummaries, TerminalSummaryLike}

class MockTerminalSummariesActor(optionalSummaries: Option[TerminalSummaryLike],
                                 maybeTestProbe: Option[ActorRef]) extends Actor {
  override def receive: Receive = {
    case GetSummaries =>
      sender() ! optionalSummaries

    case summaries: TerminalSummaryLike =>
      maybeTestProbe.foreach(_ ! summaries)
      sender() ! "ok"
  }
}

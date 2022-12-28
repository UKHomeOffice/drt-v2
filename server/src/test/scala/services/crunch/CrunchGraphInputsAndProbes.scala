package services.crunch

import akka.actor.ActorRef
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.testkit.TestProbe
import drt.server.feeds.{ArrivalsFeedResponse, ManifestsFeedResponse}
import drt.shared.CrunchApi.ActualDeskStats

case class CrunchGraphInputsAndProbes(aclArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      forecastArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      liveArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      ciriumArrivalsInput: SourceQueueWithComplete[ArrivalsFeedResponse],
                                      manifestsLiveInput: SourceQueueWithComplete[ManifestsFeedResponse],
                                      shiftsInput: ActorRef,
                                      fixedPointsInput: ActorRef,
                                      staffMovementsInput: ActorRef,
                                      actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
                                      portStateTestProbe: TestProbe,
                                      baseArrivalsTestProbe: TestProbe,
                                      forecastArrivalsTestProbe: TestProbe,
                                      liveArrivalsTestProbe: TestProbe,
                                      aggregatedArrivalsActor: ActorRef,
                                      portStateActor: ActorRef) {
  def shutdown(): Unit = {
    aclArrivalsInput.complete()
    forecastArrivalsInput.complete()
    liveArrivalsInput.complete()
    ciriumArrivalsInput.complete()
    manifestsLiveInput.complete()
    actualDesksAndQueuesInput.complete()
  }
}

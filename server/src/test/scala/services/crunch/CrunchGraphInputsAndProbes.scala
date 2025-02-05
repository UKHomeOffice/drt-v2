package services.crunch

import org.apache.pekko.actor.ActorRef
import org.apache.pekko.stream.scaladsl.SourceQueueWithComplete
import org.apache.pekko.testkit.TestProbe
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
                                      portStateActor: ActorRef,
                                     ) {
  def shutdown(): Unit = {
    aclArrivalsInput.complete()
    forecastArrivalsInput.complete()
    liveArrivalsInput.complete()
    ciriumArrivalsInput.complete()
    manifestsLiveInput.complete()
    actualDesksAndQueuesInput.complete()
  }
}

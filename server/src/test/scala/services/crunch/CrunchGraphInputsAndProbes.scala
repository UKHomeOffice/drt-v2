package services.crunch

import akka.actor.{ActorRef, PoisonPill}
import akka.stream.scaladsl.SourceQueueWithComplete
import akka.testkit.TestProbe
import drt.shared.CrunchApi.ActualDeskStats
import drt.shared.{FixedPointAssignments, ShiftAssignments, StaffMovement}
import server.feeds.ManifestsFeedResponse

case class CrunchGraphInputsAndProbes(aclArrivalsInput: ActorRef,
                                      forecastArrivalsInput: ActorRef,
                                      liveArrivalsInput: ActorRef,
                                      ciriumArrivalsInput: ActorRef,
                                      manifestsLiveInput: SourceQueueWithComplete[ManifestsFeedResponse],
                                      shiftsInput: SourceQueueWithComplete[ShiftAssignments],
                                      fixedPointsInput: SourceQueueWithComplete[FixedPointAssignments],
                                      liveStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                                      forecastStaffMovementsInput: SourceQueueWithComplete[Seq[StaffMovement]],
                                      actualDesksAndQueuesInput: SourceQueueWithComplete[ActualDeskStats],
                                      portStateTestProbe: TestProbe,
                                      baseArrivalsTestProbe: TestProbe,
                                      forecastArrivalsTestProbe: TestProbe,
                                      liveArrivalsTestProbe: TestProbe,
                                      aggregatedArrivalsActor: ActorRef,
                                      portStateActor: ActorRef) {
  def shutdown(): Unit = {
    aclArrivalsInput ! PoisonPill
    forecastArrivalsInput ! PoisonPill
    liveArrivalsInput ! PoisonPill
    ciriumArrivalsInput ! PoisonPill
    manifestsLiveInput.complete()
    shiftsInput.complete()
    fixedPointsInput.complete()
    liveStaffMovementsInput.complete()
    forecastStaffMovementsInput.complete()
    actualDesksAndQueuesInput.complete()
  }
}

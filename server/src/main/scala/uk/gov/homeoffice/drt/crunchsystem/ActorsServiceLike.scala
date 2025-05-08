package uk.gov.homeoffice.drt.crunchsystem

import org.apache.pekko.actor.ActorRef

trait ActorsServiceLike {
  val requestAndTerminateActor: ActorRef
  val portStateActor: ActorRef
  val legacyShiftAssignmentsReadActor: ActorRef
  val liveShiftAssignmentsReadActor: ActorRef
  val liveFixedPointsReadActor: ActorRef
  val liveStaffMovementsReadActor: ActorRef
  val legacyShiftAssignmentsSequentialWritesActor: ActorRef
  val shiftAssignmentsSequentialWritesActor: ActorRef
  val fixedPointsSequentialWritesActor: ActorRef
  val staffMovementsSequentialWritesActor: ActorRef

  val flightsRouterActor: ActorRef
  val queueLoadsRouterActor: ActorRef
  val queuesRouterActor: ActorRef
  val staffRouterActor: ActorRef
  val queueUpdates: ActorRef
  val staffUpdates: ActorRef
  val flightUpdates: ActorRef
}

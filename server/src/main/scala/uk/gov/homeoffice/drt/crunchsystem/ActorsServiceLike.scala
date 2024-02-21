package uk.gov.homeoffice.drt.crunchsystem

import akka.actor.ActorRef

trait ActorsServiceLike {
  val portStateActor: ActorRef
  val liveShiftsReadActor: ActorRef
  val liveFixedPointsReadActor: ActorRef
  val liveStaffMovementsReadActor: ActorRef

  val flightsRouterActor: ActorRef
  val queueLoadsRouterActor: ActorRef
  val queuesRouterActor: ActorRef
  val staffRouterActor: ActorRef
  val queueUpdates: ActorRef
  val staffUpdates: ActorRef
  val flightUpdates: ActorRef
}

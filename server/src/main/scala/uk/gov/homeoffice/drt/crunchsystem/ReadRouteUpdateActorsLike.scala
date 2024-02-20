package uk.gov.homeoffice.drt.crunchsystem

import actors.MinuteLookupsLike
import akka.actor.ActorRef

trait ReadRouteUpdateActorsLike {
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

package uk.gov.homeoffice.drt.crunchsystem

import akka.actor.ActorRef

trait PersistentStateActors {
  val shiftsActor: ActorRef
  val fixedPointsActor: ActorRef
  val staffMovementsActor: ActorRef

  val forecastBaseArrivalsActor: ActorRef
  val forecastArrivalsActor: ActorRef
  val liveArrivalsActor: ActorRef
  val liveBaseArrivalsActor: ActorRef
  val manifestsRouterActor: ActorRef

  val crunchQueueActor: ActorRef
  val deskRecsQueueActor: ActorRef
  val deploymentQueueActor: ActorRef
  val staffingQueueActor: ActorRef

  val aggregatedArrivalsActor: ActorRef
}

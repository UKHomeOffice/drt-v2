package uk.gov.homeoffice.drt.crunchsystem

import akka.actor.ActorRef

trait PersistentStateActors {
  val manifestsRouterActor: ActorRef

  val mergeArrivalsQueueActor: ActorRef
  val crunchQueueActor: ActorRef
  val deskRecsQueueActor: ActorRef
  val deploymentQueueActor: ActorRef
  val staffingQueueActor: ActorRef

  val aggregatedArrivalsActor: ActorRef
}

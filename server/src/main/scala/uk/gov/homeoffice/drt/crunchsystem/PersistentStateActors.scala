package uk.gov.homeoffice.drt.crunchsystem

import org.apache.pekko.actor.ActorRef

trait PersistentStateActors {
  val manifestsRouterActor: ActorRef

  val mergeArrivalsQueueActor: ActorRef
  val crunchQueueActor: ActorRef
  val deskRecsQueueActor: ActorRef
  val deploymentQueueActor: ActorRef
  val staffingQueueActor: ActorRef
}

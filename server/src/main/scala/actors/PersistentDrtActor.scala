package actors

import akka.persistence.PersistentActor

trait PersistentDrtActor[T] extends PersistentActor {

  var state: T

  def initialState: T
}

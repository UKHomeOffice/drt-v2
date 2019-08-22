package actors

trait PersistentDrtActor[T] {

  def state: T

  def initialState: T
}

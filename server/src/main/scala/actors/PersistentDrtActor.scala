package actors

trait PersistentDrtActor[T] {

  var state: T

  def initialState: T
}

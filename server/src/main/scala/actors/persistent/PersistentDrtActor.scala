package actors.persistent

trait PersistentDrtActor[T] {

  def state: T

  def initialState: T
}

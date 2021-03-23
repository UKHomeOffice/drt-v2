package actors.restore

import drt.shared.api.Arrival
import drt.shared.{LegacyUniqueArrival, UniqueArrivalLike, UniqueArrival}

import scala.collection.{SortedMap, mutable}


class RestorerWithLegacy {
  var items: SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()

  def removeHashLegacies(theRemoves: Iterable[Int]): Unit = theRemoves.foreach(keyToRemove => items = items.filterKeys(_.legacyUniqueId != keyToRemove))

  def remove(theRemoves: Iterable[UniqueArrivalLike]): Unit = {
    val keys = theRemoves.collect { case k: UniqueArrival => k }
    items = items -- keys
    val legacyKeys = theRemoves.collect { case lk: LegacyUniqueArrival => lk }
    if (legacyKeys.nonEmpty) {
      items = legacyKeys.foldLeft(items) {
        case (acc, legacyKey) => acc.filterKeys(_.legacyUniqueArrival != legacyKey)
      }
    }
  }

  def update(theUpdates: Iterable[Arrival]): Unit = theUpdates.foreach { update =>
    items = items + ((update.unique, update))
  }

  def finish(): Unit = items = SortedMap()
}

package actors.restore

import drt.shared.api.Arrival
import drt.shared.{LegacyUniqueArrival, UniqueArrivalLike, UniqueArrivalWithOrigin, WithLegacyUniqueId, WithUnique}

import scala.collection.{SortedMap, mutable}


class RestorerWithLegacy[LI, I <: WithLegacyUniqueId[LI, I], A <: WithUnique[I]] {
  val legacyMap: mutable.Map[LI, I] = mutable.Map()
  val items: mutable.SortedMap[I, A] = mutable.SortedMap[I, A]()

  def removeLegacies(theRemoves: Iterable[LI]): Unit = theRemoves.foreach(removeWithLegacyIdx)

  def remove(theRemoves: Iterable[I]): Unit = theRemoves.foreach { toRemoveNew =>
    val legacyIdx = toRemoveNew.legacyUniqueId
    removeWithLegacyIdx(legacyIdx)
  }

  def update(theUpdates: Iterable[A]): Unit = theUpdates.foreach { update =>
    val index = update.unique
    val legacyIdx = index.legacyUniqueId
    legacyMap += (legacyIdx -> index)
    items += (index -> update)
  }

  def finish(): Unit = legacyMap.clear()

  def clear(): Unit = {
    legacyMap.clear()
    items.clear()
  }

  private def removeWithLegacyIdx(toRemove: LI): Unit = {
    legacyMap.get(toRemove).foreach { idx =>
      items -= idx
      legacyMap -= toRemove
    }
  }
}

class RestorerWithLegacy2 {
  var items: SortedMap[UniqueArrivalWithOrigin, Arrival] = mutable.SortedMap()

  def removeLegacies2(theRemoves: Iterable[Int]): Unit = theRemoves.foreach(keyToRemove => items = items.filterKeys(_.legacyUniqueId != keyToRemove))

  def removeLegacies1(theRemoves: Iterable[LegacyUniqueArrival]): Unit = theRemoves.foreach(keyToRemove => items = items.filterKeys(_.legacyUniqueArrival != keyToRemove))

  def remove(theRemoves: Iterable[UniqueArrivalLike]): Unit = {
    val keys = theRemoves.collect { case k: UniqueArrivalWithOrigin => k }
    items = items -- keys
    val legacyKeys = theRemoves.collect { case lk: LegacyUniqueArrival => lk }
    if (legacyKeys.nonEmpty) {
      items = legacyKeys.foldLeft(items) {
        case (acc, legacyKey) => acc.filterKeys(_.legacyUniqueArrival != legacyKey)
      }
    }
  }

  def update(theUpdates: Iterable[Arrival]): Unit = theUpdates.foreach { update =>
    val index = update.unique
    items = items + ((index, update))
  }
}

package actors.restore

import drt.shared.{WithLegacyUniqueId, WithUnique}

import scala.collection.mutable


class RestorerWithLegacy[LI, I <: WithLegacyUniqueId[LI, I], A <: WithUnique[I]] {
  val legacyMap: mutable.Map[LI, I] = mutable.Map()
  val items: mutable.SortedMap[I, A] = mutable.SortedMap[I, A]()

  def removeLegacies(theRemoves: Iterable[LI]): Unit = theRemoves.foreach(removeWithLegacyIdx)
  def remove(theRemoves: Iterable[I]): Unit = theRemoves.foreach { toRemoveNew =>
    val legacyIdx = toRemoveNew.uniqueId
    removeWithLegacyIdx(legacyIdx)
  }

  def update(theUpdates: Iterable[A]): Unit = theUpdates.foreach { update =>
    val index = update.unique
    val legacyIdx = index.uniqueId
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

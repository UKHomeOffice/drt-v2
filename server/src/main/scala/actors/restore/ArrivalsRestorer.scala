package actors.restore

import drt.shared.api.Arrival
import drt.shared.{LegacyUniqueArrival, UniqueArrivalLike, UniqueArrival}

import scala.collection.{SortedMap, mutable}


//class ArrivalsRestorer {
//  var arrivals: SortedMap[UniqueArrival, Arrival] = mutable.SortedMap()
//
//  def removeHashLegacies(theRemoves: Iterable[Int]): Unit = theRemoves.foreach(keyToRemove => arrivals = arrivals.filterKeys(_.legacyUniqueId != keyToRemove))
//
//  def update(theUpdates: Iterable[Arrival]): Unit = theUpdates.foreach { update =>
//    arrivals = arrivals + ((update.unique, update))
//  }
//
//  def remove(removals: Iterable[UniqueArrivalLike]): Unit =
//    arrivals = ArrivalsRemoval.remove(removals, arrivals)
//
//  def finish(): Unit = arrivals = SortedMap()
//}
//
//object ArrivalsRemoval {
//  def remove(removals: Iterable[UniqueArrivalLike], arrivals: SortedMap[UniqueArrival, Arrival]): SortedMap[UniqueArrival, Arrival] = {
//    val keys = removals.collect { case k: UniqueArrival => k }
//    val minusRemovals = arrivals -- keys
//    val legacyKeys = removals.collect { case lk: LegacyUniqueArrival => lk }
//    if (legacyKeys.nonEmpty) {
//      legacyKeys.foldLeft(minusRemovals) {
//        case (acc, legacyKey) => acc.filterKeys(_.legacyUniqueArrival != legacyKey)
//      }
//    } else minusRemovals
//  }
//}

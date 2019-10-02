package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.TerminalName

import scala.collection.immutable.{SortedMap => ISortedMap}
import scala.collection.mutable.{ListBuffer, SortedMap => MSortedMap}
import scala.collection.{SortedMap, mutable}


abstract class IndexedByTerminal[K <: WithTerminal[K], A <: WithLastUpdated] {
  protected val items: mutable.Map[TerminalName, MSortedMap[K, A]] = mutable.Map()
  var lastUpdated: MillisSinceEpoch = 0L
  val recentUpdates: mutable.SortedMap[MillisSinceEpoch, ListBuffer[(K, A)]] = mutable.SortedMap()

  def ++=(toAdd: SortedMap[K, A]): Unit = toAdd.groupBy(_._1.terminal).foreach {
    case (t, things) =>
      updateTerminalItems(t, things)
      updateRecentUpdates(toAdd)
  }

  private def updateRecentUpdates(toAdd: SortedMap[K, A]): Unit = {
    toAdd.filter(_._2.lastUpdated.getOrElse(0L) > lastUpdated).groupBy(_._2.lastUpdated.getOrElse(0L)).foreach {
      case (lu, itemsUpdated) =>
        if (recentUpdates.contains(lu)) {
          recentUpdates(lu) ++= itemsUpdated
        } else {
          recentUpdates(lu) = ListBuffer[(K, A)]() ++= itemsUpdated
        }
    }
    val keys = recentUpdates.keys
    lastUpdated = if (keys.nonEmpty) keys.max else 0L
    val toDrop = keys.filter(_ < lastUpdated - 15 * 60 * 1000)
    recentUpdates --= toDrop
  }

  private def updateTerminalItems(t: TerminalName, things: SortedMap[K, A]): Unit = {
    if (items.contains(t)) {
      items(t) ++= things
    } else {
      items(t) = MSortedMap[K, A]() ++= things
    }
  }

  def ++=(toAdd: Seq[(K, A)]): Unit = ++=(SortedMap[K, A]() ++ toAdd)

  def +++=(toAdd: Seq[A]): Unit

  def --=(toRemove: Seq[K]): Unit = toRemove.groupBy(_.terminal).foreach {
    case (t, things) => if (items.contains(t)) items(t) --= things
  }

  def get: SortedMap[K, A] = {
    items.foldLeft(SortedMap[K, A]()) { case (acc, (_, tItems)) => acc ++ tItems }
  }

  def getByKey(key: K): Option[A] = items.get(key.terminal).flatMap(_.get(key))

  def count: Int = if (items.nonEmpty) items.map(_._2.size).sum else 0

  def atTime: MillisSinceEpoch => K

  def range(roundedStart: SDateLike, roundedEnd: SDateLike): ISortedMap[K, A] = {
    val start = atTime(roundedStart.millisSinceEpoch)
    val end = atTime(roundedEnd.millisSinceEpoch)
    items.foldLeft(ISortedMap[K, A]()) { case (acc, (_, tItems)) => acc ++ tItems.range(start, end) }
  }

  def purgeOlderThanDate(thresholdMillis: MillisSinceEpoch): Unit = items.foreach {
    case (_, tItems) => purgeExpired(tItems, atTime, thresholdMillis)
  }

  def purgeRecentUpdates(thresholdMillis: MillisSinceEpoch): Unit = purgeExpired(recentUpdates, (m: MillisSinceEpoch) => m, thresholdMillis)

  def purgeExpired[X, Y](expireable: mutable.SortedMap[X, Y], atTime: MillisSinceEpoch => X, thresholdMillis: MillisSinceEpoch): Unit = {
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis - 1))
    expireable --= expired.keys
  }

  def clear(): Unit = items.foreach(_._2.clear())

  def updatesSince(sinceEpoch: MillisSinceEpoch): Set[A] = if (recentUpdates.isEmpty) {
    Set()
  } else {
    recentUpdates.filterKeys(_ > sinceEpoch).foldLeft(List[(K, A)]()) {
      case (acc, (_, ups)) => acc ++ ups
    }.toMap.values.toSet
  }
}

class IndexedFlights extends IndexedByTerminal[UniqueArrival, ApiFlightWithSplits] {
  val atTime: MillisSinceEpoch => UniqueArrival = UniqueArrival.atTime

  def +++=(toAdd: Seq[ApiFlightWithSplits]): Unit = ++=(toAdd.map(cm => (cm.unique, cm)))
}

class IndexedCrunchMinutes extends IndexedByTerminal[TQM, CrunchMinute] {
  val atTime: MillisSinceEpoch => TQM = TQM.atTime

  def +++=(toAdd: Seq[CrunchMinute]): Unit = ++=(toAdd.map(cm => (cm.key, cm)))
}

class IndexedStaffMinutes extends IndexedByTerminal[TM, StaffMinute] {
  val atTime: MillisSinceEpoch => TM = TM.atTime

  def +++=(toAdd: Seq[StaffMinute]): Unit = ++=(toAdd.map(cm => (cm.key, cm)))
}

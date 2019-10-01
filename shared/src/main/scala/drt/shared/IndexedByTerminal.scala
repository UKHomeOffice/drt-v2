package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, StaffMinute}
import drt.shared.FlightsApi.TerminalName

import scala.collection.immutable.{SortedMap => ISortedMap}
import scala.collection.mutable.{SortedMap => MSortedMap}
import scala.collection.{SortedMap, mutable}


abstract class IndexedByTerminal[K <: WithTerminal[K], A <: WithLastUpdated] {
  protected val items: mutable.Map[TerminalName, MSortedMap[K, A]] = mutable.Map()

  def ++=(toAdd: SortedMap[K, A]): Unit = toAdd.groupBy(_._1.terminal).foreach {
    case (t, things) => if (items.contains(t)) {
      items(t) ++= things
    } else {
      items(t) = MSortedMap[K, A]() ++= things
    }
  }

  def ++=(toAdd: Seq[(K, A)]): Unit = ++=(SortedMap[K, A]() ++ toAdd)

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

  def purgeExpired[X, Y](expireable: mutable.SortedMap[X, Y], atTime: MillisSinceEpoch => X, thresholdMillis: MillisSinceEpoch): Unit = {
    val expired = expireable.range(atTime(0L), atTime(thresholdMillis - 1))
    expireable --= expired.keys
  }

  def clear(): Unit = items.foreach(_._2.clear())

  def updatesSince(sinceEpoch: MillisSinceEpoch): Set[A] = items.foldLeft(Set[A]()) {
    case (acc, (_, tItems)) =>
      acc ++ tItems.filter {
        case (_, item) => item.lastUpdated.getOrElse(0L) > sinceEpoch
      }.values.toSet
  }
}

class IndexedFlights extends IndexedByTerminal[UniqueArrival, ApiFlightWithSplits] {
  val atTime: MillisSinceEpoch => UniqueArrival = UniqueArrival.atTime
}

class IndexedCrunchMinutes extends IndexedByTerminal[TQM, CrunchMinute] {
  val atTime: MillisSinceEpoch => TQM = TQM.atTime
}

class IndexedStaffMinutes extends IndexedByTerminal[TM, StaffMinute] {
  val atTime: MillisSinceEpoch => TM = TM.atTime
}

package services

import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.{SDateLike, TM, TQM, WithTimeAccessor}
import org.specs2.mutable.SpecificationLike
import services.graphstages.Crunch

import scala.collection.mutable


class MutableStateSpec extends SpecificationLike {
  "Something" >> {
    val oneDayMillis = 1000L * 60 * 60 * 24
    val minutes: List[Long] = List.range(1567600000000L, 1567600000000L + (oneDayMillis * 360), 60000)

    val tms = for {
      t <- Seq("T2", "T3", "T4", "T5")
      m <- minutes
    } yield (TM(t, m.toLong), m.toLong)

    println(s"${tms.size} things")
    val things = mutable.SortedMap[TM, Long]() ++= tms

    val start = SDate.now().millisSinceEpoch
    println(s"${things.size} things")

    val like: () => SDateLike = () => SDate("2019-09-17T00:00")

//    println(s"${things.take(100).mkString("\n")}")

    Crunch.purgeExpired(things, TM.atTime, like, oneDayMillis.toInt * 2)
//    val toExpire = things.range(TM("", 0L), TM("", like().millisSinceEpoch - (oneDayMillis * 2)))
//    println(s"${toExpire.size} things")
//    things --= toExpire.keys
//    println(s"${things.size} things")

    timing(start)

    success
  }

  private def timing(start: MillisSinceEpoch): Unit = {
    val end = SDate.now().millisSinceEpoch
    println(s"taken ${end - start}ms")
  }
}

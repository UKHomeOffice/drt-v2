package drt.shared.api

import ujson.Value.Value
import upickle.default.{macroRW, _}

trait PaxAgeRange {
  def title: String
}

case class AgeRange(bottom: Int, top: Option[Int]) extends PaxAgeRange {
  def isInRange(age: Int) = this match {
    case AgeRange(bottom, Some(top)) => age >= bottom && age <= top
    case AgeRange(bottom, None) => age > bottom
  }

  def title: String = top match {
    case Some(top) => s"$bottom-$top"
    case _ => s">$bottom"
  }
}

case object UnknownAge extends PaxAgeRange {
  def title: String = "Unknown"
}

object AgeRange {
  implicit val rw: ReadWriter[AgeRange] = macroRW

  def apply(bottom: Int, top: Int): AgeRange = AgeRange(bottom, Option(top))

  def apply(bottom: Int): AgeRange = AgeRange(bottom, None)
}

object PaxAgeRange {
  implicit val paxAgeReadWriter: ReadWriter[PaxAgeRange] =
    readwriter[Value].bimap[PaxAgeRange](
      par => par.title,
      (title: Value) => parse(title.str)
    )

  def parse(title: String): PaxAgeRange = title.split("-").toList match {
    case _ if title == UnknownAge.title =>
      UnknownAge
    case top :: bottom :: Nil =>
      AgeRange(top.toInt, bottom.toInt)
    case bottom :: Nil =>
      AgeRange(bottom.replace(">", "").toInt)
  }

}


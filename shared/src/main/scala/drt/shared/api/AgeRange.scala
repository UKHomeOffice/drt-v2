package drt.shared.api
import upickle.default.{macroRW, _}

case class AgeRange(bottom: Int, top: Option[Int]) {
  def isInRange(age: Int) = this match {
    case AgeRange(bottom, Some(top)) => age >= bottom && age <= top
    case AgeRange(bottom, None) => age > bottom
  }

  def title: String = top match {
    case Some(top) => s"$bottom-$top"
    case _ => s">$bottom"
  }
}

object AgeRange {
  implicit val rw: ReadWriter[AgeRange] = macroRW

  def apply(bottom: Int, top: Int): AgeRange = AgeRange(bottom, Option(top))

  def apply(bottom: Int): AgeRange = AgeRange(bottom, None)
}


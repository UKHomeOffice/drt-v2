package drt.client.components

import drt.client.services.JSDateConversions.SDate


sealed trait TimeRangeHours {
  def start: String

  def startInt: Int

  def end: String

  def endInt: Int
}

case class CustomWindow(start: String, end: String) extends TimeRangeHours {
  override def startInt: Int = start.split(":")(0).toInt

  override def endInt: Int = if (end.contains("+1"))
    end.split(":")(0).toInt + 24 else end.split(":")(0).toInt
}


case class WholeDayWindow() extends TimeRangeHours {
  override def start: String = "00:00"

  override def end: String = "00:00 +1"

  override def startInt: Int = 0

  override def endInt: Int = 0
}

case class CurrentWindow() extends TimeRangeHours {
  override def start: String = f"${SDate.now().getHours - 1}%02d:00"

  override def end: String = f"${SDate.now().getHours + 3}%02d:00"

  override def startInt: Int = SDate.now().getHours - 1

  override def endInt: Int = SDate.now().getHours + 3
}

object TimeRangeHours {
  def apply(start: String, end: String): CustomWindow = {
    CustomWindow(start, end)
  }
}

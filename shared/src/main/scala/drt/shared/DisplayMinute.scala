package drt.shared

sealed trait DisplayMinute {
  val minutes: Int
  val display: String
}

case class MinuteAsAdjective(minutes: Int) extends DisplayMinute {
  override val display: String = s"$minutes minute"
}

case class MinuteAsNoun(minutes: Int) extends DisplayMinute {
  override val display: String = if (minutes == 1) s"$minutes minute" else s"$minutes minutes"
}

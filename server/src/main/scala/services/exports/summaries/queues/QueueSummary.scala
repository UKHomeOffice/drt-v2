package services.exports.summaries.queues

sealed trait QueueSummaryLike {
  val pax: Double
  val deskRecs: Int
  val waitTime: Int
  val actDesks: Option[Int]
  val actWaitTime: Option[Int]
  val toCsv: String
}

case object EmptyQueueSummary extends QueueSummaryLike {
  override val pax: Double = 0d
  override val deskRecs: Int = 0
  override val waitTime: Int = 0
  override val actDesks: Option[Int] = None
  override val actWaitTime: Option[Int] = None
  override val toCsv: String = "0,0,0,,"
}

case class QueueSummary(pax: Double,
                        deskRecs: Int,
                        waitTime: Int,
                        actDesks: Option[Int],
                        actWaitTime: Option[Int]) extends QueueSummaryLike {
  lazy val toCsv: String = s"${Math.round(pax)},$waitTime,$deskRecs,${actWaitTime.getOrElse("")},${actDesks.getOrElse("")}"
}

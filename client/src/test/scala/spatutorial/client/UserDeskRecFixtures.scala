package spatutorial.client

import diode.data.Ready
import spatutorial.client.services.{DeskRecTimeSlots, DeskRecTimeslot}
import spatutorial.shared.FlightsApi.QueueName

import scala.collection.immutable.Map

/**
  * Created by rich on 04/01/17.
  */
object UserDeskRecFixtures {
  def makeUserDeskRecs(queueName: QueueName, userDesks: Int): Map[QueueName, Ready[DeskRecTimeSlots]] = {
    makeUserDeskRecs(queueName, oneHourOfDeskRecs(userDesks))
  }

  def makeUserDeskRecs(queueName: QueueName, recs: List[Int]): Map[QueueName, Ready[DeskRecTimeSlots]] = {
    val userDeskRecs = Map(
      queueName ->
        Ready(DeskRecTimeSlots(
          Stream.from(0, 60000 * 15).zip(recs).map {
            case (millisSinceEpoch, dr) =>
              DeskRecTimeslot(millisSinceEpoch, dr)
          }.toVector)
        ))
    userDeskRecs
  }

  def oneHourOfDeskRecs(userDesksNonEea: Int): List[Int] = {
    //desk recs are currently in 15 minute blocks
    List.fill(4)(userDesksNonEea)
  }
}

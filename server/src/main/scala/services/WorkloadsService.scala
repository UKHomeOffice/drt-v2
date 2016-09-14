package services

import org.joda.time.DateTime
import spatutorial.shared.FlightsApi.{Flights, Flight}
import spatutorial.shared.FlightsApi

import scala.util.Random

trait FlightsService extends FlightsApi {
  def flights(startTimeEpoch: Long, endTimeEpoch: Long) = Flights(
    Flight(DateTime.now().getMillis, Option(startTimeEpoch), DateTime.now().getMillis, "12345", "BA", 200, None, None) :: Nil)
}

trait WorkloadsService {
  def numberOf15Mins = (24 * 4 * 15)
  def maxLoadPerSlot: Int = 20
  val workload: Seq[Double] = Iterator.continually(Random.nextDouble() * maxLoadPerSlot).take(numberOf15Mins).toSeq


  def getWorkloads(): Seq[Double] = workload
}

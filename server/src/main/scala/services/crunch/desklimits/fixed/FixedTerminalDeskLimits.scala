package services.crunch.desklimits.fixed

import services.WorkloadProcessorsProvider
import services.crunch.CalculationControl
import services.crunch.desklimits.{ ConfiguredTerminalDeskLimitsLike, EmptyCapacityProvider, QueueCapacityProvider }
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.time.LocalDate

import scala.collection.immutable.{ Map, NumericRange }
import scala.concurrent.Future

case class FixedTerminalDeskLimits(
    minDesksByQueue24Hrs: LocalDate => Map[Queue, IndexedSeq[Int]],
    maxDesksByQueue24Hrs: Map[Queue, QueueCapacityProvider],
    paxForQueue: (NumericRange[Long], Queue) => Future[Seq[Int]],
    override val calculationControl: CalculationControl = CalculationControl.Unlimited
) extends ConfiguredTerminalDeskLimitsLike {
  override val capacityByQueue: Map[Queue, QueueCapacityProvider] = maxDesksByQueue24Hrs

  override def maxDesksForMinutes(
      minuteMillis: NumericRange[Long],
      queue: Queue,
      allocatedDesks: Map[Queue, List[Int]]
  ): Future[WorkloadProcessorsProvider] = {
    maxDesksByQueue24Hrs.getOrElse(queue, EmptyCapacityProvider).capacityForPeriod(minuteMillis)
  }
}

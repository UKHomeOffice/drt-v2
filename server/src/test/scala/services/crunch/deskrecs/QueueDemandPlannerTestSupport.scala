package services.crunch.deskrecs

import services.crunch.CalculationControl
import services.{ OptimiserConfig, OptimizerCrunchResult, TryCrunchWholePax, WorkloadProcessorsProvider }
import scala.util.Success

object QueueDemandPlannerTestSupport {
  val maximum: TryCrunchWholePax = (passengers, _, maxima, _) =>
    Success(OptimizerCrunchResult(maxima.toIndexedSeq, Seq.fill(passengers.size)(0), IndexedSeq.empty))

  /** Fixed proposals isolate resource-sharing and publication tests from the demand-search policy. */
  def proposals(cruncher: TryCrunchWholePax): QueueDemandPlanner = new QueueDemandPlanner {
    override def plan(request: QueueDemandRequest, control: CalculationControl): QueueDemandPlan = {
      control.check()
      val length = request.passengers.size
      val maxima = request.processors.maxProcessors(length).toIndexedSeq
      val minimums = request.minimums.zip(maxima).map { case (minimum, maximum) => Math.min(minimum, maximum) }
      val proposed =
        if (
          (!request.passengers.exists(_.exists(_ > 0)) && request.state.remainingPassengers.isEmpty) ||
          !maxima.exists(_ > 0)
        ) minimums
        else {
          val padding = (15 - length % 15) % 15
          val paddedProcessors = WorkloadProcessorsProvider(
            request.processors.processorsByMinute ++ Vector.fill(padding)(request.processors.processorsByMinute.last)
          )
          val result = cruncher(
            request.passengers ++ Vector.fill(padding)(List.empty[Double]),
            minimums ++ Vector.fill(padding)(minimums.last),
            maxima ++ Vector.fill(padding)(maxima.last),
            OptimiserConfig(request.sla, paddedProcessors)
          ).get.recommendedDesks
          require(result.size == length + padding && result.forall(_ >= 0), "Invalid test demand proposal")
          result.take(length).toIndexedSeq
        }
      control.check()
      val required = proposed.indices.map(i => Math.min(maxima(i), Math.max(minimums(i), proposed(i))))
      QueueDemandPlan(required, QueueDemandCost(0L, required.map(_.toLong).sum, 0L), 1, searchLimited = false)
    }
  }
}

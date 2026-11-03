package services.crunch.deskrecs

import drt.shared.AllocationPreview.{ MeasurementComparison, MeasurementSummary, Measurements }
import org.apache.pekko.stream.Materializer
import org.slf4j.LoggerFactory
import services.{ TryCrunchWholePax, WorkloadProcessorsProvider }
import services.crunch.{ CalculationAborted, CalculationControl }
import services.crunch.desklimits.TerminalDeskLimitsLike
import services.crunch.desklimits.flexed.BalancedDeskLimitsSnapshot
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal
import uk.gov.homeoffice.drt.time.LocalDate

import java.util.concurrent.ConcurrentHashMap
import scala.collection.immutable.NumericRange
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._
import scala.util.{ Failure, Try }
import scala.util.control.NonFatal

private[deskrecs] object AllocationMeasurements {
  private val log = LoggerFactory.getLogger(getClass)
  private val zero = Measurements(0L, 0L, 0L, 0L)

  private def sum(values: Iterable[Measurements]): Measurements =
    values.foldLeft(zero) { (a, b) =>
      Measurements(
        Math.addExact(a.slaBreachMinutes, b.slaBreachMinutes),
        Math.addExact(a.breachPassengerMinutes, b.breachPassengerMinutes),
        Math.addExact(a.queuePassengerMinutes, b.queuePassengerMinutes),
        Math.addExact(a.staffMinutes, b.staffMinutes)
      )
    }

  private def summary(
      expected: Set[Queue],
      values: Map[Queue, Measurements],
      feasible: Boolean
  ): MeasurementSummary = {
    val missing = expected -- values.keySet
    MeasurementSummary(
      values,
      Option.when(missing.isEmpty && values.keySet == expected)(sum(values.values)),
      missing.toSeq.sortBy(_.toString),
      feasible
    )
  }

  private[deskrecs] def fromEvaluation(
      expected: Set[Queue],
      evaluation: AllocationEvaluation,
      resourceFeasible: Boolean
  ): MeasurementSummary = {
    val values = evaluation.queues.flatMap { case (queue, evaluated) =>
      for {
        minutes <- evaluated.slaBreachMinutes
        severity <- evaluated.score
        burden <- evaluated.queuePassengerMinutes
        staff <- evaluated.deskMinutes
      } yield queue -> Measurements(minutes, severity, burden, staff)
    }
    summary(expected, values, resourceFeasible)
  }

  def compare(
      terminal: Terminal,
      inputs: CapturedDeskCalculationInputs,
      snapshot: BalancedDeskLimitsSnapshot,
      slas: Map[Queue, Int],
      neutral: BalancedTerminalResult,
      priority: List[Queue],
      cruncher: TryCrunchWholePax,
      control: CalculationControl
  )(implicit ec: ExecutionContext, mat: Materializer): Future[MeasurementComparison] = {
    val passengers = inputs.passengers
    // Both algorithms are scored by the same evaluator so the comparison cannot drift.
    val neutralEvaluation = QueueAllocationComparison.evaluateBalanced(snapshot, passengers, slas, neutral, control)
    val neutralSummary = fromEvaluation(passengers.keySet, neutralEvaluation, neutralEvaluation.valid)
    val frozen = inputs.frozenLimits(control)
    val processors = new ConcurrentHashMap[Queue, WorkloadProcessorsProvider]()
    // Legacy flexing can construct different processor profiles; score its actual profiles, not Neutral's bounds.
    val observed = new TerminalDeskLimitsLike {
      override def calculationControl: CalculationControl = control
      override val minDesksByQueue24Hrs: LocalDate => Map[Queue, IndexedSeq[Int]] = frozen.minDesksByQueue24Hrs
      override val paxForQueue: (NumericRange[Long], Queue) => Future[Seq[Int]] = frozen.paxForQueue
      override def maxDesksForMinutes(
          range: NumericRange[Long],
          queue: Queue,
          allocations: Map[Queue, List[Int]]
      ): Future[WorkloadProcessorsProvider] =
        frozen.maxDesksForMinutes(range, queue, allocations).map { profile =>
          control.check()
          processors.put(queue, profile)
          profile
        }
    }
    val safeCruncher: TryCrunchWholePax = (pax, minimum, maximum, config) =>
      Try(cruncher(pax, minimum, maximum, config)).flatten.recoverWith {
        case aborted: CalculationAborted => Failure(aborted)
        case NonFatal(_)                 => Failure(new IllegalStateException("Legacy measurement calculation failed"))
      }
    val provider = TerminalDesksAndWaitsProvider(
      terminal,
      (_, queue) => inputs.sla(queue, control),
      priority,
      safeCruncher,
      "same-input-measurements"
    )
    provider.desksAndWaits(inputs.minuteMillis, passengers, observed, control).map { legacy =>
      control.check()
      val actualModel = snapshot.copy(processorsByQueue = snapshot.processorsByQueue ++ processors.asScala.toMap)
      val evaluated = QueueAllocationComparison.evaluateLegacy(actualModel, passengers, slas, legacy, control)
      val resourceCheck = QueueAllocationComparison.evaluateLegacy(snapshot, passengers, slas, legacy, control)
      MeasurementComparison(
        fromEvaluation(passengers.keySet, evaluated, evaluated.valid && resourceCheck.valid),
        neutralSummary
      )
    }.recover {
      case aborted: CalculationAborted => throw aborted
      case NonFatal(error)             =>
        log.warn("Legacy comparison measurements unavailable ({})", error.getClass.getSimpleName)
        MeasurementComparison(summary(passengers.keySet, Map.empty, feasible = false), neutralSummary)
    }
  }
}

package services

import uk.gov.homeoffice.drt.egates.WorkloadProcessor


object WorkloadProcessorsProvider {
  def apply(processorsOverTime: Iterable[Seq[WorkloadProcessor]]): WorkloadProcessorsProvider =
    WorkloadProcessorsProvider(processorsOverTime.map(processor => WorkloadProcessors(processor)).toIndexedSeq)
}

case class WorkloadProcessorsProvider(processorsByMinute: IndexedSeq[WorkloadProcessors]) {
  def forMinute(minute: Int): WorkloadProcessors = processorsByMinute(minute)
}

case class WorkloadProcessors(processors: Iterable[WorkloadProcessor]) {
  val cumulativeCapacity: List[Int] = processors
    .foldLeft(List[Int](0)) {
      case (acc, processors) => acc.headOption.getOrElse(0) + processors.maxCapacity :: acc
    }
    .reverse

  private val capacityByWorkload: Map[Int, Int] = cumulativeCapacity
    .sliding(2).toList.zipWithIndex
    .flatMap {
      case (capacities, idx) => ((capacities.min + 1) to capacities.max).map(c => (c, idx + 1))
    }.toMap + (0 -> 0)

  private val maxCapacity: Int = capacityByWorkload.values.max

  def capacityForServers(servers: Int): Int =
    cumulativeCapacity.indices.zip(cumulativeCapacity).toMap.getOrElse(servers, 0)

  val forWorkload: PartialFunction[Double, Int] = {
    case noWorkload if noWorkload <= 0 => 0
    case someWorkload => capacityByWorkload.getOrElse(someWorkload.ceil.toInt, maxCapacity)
  }
}

package services

import uk.gov.homeoffice.drt.egates.EgateBank

trait WorkloadProcessorsProvider {
  def forMinute(minute: Int): WorkloadProcessors
}

case class EgateWorkloadProcessorsProvider(processors: IndexedSeq[WorkloadProcessors]) extends WorkloadProcessorsProvider {
  override def forMinute(minute: Int): WorkloadProcessors = processors(minute)
}

object EgateWorkloadProcessorsProvider {
  def apply(banksOverTime: Iterable[Seq[EgateBank]]): EgateWorkloadProcessorsProvider =
    EgateWorkloadProcessorsProvider(banksOverTime.map(banks => EGateWorkloadProcessors(banks.map(_.gates.count(_ == true)))).toIndexedSeq)
}

case object DeskWorkloadProcessorsProvider extends WorkloadProcessorsProvider {
  override def forMinute(minute: Int): WorkloadProcessors = DeskWorkloadProcessors
}

sealed trait WorkloadProcessors {
  def capacityForServers(servers: Int): Int

  val forWorkload: PartialFunction[Double, Int]
}

case object DeskWorkloadProcessors extends WorkloadProcessors {
  override def capacityForServers(servers: Int): Int = servers

  override val forWorkload: PartialFunction[Double, Int] = {
    case workload => capacityForServers(workload.ceil.toInt)
  }
}

case class EGateWorkloadProcessors(processors: Iterable[Int]) extends WorkloadProcessors {
  val processorsIncludingZero: Iterable[Int] = processors.headOption match {
    case None => Iterable(0)
    case Some(zero) if zero == 0 => processors
    case Some(_) => Iterable(0) ++ processors
  }

  val cumulativeCapacity: List[Int] = processorsIncludingZero
    .foldLeft(List[Int]()) {
      case (acc, processors) => acc.headOption.getOrElse(0) + processors :: acc
    }
    .reverse

  val capacityByWorkload: Map[Int, Int] = cumulativeCapacity
    .sliding(2).toList.zipWithIndex
    .flatMap {
      case (capacities, idx) => ((capacities.min + 1) to capacities.max).map(c => (c, idx + 1))
    }.toMap + (0 -> 0)

  val maxCapacity: Int = capacityByWorkload.values.max

  override def capacityForServers(servers: Int): Int = cumulativeCapacity.indices.zip(cumulativeCapacity).toMap.getOrElse(servers, 0)

  override val forWorkload: PartialFunction[Double, Int] = {
    case noWorkload if noWorkload <= 0 => 0
    case someWorkload => capacityByWorkload.getOrElse(someWorkload.ceil.toInt, maxCapacity)
  }
}

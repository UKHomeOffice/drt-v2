package services

trait ProcessorsLike {
  def capacityForUnits(units: Int): Int

  val forWorkload: PartialFunction[Double, Int]
}

case object DeskProcessors extends ProcessorsLike {
  override def capacityForUnits(units: Int): Int = units

  override val forWorkload: PartialFunction[Double, Int] = {
    case workload => capacityForUnits(workload.ceil.toInt)
  }
}

case class EGateProcessors(processors: Iterable[Int]) extends ProcessorsLike {
  val processorsWithZero: Iterable[Int] = processors.headOption match {
    case None => Iterable(0)
    case Some(zero) if zero == 0 => processors
    case Some(_) => Iterable(0) ++ processors
  }

  val cumulativeCapacity: List[Int] = processorsWithZero
    .foldLeft(List[Int]()) {
      case (acc, processors) => acc.headOption.getOrElse(0) + processors :: acc
    }
    .reverse

  val capacityByWorkload: Map[Int, Int] = cumulativeCapacity
    .sliding(2).toList.zipWithIndex
    .flatMap {
      case (capacities, idx) => ((capacities.min + 1) to capacities.max).map(c => (c, idx + 1))
    }.toMap + (0 -> 0)

  val maxProcessorUnits: Int = capacityByWorkload.values.max

  override def capacityForUnits(units: Int): Int = cumulativeCapacity.indices.zip(cumulativeCapacity).toMap.getOrElse(units, 0)

  override val forWorkload: PartialFunction[Double, Int] = {
    case noWorkload if noWorkload <= 0 => 0
    case someWorkload => capacityByWorkload.getOrElse(someWorkload.ceil.toInt, maxProcessorUnits)
  }
}

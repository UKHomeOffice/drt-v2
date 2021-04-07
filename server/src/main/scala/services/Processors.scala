package services

case class Processors(processors: Iterable[Int]) {
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

  val capacityForUnits: Map[Int, Int] = cumulativeCapacity.indices.zip(cumulativeCapacity).toMap

  val byWorkload: Map[Int, Int] = cumulativeCapacity
    .sliding(2).toList.zipWithIndex
    .flatMap {
      case (capacities, idx) => ((capacities.min + 1) to capacities.max).map(c => (c, idx + 1))
    }.toMap + (0 -> 0)

  val maxCapacity: Int = byWorkload.keys.max
  val maxProcessorUnits: Int = byWorkload.values.max

  val forWorkload: PartialFunction[Double, Int] = {
    case noWorkload if noWorkload <= 0 => 0
    case someWorkload => byWorkload.getOrElse(someWorkload.ceil.toInt, maxProcessorUnits)
  }
}

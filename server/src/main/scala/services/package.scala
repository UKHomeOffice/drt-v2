import scala.util.Try

package object services {
  type TryCrunch = (Seq[Double], Seq[Int], Seq[Int], OptimiserPlusConfig) => Try[OptimizerCrunchResult]
  type Simulator = (Seq[Double], Seq[Int], OptimiserPlusConfig) => Try[Seq[Int]]
}

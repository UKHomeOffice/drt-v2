import scala.util.Try

package object services {
  type TryCrunch = (Seq[Double], Seq[Int], Seq[Int], OptimiserConfig) => Try[OptimizerCrunchResult]
  type TrySimulator = (Seq[Double], Seq[Int], OptimiserConfig) => Try[Seq[Int]]
}

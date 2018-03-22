import scala.util.Try

package object services {

  type TryCrunch = (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult]
  type Simulate = (Seq[Double], Seq[Int], OptimizerConfig) => Seq[Int]
}

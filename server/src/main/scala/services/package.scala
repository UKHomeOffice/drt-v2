import scala.util.Try

package object services {

  type TryCrunch = (Seq[Double], Seq[Int], Seq[Int], OptimizerConfig) => Try[OptimizerCrunchResult]
  type Simulator = (Seq[Double], Seq[Int], OptimizerConfig) => Seq[Int]
}

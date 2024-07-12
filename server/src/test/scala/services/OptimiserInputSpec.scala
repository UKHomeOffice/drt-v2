package services

import org.specs2.mutable.Specification
import uk.gov.homeoffice.drt.egates.Desk

import scala.io.{BufferedSource, Source}


class OptimiserInputSpec extends Specification {
  val oneDesk: Seq[Int] = Seq.fill(30)(1)
  val oneBank: Seq[Int] = Seq.fill(30)(1)

  val filename = "some-desks-and-queues-file.csv"
  val fileSource: BufferedSource = Source.fromFile(filename)
  val workloadIndex = 12
  val desksIndex = 14
  val wlDesks: Iterator[(String, String)] = for (line <- fileSource.getLines) yield {
    val cells = line.split(",").toIndexedSeq
    val workload = cells(workloadIndex)
    val desks = cells(desksIndex)
    (workload, desks)
  }
  val wlDesksSeq = wlDesks.toSeq :+ ("0", "0")
  val workloads = wlDesksSeq.drop(2).map(_._1.toDouble)
  val deskCounts = wlDesksSeq.drop(2).map(_._2.toInt)

  fileSource.close()

  "Crunch with desk workload processors" >> {
    skipped("exploratory test")
    "Given 1 minutes incoming workload per minute, and desks fixed at 1 per minute" >> {
      "I should see all the workload completed each minute, leaving zero wait times" >> {
        val processors = WorkloadProcessorsProvider(deskCounts.map(d => Seq.fill(d)(Desk)))
        val result: Seq[Int] = OptimiserWithFlexibleProcessors.runSimulationOfWork(
          workloads = workloads,
          desks = deskCounts,
          config = OptimiserConfig(60, processors)).get

        println(s"waits: ${
          result.zipWithIndex.map { x =>
            val hour = x._2 / 60
            val minute = x._2 % 60
            f"$hour%02d:$minute%02d => ${x._1}"
          }.mkString("\n")
        }")

        success
      }
    }
  }
}

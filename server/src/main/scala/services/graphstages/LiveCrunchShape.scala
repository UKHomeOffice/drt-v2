package services.graphstages

import akka.NotUsed
import akka.stream.scaladsl.GraphDSL
import akka.stream.{FanInShape7, Graph}
import drt.shared._
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

object LiveCrunchShape {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SVM, SAD](crunchStage: CrunchGraphStage,
                      staffingStage: StaffingStage,
                      actualDesksStage: ActualDesksAndWaitTimesGraphStage
                     ): Graph[FanInShape7[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], String, String, Seq[StaffMovement], ActualDeskStats, CrunchApi.PortState], NotUsed] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    GraphDSL.create() {
      implicit builder =>
        val crunch = builder.add(crunchStage.async)
        val staffing = builder.add(staffingStage.async)
        val actualDesks = builder.add(actualDesksStage.async)

        crunch.out ~> staffing.in0
        staffing.out ~> actualDesks.in0
        actualDesks.out

        new FanInShape7(crunch.in0, crunch.in1, crunch.in2, staffing.in1, staffing.in2, staffing.in3, actualDesks.in1, actualDesks.out)
    }.named("live-crunch")
  }
}

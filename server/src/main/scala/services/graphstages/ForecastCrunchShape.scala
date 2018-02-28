package services.graphstages

import akka.NotUsed
import akka.stream.scaladsl.GraphDSL
import akka.stream.{FanInShape6, Graph}
import drt.shared.{ApiSplits, Arrival, CrunchApi, StaffMovement}
import org.slf4j.{Logger, LoggerFactory}
import passengersplits.parsing.VoyageManifestParser.VoyageManifests

object ForecastCrunchShape {
  val log: Logger = LoggerFactory.getLogger(getClass)

  def apply[SVM, SAD](crunchStage: CrunchGraphStage,
                      staffingStage: StaffingStage
                     ): Graph[FanInShape6[ArrivalsDiff, VoyageManifests, Seq[(Arrival, Option[ApiSplits])], String, String, Seq[StaffMovement], CrunchApi.PortState], NotUsed] = {

    import akka.stream.scaladsl.GraphDSL.Implicits._

    GraphDSL.create() {
      implicit builder =>
        val crunch = builder.add(crunchStage.async)
        val staffing = builder.add(staffingStage.async)

        crunch.out ~> staffing.in0
        staffing.out

        new FanInShape6(crunch.in0, crunch.in1, crunch.in2, staffing.in1, staffing.in2, staffing.in3, staffing.out)
    }.named("forecast-crunch")
  }
}

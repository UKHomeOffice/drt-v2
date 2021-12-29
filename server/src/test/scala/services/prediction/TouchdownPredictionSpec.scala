package services.prediction

import actors.persistent.prediction.TouchdownPredictionActor
import actors.persistent.staffing.GetState
import actors.serializers.FeatureType.OneToMany
import actors.serializers.{Features, ModelAndFeatures, RegressionModel}
import akka.actor.Props
import akka.pattern.ask
import controllers.ArrivalGenerator
import drt.shared.{MilliTimes, SDateLike}
import services.SDate
import services.crunch.CrunchTestLike
import uk.gov.homeoffice.drt.ports.PortCode
import uk.gov.homeoffice.drt.ports.Terminals.T2

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class MockTouchdownPredictionActor(now: () => SDateLike,
                                   terminal: String,
                                   number: Int,
                                   origin: String
                                  ) extends TouchdownPredictionActor(now, terminal, number, origin) {
  private val model: RegressionModel = RegressionModel(Iterable(-4.491677337488966, 0.5758560689088016, 3.8006500547982798, 0.11517121378172734, 0.0), 0)
  private val features: Features = Features(List(OneToMany(List("dayOfWeek"), "dow"), OneToMany(List("hoursMinutes"), "hhmm")), IndexedSeq("dow_7", "dow_4", "dow_6", "dow_2", "hhmm_1"))
  state = Option(ModelAndFeatures(model, features))
}

class TouchdownPredictionSpec extends CrunchTestLike {
  "Given an arrival and an actor containing a prediction model for that arrival" >> {
    "I should be able to update the arrival with an predicted touchdown time" >> {
      val arrival = ArrivalGenerator.arrival("BA0001", schDt = "2022-05-01T12:00", origin = PortCode("JFK"), terminal = T2)
      val actor = system.actorOf(Props(new MockTouchdownPredictionActor(() => SDate.now(), T2.toString, arrival.VoyageNumber.numeric, arrival.Origin.iata)))
      val eventualMaybeModelAndFeatures = actor.ask(GetState).mapTo[Option[ModelAndFeatures]]
      val maybeUpdatedArrival = eventualMaybeModelAndFeatures.map {
        case Some(modelAndFeatures) => modelAndFeatures
          val dow = s"dow_${SDate(arrival.Scheduled).getDayOfWeek()}"
          val hhmm = s"hhmm_${SDate(arrival.Scheduled).getHours() / 12}"
          val dowIdx = modelAndFeatures.features.oneToManyValues.indexOf(dow)
          val hhmmIdx = modelAndFeatures.features.oneToManyValues.indexOf(hhmm)
          println(s"dow: $dow, hhmm: $hhmm")
          val maybeUpdated = for {
            dowCo <- modelAndFeatures.model.coefficients.toIndexedSeq.lift(dowIdx)
            hhmmCo <- modelAndFeatures.model.coefficients.toIndexedSeq.lift(hhmmIdx)
          } yield {
            val offScheduled = (modelAndFeatures.model.intercept + dowCo + hhmmCo).toInt
            arrival.copy(Estimated = Option(arrival.Scheduled + (offScheduled * MilliTimes.oneMinuteMillis)))
          }
        maybeUpdated.getOrElse(arrival)
        case None => arrival
      }

      val result = Await.result(maybeUpdatedArrival, 1.second)

      println(s"scheduled: ${SDate(result.Scheduled).toISOString()}")
      result.Estimated.foreach(est => println(s"pred td: ${SDate(est).toISOString()}"))

      success
    }
  }
}

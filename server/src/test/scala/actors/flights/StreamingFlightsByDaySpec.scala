package actors.flights

import actors.ArrivalGenerator
import akka.NotUsed
import akka.stream.scaladsl.{Sink, Source}
import drt.shared.FlightsApi.FlightsWithSplits
import drt.shared.{ApiFlightWithSplits, MilliTimes, UniqueArrival, UtcDate}
import services.SDate
import services.crunch.CrunchTestLike

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class StreamingFlightsByDaySpec extends CrunchTestLike {
  def earlyOnTimeAndLate(utcDate: UtcDate): FlightsWithSplits = FlightsWithSplits(List(
    ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = midday(utcDate), pcpDt = dayBefore(utcDate), actPax = Option(100)), Set()),
    ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = midday(utcDate), pcpDt = midday(utcDate), actPax = Option(100)), Set()),
    ApiFlightWithSplits(ArrivalGenerator.arrival(schDt = midday(utcDate), pcpDt = dayAfter(utcDate), actPax = Option(100)), Set())))

  private def midday(utcDate: UtcDate): String = SDate(utcDate).addHours(12).toISOString()

  private def dayBefore(utcDate: UtcDate): String = SDate(utcDate).addHours(-1).toISOString()

  private def dayAfter(utcDate: UtcDate): String = SDate(utcDate).addHours(25).toISOString()

  "Given map of UtcDate to flights spanning several days, each containing flights that have pcp times the day before, after or on time" >> {
    def september(day: Int) = UtcDate(2020, 9, day)

    val earlyOnTimeAndLateFlights: Map[UtcDate, FlightsWithSplits] = (1 to 10)
      .map { day =>
        val date = september(day)
        date -> earlyOnTimeAndLate(date)
      }.toMap

    "When asking for flights for dates 3rd to 5th" >> {
      "I should see the late pcp from 2nd, all 3 flights from the 3rd, 4th, 5th, and the early flight from the 6th" >> {
        val flights: Source[Iterable[(UniqueArrival, ApiFlightWithSplits)], NotUsed] = flightsByDaySource(earlyOnTimeAndLateFlights, UtcDate(2020, 9, 3), UtcDate(2020, 9, 5))
      }
    }
  }

  private def flightsByDaySource(flights: Map[UtcDate, FlightsWithSplits], start: UtcDate, end: UtcDate): Source[Iterable[(UniqueArrival, ApiFlightWithSplits)], NotUsed] =
    utcDateRangeSource(start, end)
      .map { date =>
        flights.get(date) match {
          case Some(FlightsWithSplits(flights)) =>
            flights.filter { case (_, fws) =>
              val scheduledDate = SDate(fws.apiFlight.Scheduled).toUtcDate
              val pcpRangeStart = SDate(fws.apiFlight.pcpRange().min).toUtcDate
              val pcpRangeEnd = SDate(fws.apiFlight.pcpRange().max).toUtcDate
              val scheduledInRange = start <= scheduledDate && scheduledDate <= end
              val pcpStartInRange = start <= pcpRangeStart && pcpRangeStart <= end
              val pcpEndInRange = start <= pcpRangeEnd && pcpRangeEnd <= end
              val onlyPcpInRange = !scheduledInRange && (pcpStartInRange || pcpEndInRange)
              scheduledInRange || onlyPcpInRange
            }
          case None => Iterable()
        }
      }

  "When I ask for a Source of query dates" >> {
    "Given a start date of 2020-09-10 and end date of 2020-09-11" >> {
      "I should get 2 days before (2020-09-08) to 1 day after (2020-09-12)" >> {
        val result = Await.result(utcDateRangeSource(UtcDate(2020, 9, 10), UtcDate(2020, 9, 11)).runWith(Sink.seq), 1 second)
        val expected = Seq(
          UtcDate(2020, 9, 8),
          UtcDate(2020, 9, 9),
          UtcDate(2020, 9, 10),
          UtcDate(2020, 9, 11),
          UtcDate(2020, 9, 12)
        )

        result === expected
      }
    }
  }

  private def utcDateRangeSource(start: UtcDate, end: UtcDate): Source[UtcDate, NotUsed] = {
    val lookupStartMillis = SDate(start).addDays(-2).millisSinceEpoch
    val lookupEndMillis = SDate(end).addDays(1).millisSinceEpoch
    val daysRangeMillis = lookupStartMillis to lookupEndMillis by MilliTimes.oneDayMillis
    Source(daysRangeMillis).map(SDate(_).toUtcDate)
  }
}

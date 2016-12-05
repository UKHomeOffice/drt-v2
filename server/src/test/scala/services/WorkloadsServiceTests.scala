package services

import spatutorial.shared._
import spatutorial.shared.FlightsApi.TerminalName
import utest._

import scala.concurrent.Future

class WorkloadsServiceTests extends TestSuite {
  def apiFlight(iataFlightCode: String,
                totalPax: Int, scheduledDatetime: String,
                terminal: String
               ): ApiFlight =
    ApiFlight(
      Operator = "",
      Status = "",
      EstDT = "",
      ActDT = "",
      EstChoxDT = "",
      ActChoxDT = "",
      Gate = "",
      Stand = "",
      MaxPax = 1,
      ActPax = totalPax,
      TranPax = 0,
      RunwayID = "",
      BaggageReclaimId = "",
      FlightID = 1,
      AirportID = "EDI",
      Terminal = terminal,
      ICAO = "",
      IATA = iataFlightCode,
      Origin = "",
      PcpTime = 0,
      SchDT = scheduledDatetime
    )

  def tests = TestSuite {
    'WorkloadsCalculator - {
      val wc = new WorkloadsCalculator {
        override def splitRatioProvider = (apiFlight: ApiFlight) => {
          Some(List(SplitRatio(PaxTypeAndQueue(PaxTypes.eeaMachineReadable, Queues.eeaDesk), 1)))
        }
        override def procTimesProvider(terminalName: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = _ => 20d
      }

      val flights = Future.successful(List(apiFlight(iataFlightCode = "BA0001", totalPax = 10, scheduledDatetime = "2016-01-01T00:00:00", terminal = "A1")))

      val result = wc.getWorkloadsByTerminal(flights)

      assert(result == Nil)
    }
  }
}

package services.simulations

import controllers.ArrivalGenerator
import drt.shared._
import org.specs2.mutable.Specification
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.LocalDate

class SimulationParamsSpec extends Specification {
  val paxFeedSourceOrder: Seq[FeedSource] = TestDefaults.paxFeedSourceOrder

  val testConfig: AirportConfig = DrtPortConfigs.confByPort(PortCode("TEST"))

  private val terminal: Terminal = Terminal("T1")
  val simulation: SimulationParams = SimulationParams(
    terminal = terminal,
    date = LocalDate(2020, 3, 27),
    passengerWeighting = 1.0,
    processingTimes = testConfig.terminalProcessingTimes(terminal).view.mapValues(_ => 60).toMap,
    minDesksByQueue = testConfig.queuesByTerminal.head._2(terminal).map(q => q -> 0).toMap,
    maxDesks = 20,
    eGateBankSizes = IndexedSeq(5, 5, 5),
    slaByQueue = testConfig.slaByQueue,
    crunchOffsetMinutes = 0,
    eGateOpenHours = Seq(),
  )

  "Given I am applying a simulation to an airport config" >> {
    "The processing times should be updated to the simulation processing times" >> {

      val updatedConfig = simulation.applyToAirportConfig(testConfig)

      val expected = testConfig.terminalProcessingTimes(terminal).view.mapValues(_ => 1).toMap

      updatedConfig.terminalProcessingTimes(terminal) === expected
    }

    "The original airport config processing times should be used if replacements are not supplied" >> {

      val simulationWithMissingProcTimes = simulation.copy(processingTimes = Map(eeaMachineReadableToDesk -> 30,
        eeaMachineReadableToEGate -> 30))

      val testConfigWithProcTimes = testConfig.copy(terminalProcessingTimes = Map(terminal -> Map(
        eeaMachineReadableToDesk -> 1.0,
        eeaMachineReadableToEGate -> 1.0,
        eeaNonMachineReadableToDesk -> 1.0
      )))

      val expected = Map(
        eeaMachineReadableToDesk -> 0.5,
        eeaMachineReadableToEGate -> 0.5,
        eeaNonMachineReadableToDesk -> 1.0
      )

      val result: Map[PaxTypeAndQueue, Double] = simulationWithMissingProcTimes
        .applyToAirportConfig(testConfigWithProcTimes)
        .terminalProcessingTimes(terminal)

      result === expected
    }

    "Simulation supplied min desks should be applied to each queue" >> {

      val simulationWithMinDesks = simulation.copy(minDesksByQueue = Map(Queues.EGate -> 8))

      val expected = List.fill(24)(8)

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .minMaxDesksByTerminalQueue24Hrs(terminal)(Queues.EGate)._1

      result === expected
    }

    "Simulation supplied terminal desks should be applied to the total number of desks available" >> {

      val simulationWithMinDesks = simulation.copy(maxDesks = 25)

      val expected = Map(T1 -> 25)

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .desksByTerminal

      result === expected
    }

    "Simulation crunch offset should be used" >> {

      val simulationWithMinDesks = simulation.copy(crunchOffsetMinutes = 120)

      val expected = 120

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .crunchOffsetMinutes

      result === expected
    }
  }

  "Given I am applying a passenger weighting of 1 to some flights then the passenger numbers should be the same" >> {
    val weightingOfOne = simulation.copy(passengerWeighting = 1.0)

    val flightWithSplits = ApiFlightWithSplits(ArrivalGenerator.live(totalPax = Option(100),transPax = Option(50)).toArrival(LiveFeedSource), Set())
    val flights = FlightsWithSplits(Seq(flightWithSplits))

    val result = weightingOfOne.applyPassengerWeighting(flights, paxFeedSourceOrder)

    sumPcpPax(result) === sumPcpPax(flights)
  }

  "Given I am applying a passenger weighting to a flight, it should have the ScenarioSimulationSource added to it" >> {
    val weightingOfOne = simulation.copy(passengerWeighting = 1.0)

    val flights = FlightsWithSplits(
      List(ApiFlightWithSplits(ArrivalGenerator.live(totalPax = None).toArrival(LiveFeedSource), Set()))
    )

    val result = weightingOfOne.applyPassengerWeighting(flights, paxFeedSourceOrder)

    result.flights.values.head.apiFlight.FeedSources === Set(LiveFeedSource, ScenarioSimulationSource)
  }

  "Given I am applying a passenger weighting of 1.5 to some flights then pcp numbers increase by that factor" >> {
    val weightingOfTwo = simulation.copy(passengerWeighting = 1.5)

    val fws = FlightsWithSplits(List(
      ApiFlightWithSplits(ArrivalGenerator.live(totalPax = Option(100), transPax = Option(50)).toArrival(LiveFeedSource), Set())
    ))

    val result = weightingOfTwo.applyPassengerWeighting(fws, paxFeedSourceOrder)

    sumPcpPax(result) === (sumPcpPax(fws) * 1.5).toInt
  }


  private def sumPcpPax(result: FlightsWithSplits): Int =
    result.flights.values
      .map(_.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder).getOrElse(0))
      .sum
}

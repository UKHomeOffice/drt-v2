package services.simulations

import controllers.ArrivalGenerator
import drt.shared._
import org.specs2.mutable.Specification
import services.crunch.TestDefaults
import uk.gov.homeoffice.drt.arrivals.{ApiFlightWithSplits, FlightsWithSplits, Passengers}
import uk.gov.homeoffice.drt.ports.PaxTypesAndQueues._
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.ports._
import uk.gov.homeoffice.drt.time.LocalDate

class SimulationParamsSpec extends Specification {
  val paxFeedSourceOrder = TestDefaults.paxFeedSourceOrder

  val testConfig: AirportConfig = DrtPortConfigs.confByPort(PortCode("TEST"))

  private val terminal: Terminal = Terminal("T1")
  val simulation: SimulationParams = SimulationParams(
    terminal = terminal,
    date = LocalDate(2020, 3, 27),
    passengerWeighting = 1.0,
    processingTimes = testConfig.terminalProcessingTimes(terminal).view.mapValues(_ => 60).toMap,
    minDesks = testConfig.queuesByTerminal(terminal).map(q => q -> 0).toMap,
    maxDesks = testConfig.queuesByTerminal(terminal).map(q => q -> 10).toMap,
    eGateBanksSizes = IndexedSeq(5, 5, 5),
    slaByQueue = testConfig.slaByQueue,
    crunchOffsetMinutes = 0,
    eGateOpenHours = Seq()
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

      val simulationWithMinDesks = simulation.copy(minDesks = Map(Queues.EGate -> 8))

      val expected = List.fill(24)(8)

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .minMaxDesksByTerminalQueue24Hrs(terminal)(Queues.EGate)._1

      result === expected
    }

    "Simulation supplied max desks should be applied to each queue" >> {

      val simulationWithMinDesks = simulation.copy(maxDesks = Map(Queues.EGate -> 25))

      val expected = List.fill(24)(25)

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .minMaxDesksByTerminalQueue24Hrs(terminal)(Queues.EGate)._2

      result === expected
    }

    "Simulation eGate bank size should be used" >> {

      val simulationWithMinDesks = simulation.copy(eGateBanksSizes = IndexedSeq(7, 7, 7))

      val expected = Map(T1 -> IndexedSeq(7, 7, 7))

      val result = simulationWithMinDesks
        .applyToAirportConfig(testConfig)
        .eGateBankSizes

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

    val flightWithSplits = ApiFlightWithSplits(ArrivalGenerator.arrival(passengerSources = Map(ScenarioSimulationSource-> Passengers(Option(100),Option(50)))), Set())
    val flights = FlightsWithSplits(List(
      flightWithSplits
    ).map(a => a.apiFlight.unique -> a).toMap)

    val result = weightingOfOne.applyPassengerWeighting(flights)

    result.flights.values.head.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder) === flightWithSplits.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder)
  }

  "Given I am applying a passenger weighting to a flight, it should have the ScenarioSimulationSource added to it" >> {
    val weightingOfOne = simulation.copy(passengerWeighting = 1.0)

    val flights = FlightsWithSplits(
      List(ApiFlightWithSplits(ArrivalGenerator.arrival(passengerSources = Map(ScenarioSimulationSource-> Passengers(None,None))), Set())).map(a => a.apiFlight.unique -> a).toMap)

    val result = weightingOfOne.applyPassengerWeighting(flights)

    result.flights.values.head.apiFlight.FeedSources === Set(ScenarioSimulationSource)
  }

  "Given I am applying a passenger weighting of 2 to some flights then passenger numbers and trans numbers shoudl be doubled" >> {
    val weightingOfTwo = simulation.copy(passengerWeighting = 2.0)

    val fws = FlightsWithSplits(List(
      ApiFlightWithSplits(ArrivalGenerator.arrival(passengerSources = Map(LiveFeedSource->Passengers(Option(100),Option(50)))), Set())
    ).map(a => a.apiFlight.unique -> a).toMap)

    val flightWithSplits = ApiFlightWithSplits(ArrivalGenerator.arrival(passengerSources = Map(ScenarioSimulationSource->Passengers(Option(200),Option(100)))),Set())
    val result = weightingOfTwo.applyPassengerWeighting(fws)

    result.flights.values.head.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder) === flightWithSplits.apiFlight.bestPcpPaxEstimate(paxFeedSourceOrder)
  }

}

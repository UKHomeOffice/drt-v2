package services

import controllers.AirportConfProvider
import drt.shared.FlightsApi.TerminalName
import drt.shared.PaxTypeAndQueue

trait ProcessingTimes extends AirportConfProvider {

  def procTimesProvider(terminal: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
    getPortConfFromEnvVar.defaultProcessingTimes(terminal)(paxTypeAndQueue)
  }
}

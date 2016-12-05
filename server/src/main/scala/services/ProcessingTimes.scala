package services

import controllers.AirportConfProvider
import spatutorial.shared.FlightsApi.TerminalName
import spatutorial.shared.PaxTypeAndQueue

trait ProcessingTimes extends AirportConfProvider {

  def procTimesProvider(terminal: TerminalName)(paxTypeAndQueue: PaxTypeAndQueue): Double = {
    getPortConfFromEnvVar.defaultProcessingTimes(terminal)(paxTypeAndQueue)
  }

}

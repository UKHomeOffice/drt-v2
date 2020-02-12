//package services.crunch.deployments
//
//import drt.shared.CrunchApi
//import drt.shared.CrunchApi.MillisSinceEpoch
//import drt.shared.FlightsApi.FlightsWithSplits
//
//trait PortDeploymentProviderLike {
//  val minutesToCrunch: Int
//  val crunchOffsetMinutes: Int
//
//  def flightsToDeployments(flights: FlightsWithSplits, crunchStartMillis: MillisSinceEpoch): CrunchApi.DeskRecMinutes
//}
//

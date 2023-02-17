package drt.client.components

import drt.shared.CrunchApi.MillisSinceEpoch

case class ArrivalDisplayTime(label: String, shortLabel: String, time: Option[MillisSinceEpoch])

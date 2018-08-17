package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch

case class Alert(message: String, expires: MillisSinceEpoch, createdAt: MillisSinceEpoch)


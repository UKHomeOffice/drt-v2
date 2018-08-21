package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch

case class Alert(title: String, message: String, expires: MillisSinceEpoch, createdAt: MillisSinceEpoch)


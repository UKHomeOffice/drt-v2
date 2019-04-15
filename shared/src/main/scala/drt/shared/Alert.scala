package drt.shared

import drt.shared.CrunchApi.MillisSinceEpoch

case class Alert(title: String, message: String, alertClass: String, expires: MillisSinceEpoch, createdAt: MillisSinceEpoch)

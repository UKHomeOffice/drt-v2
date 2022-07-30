package drt.shared.redlist

import drt.shared.CrunchApi.MillisSinceEpoch
import uk.gov.homeoffice.drt.ports.Terminals.{T2, T3, T4, T5, Terminal}

case class LhrTerminalTypes(lhrRedListDates: LhrRedListDates) {

  def lhrRedListTerminalForDate(scheduled: MillisSinceEpoch): Option[Terminal] =
    if (scheduled < lhrRedListDates.t3RedListOpeningDate) None
    else if (scheduled < lhrRedListDates.t4RedListOpeningDate) Option(T3)
    else Option(T4)

  def lhrNonRedListTerminalsForDate(scheduled: MillisSinceEpoch): List[Terminal] =
    if (scheduled < lhrRedListDates.t3RedListOpeningDate) List()
    else if (scheduled < lhrRedListDates.t4RedListOpeningDate) List(T2, T5)
    else List(T2, T3, T5)
}

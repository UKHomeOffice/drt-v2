package drt.shared

import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinuteLike, SimulationMinuteLike}
import uk.gov.homeoffice.drt.ports.Queues.Queue
import uk.gov.homeoffice.drt.ports.Terminals.Terminal


case class SimulationMinute(terminal: Terminal,
                            queue: Queue,
                            minute: MillisSinceEpoch,
                            desks: Int,
                            waitTime: Int) extends SimulationMinuteLike with MinuteComparison[CrunchMinute] with MinuteLike[CrunchMinute, TQM] {
  lazy val key: TQM = MinuteHelper.key(terminal, queue, minute)

  override def maybeUpdated(existing: CrunchMinute, now: MillisSinceEpoch): Option[CrunchMinute] =
    if (existing.deployedDesks.isEmpty || existing.deployedDesks.get != desks || existing.deployedWait.isEmpty || existing.deployedWait.get != waitTime) Option(existing.copy(
      deployedDesks = Option(desks), deployedWait = Option(waitTime), lastUpdated = Option(now)
    ))
    else None

  override val lastUpdated: Option[MillisSinceEpoch] = None

  override def toUpdatedMinute(now: MillisSinceEpoch): CrunchMinute = toMinute.copy(lastUpdated = Option(now))

  override def toMinute: CrunchMinute = CrunchMinute(
    terminal = terminal,
    queue = queue,
    minute = minute,
    paxLoad = 0,
    workLoad = 0,
    deskRec = 0,
    waitTime = 0,
    maybePaxInQueue = None,
    deployedDesks = Option(desks),
    deployedWait = Option(waitTime),
    maybeDeployedPaxInQueue = None,
    lastUpdated = None)

}

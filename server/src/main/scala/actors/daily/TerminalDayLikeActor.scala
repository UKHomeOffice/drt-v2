package actors.daily

import actors.{RecoveryActorLike, Sizes}
import drt.shared.CrunchApi.MillisSinceEpoch
import drt.shared.SDateLike
import drt.shared.Terminals.Terminal
import org.slf4j.{Logger, LoggerFactory}
import services.SDate
import services.graphstages.Crunch

case object GetSummariesWithActualApi

abstract class TerminalDayLikeActor(year: Int,
                                    month: Int,
                                    day: Int,
                                    terminal: Terminal,
                                    now: () => SDateLike) extends RecoveryActorLike {
  override val log: Logger = LoggerFactory.getLogger(getClass)

  val typeForPersistenceId: String

  override def persistenceId: String = f"terminal-$typeForPersistenceId-${terminal.toString.toLowerCase}-$year-$month%02d-$day%02d"

  override val snapshotBytesThreshold: Int = Sizes.oneMegaByte

  val firstMinute: SDateLike = SDate(year, month, day, 0, 0, Crunch.europeLondonTimeZone)
  val firstMinuteMillis: MillisSinceEpoch = firstMinute.millisSinceEpoch
  val lastMinuteMillis: MillisSinceEpoch = firstMinute.addDays(1).addMinutes(-1).millisSinceEpoch
}

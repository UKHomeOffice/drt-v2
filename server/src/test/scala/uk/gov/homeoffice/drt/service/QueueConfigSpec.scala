package uk.gov.homeoffice.drt.service

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import uk.gov.homeoffice.drt.ports.Queues
import uk.gov.homeoffice.drt.ports.Queues.{EGate, EeaDesk, NonEeaDesk, QueueDesk}
import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}
import uk.gov.homeoffice.drt.time.LocalDate

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future}

class QueueConfigSpec extends AnyWordSpec with Matchers {
  private val config: () => Future[Map[LocalDate, Map[Terminal, Seq[Queues.Queue]]]] = () => Future.successful(Map(
    LocalDate(2014, 1, 1) -> Map(T1 -> Seq(EeaDesk, EGate, NonEeaDesk)),
    LocalDate(2025, 6, 2) -> Map(T1 -> Seq(QueueDesk)),
  ))

  private val configProvider = QueueConfig.queuesForDateAndTerminal(config)

  "queuesForDateAndTerminal" should {
    val terminal = T1

    "return the queues configured in the most recent past config prior to 2023-10-01: 2014-01-01" in {
      Await.result(configProvider(LocalDate(2023, 10, 1), terminal), 1.second) shouldEqual Seq(EeaDesk, EGate, NonEeaDesk)
    }
    "return the queues configured in the most recent past prior to 2025-06-10: 2025-06-02" in {
      Await.result(configProvider(LocalDate(2025, 6, 10), terminal), 1.second) shouldEqual Seq(QueueDesk)
    }
  }

  "queuesForDateRangeAndTerminal" should {
    val terminal = T1
    "return the all queues configures for the given terminal when the date range spans only one config (1)" in {
      val queuesProvider = QueueConfig.queuesForDateRangeAndTerminal(config)

      val queues = Await.result(queuesProvider(LocalDate(2013, 1, 1), LocalDate(2025, 6, 1), terminal), 1.second)
      queues shouldEqual Set(EeaDesk, EGate, NonEeaDesk)
    }
    "return the all queues configures for the given terminal when the date range spans only one config (2)" in {
      val queuesProvider = QueueConfig.queuesForDateRangeAndTerminal(config)

      val queues = Await.result(queuesProvider(LocalDate(2015, 1, 1), LocalDate(2025, 6, 10), terminal), 1.second)
      queues shouldEqual Set(QueueDesk)
    }
    "return the all queues configures for the given terminal when the date range spans multiple configs" in {
      val queuesProvider = QueueConfig.queuesForDateRangeAndTerminal(config)

      val queues = Await.result(queuesProvider(LocalDate(2014, 1, 1), LocalDate(2025, 6, 10), terminal), 1.second)
      queues shouldEqual Set(EeaDesk, EGate, NonEeaDesk, QueueDesk)
    }
  }
}

package queueus

import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}

case class PaxTypeQueueAllocation(paxTypeAllocator: PaxTypeAllocator, queueAllocator: QueueAllocator) {
  def toQueues(terminal: Terminal, bestManifest: BestAvailableManifest): Map[Queue, List[(Queue, PaxType, ManifestPassengerProfile, Double)]] = {
    val queueAllocatorForFlight = queueAllocator(terminal, bestManifest) _
    val paxTypeAllocatorForFlight = paxTypeAllocator(bestManifest) _
    bestManifest.passengerList.flatMap(mpp => {
      val paxType = paxTypeAllocatorForFlight(mpp)
      val queueAllocations = queueAllocatorForFlight(paxType)
      queueAllocations.map {
        case (queue, allocation) => (queue, paxType, mpp, allocation)
      }
    })
    }
    .groupBy {
      case (queueType, _, _, _) => queueType
    }

  def toSplits(terminal: Terminal, bestManifest: BestAvailableManifest): Splits = {
    val splits = toQueues(terminal, bestManifest).flatMap {
      case (_, passengerProfileTypeByQueueCount) =>
        passengerProfileTypeByQueueCount.foldLeft(Map[PaxTypeAndQueue, ApiPaxTypeAndQueueCount]()) {
          case (soFar, (queue, paxType, mpp, paxCount)) =>
            val paxTypeAndQueue = PaxTypeAndQueue(paxType, queue)
            val ptqc: ApiPaxTypeAndQueueCount = soFar.get(paxTypeAndQueue) match {
              case Some(apiPaxTypeAndQueueCount) => apiPaxTypeAndQueueCount.copy(
                paxCount = apiPaxTypeAndQueueCount.paxCount + paxCount,
                nationalities = incrementNationalityCount(mpp, paxCount, apiPaxTypeAndQueueCount)
              )
              case None => ApiPaxTypeAndQueueCount(paxType, queue, paxCount, Some(Map(mpp.nationality -> paxCount)))
            }
            soFar + (paxTypeAndQueue -> ptqc)
        }
    }.values.toSet

    Splits(splits, bestManifest.source, None, PaxNumbers)
  }

  def incrementNationalityCount(mpp: ManifestPassengerProfile, paxCount: Double, apiPaxTypeAndQueueCount: ApiPaxTypeAndQueueCount): Some[Map[String, Double]] =
    apiPaxTypeAndQueueCount.nationalities.map(nats => {
      val existingOfNationality: Double = nats.getOrElse(mpp.nationality, 0)
      val newNats: Map[String, Double] = nats + (mpp.nationality -> (existingOfNationality + paxCount))
      Some(newNats)
    }).getOrElse(Some(Map(mpp.nationality -> paxCount)))
}

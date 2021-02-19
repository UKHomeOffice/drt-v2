package queueus

import drt.shared.Queues.Queue
import drt.shared.Terminals.Terminal
import drt.shared._
import manifests.passengers.{ManifestLike, ManifestPassengerProfile}

case class PaxTypeQueueAllocation(paxTypeAllocator: PaxTypeAllocator, queueAllocator: QueueAllocator) {
  def toQueues(terminal: Terminal, manifest: ManifestLike): Map[Queue, Iterable[(Queue, PaxType, ManifestPassengerProfile, Double)]] = {
    val queueAllocatorForFlight = queueAllocator(terminal, manifest) _
    val paxTypeAllocatorForFlight = paxTypeAllocator
    manifest.uniquePassengers.flatMap(mpp => {
      val paxType = paxTypeAllocatorForFlight(mpp)
      val queueAllocations = queueAllocatorForFlight(paxType)
      queueAllocations.map {
        case (queue, allocation) => (queue, paxType, mpp, allocation)
      }
    })
  }.groupBy {
    case (queueType, _, _, _) => queueType
  }

  def toSplits(terminal: Terminal, manifest: ManifestLike): Splits = {
    val splits = toQueues(terminal, manifest).flatMap {
      case (_, passengerProfileTypeByQueueCount) =>
        passengerProfileTypeByQueueCount.foldLeft(Map[PaxTypeAndQueue, ApiPaxTypeAndQueueCount]()) {
          case (soFar, (queue, paxType, mpp, paxCount)) =>
            val paxTypeAndQueue = PaxTypeAndQueue(paxType, queue)
            val ptqc: ApiPaxTypeAndQueueCount = soFar.get(paxTypeAndQueue) match {
              case Some(apiPaxTypeAndQueueCount) => apiPaxTypeAndQueueCount.copy(
                paxCount = apiPaxTypeAndQueueCount.paxCount + paxCount,
                nationalities = incrementNationalityCount(mpp, paxCount, apiPaxTypeAndQueueCount),
                ages = incrementAgeCount(mpp, paxCount, apiPaxTypeAndQueueCount)
              )
              case None => ApiPaxTypeAndQueueCount(
                paxType,
                queue,
                paxCount,
                Option(Map(mpp.nationality -> paxCount)),
                mpp.age.map(age => Map(age -> paxCount))
              )
            }
            soFar + (paxTypeAndQueue -> ptqc)
        }
    }.values.toSet

    Splits(splits, manifest.source, None, PaxNumbers)
  }

  def incrementNationalityCount(mpp: ManifestPassengerProfile, paxCount: Double, apiPaxTypeAndQueueCount: ApiPaxTypeAndQueueCount): Option[Map[Nationality, Double]] =
    apiPaxTypeAndQueueCount.nationalities.map(nats => {
      val existingOfNationality: Double = nats.getOrElse(mpp.nationality, 0)
      val newNats: Map[Nationality, Double] = nats + (mpp.nationality -> (existingOfNationality + paxCount))
      Option(newNats)
    }).getOrElse(Option(Map(mpp.nationality -> paxCount)))

  def incrementAgeCount(mpp: ManifestPassengerProfile, paxCount: Double, apiPaxTypeAndQueueCount: ApiPaxTypeAndQueueCount): Option[Map[PaxAge, Double]] =
    mpp.age.map(age => {
      apiPaxTypeAndQueueCount.ages.map((paxAges: Map[PaxAge, Double]) => {
        val existingOfAge: Double = paxAges.getOrElse(age, 0)
        val newPaxAges: Map[PaxAge, Double] = paxAges + (age -> (existingOfAge + paxCount))
        newPaxAges
      }).getOrElse(Map(age -> paxCount))
    })
}

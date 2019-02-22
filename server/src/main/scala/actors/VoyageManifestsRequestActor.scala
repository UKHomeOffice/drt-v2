package actors

import java.sql.Timestamp

import akka.actor.Actor
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.SourceQueueWithComplete
import drt.shared.SplitRatiosNs.SplitSources
import drt.shared.{ArrivalsDiff, SDateLike}
import manifests.passengers.{BestAvailableManifest, ManifestPassengerProfile}
import org.slf4j.{Logger, LoggerFactory}
import server.feeds.BestManifestsFeedSuccess
import services.SDate
import slickdb.VoyageManifestPassengerInfoTable

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Success, Try}

class VoyageManifestsRequestActor(portCode: String, paxInfoTable: VoyageManifestPassengerInfoTable) extends Actor {
  var manifestsResponseQueue: Option[SourceQueueWithComplete[Any]] = None
  val log: Logger = LoggerFactory.getLogger(getClass)

  override def receive: Receive = {
    case Subscribe(subscriber) =>
      log.info(s"received subscriber")
      manifestsResponseQueue = Option(subscriber)
    case ArrivalsDiff(arrivals, _) =>
      log.info(s"received manifest requests for ${arrivals.size} arrivals:\n${arrivals.map(a => s"${a.voyageNumberPadded}-${a.Origin}-$portCode").mkString("\n")}")

      manifestsResponseQueue.foreach(queue => {
        log.info(s"We have a queue so I'll try looking some things up")
        import paxInfoTable.tables.profile.api._

        def mostRecentScheduled(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: SDateLike): Future[Option[java.sql.Timestamp]] = {
          paxInfoTable.db.run(sql"""select scheduled_date
                                    from voyage_manifest_passenger_info
                                    where event_code ='DC'
                                      and arrival_port_code=$arrivalPort
                                      and departure_port_code=$departurePort
                                      and voyager_number=$voyageNumber
                                      and extract(dow from scheduled_date) = extract(dow from to_date(${scheduled.toISODateOnly}, 'YYYY-MM-DD'))
                                    order by scheduled_date DESC
                                    LIMIT 1""".as[java.sql.Timestamp]).map(_.headOption)
        }

        def mostRecentManifest(arrivalPort: String, departurePort: String, voyageNumber: String, scheduled: java.sql.Timestamp): Future[Vector[(String, String, String, String, String, Boolean)]] = {
          paxInfoTable.db.run(sql"""select
                                      nationality_country_code,
                                      document_type,
                                      age,
                                      in_transit_flag,
                                      disembarkation_port_country_code,
                                      in_transit
                                    from voyage_manifest_passenger_info
                                    where event_code ='DC'
                                      and arrival_port_code=$arrivalPort
                                      and departure_port_code=$departurePort
                                      and voyager_number=$voyageNumber
                                      and scheduled_date=$scheduled""".as[(String, String, String, String, String, Boolean)])
        }

        val manifests = arrivals
          .map(a => {
            val mostRecentFuture: Future[Option[Timestamp]] = mostRecentScheduled(portCode, a.Origin, a.voyageNumberPadded, SDate(a.Scheduled))
            Await.result(mostRecentFuture, 10 seconds) match {
              case None =>
                log.info(s"No recent arrival on the same day of the week for ${a.IATA} @ ${SDate(a.Scheduled).toISOString()}")
                None
              case Some(mostRecent) =>
                log.info(s"Most recent arrival on the same day of the week for ${a.IATA} @ ${SDate(a.Scheduled).toISOString()} is ${SDate(mostRecent.getTime).toISOString()}")
                val paxProfilesFuture: Future[Vector[(String, String, String, String, String, Boolean)]] = mostRecentManifest(portCode, a.Origin, a.voyageNumberPadded, mostRecent)
                val paxProfiles = Await.result(paxProfilesFuture, 10 seconds)
                val pax = paxProfiles.map {
                  case (nat, doc, age, transitFlag, endCountry, inTransit) =>
                    val transit = (transitFlag, endCountry, inTransit) match {
                      case (t, _, _) if t == "Y" => true
                      case (_, c, _) if c != "GBR" => true
                      case (_, _, t) if t => true
                      case _ => false
                    }
                    ManifestPassengerProfile(nat, Option(doc), Try(age.toInt).toOption, Option(transit))
                }
                Option(BestAvailableManifest(SplitSources.Historical, portCode, a.Origin, a.voyageNumberPadded, "xx", SDate(a.Scheduled), pax.toList))
            }
          })
          .collect {
            case Some(bm) => bm
          }
        queue.offer(BestManifestsFeedSuccess(manifests.toSeq, SDate.now())) map {
          case Enqueued => log.info(s"Enqueued ${manifests.size} estimated manifests")
          case failure => log.info(s"Failed to enqueue ${manifests.size} estimated manifests: $failure")
        }
      })

    case unexpected =>
      log.warn(s"received unexpected ${unexpected.getClass}")
  }
}

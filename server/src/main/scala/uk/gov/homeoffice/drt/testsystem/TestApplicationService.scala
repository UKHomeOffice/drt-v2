package uk.gov.homeoffice.drt.testsystem

import actors.{DrtParameters, ManifestLookupsLike, MinuteLookupsLike, StreamingJournalLike, TestDrtSystemActors, TestDrtSystemActorsLike}
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.util.Timeout
import manifests.ManifestLookupLike
import play.api.Configuration
import slickdb.Tables
import uk.gov.homeoffice.drt.crunchsystem.{PersistentStateActors, ReadRouteUpdateActorsLike}
import uk.gov.homeoffice.drt.ports.AirportConfig
import uk.gov.homeoffice.drt.service.{ApplicationService, FeedService}
import uk.gov.homeoffice.drt.time.SDateLike

import scala.concurrent.ExecutionContext

class TestApplicationService(journalType: StreamingJournalLike,
                             airportConfig: AirportConfig,
                             now: () => SDateLike,
                             params: DrtParameters,
                             config: Configuration,
                             db: Tables,
                             feedService: FeedService,
                             manifestLookups: ManifestLookupsLike,
                             manifestLookupService: ManifestLookupLike,
                             minuteLookups: MinuteLookupsLike,
                             readActorService: ReadRouteUpdateActorsLike,
                             persistentStateActors: PersistentStateActors,
                             testDrtSystemActor: TestDrtSystemActorsLike)(implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer, timeout: Timeout)
  extends ApplicationService(journalType,
    airportConfig,
    now,
    params,
    config,
    db,
    feedService,
    manifestLookups,
    manifestLookupService,
    minuteLookups,
    readActorService,
    persistentStateActors) {

//  override def run(): Unit = {
//    testDrtSystemActor.restartActor ! StartTestSystem
//  }

}

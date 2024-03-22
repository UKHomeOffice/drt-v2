[1mdiff --git a/project/Settings.scala b/project/Settings.scala[m
[1mindex 2141038bb..48094b66b 100644[m
[1m--- a/project/Settings.scala[m
[1m+++ b/project/Settings.scala[m
[36m@@ -25,7 +25,7 @@[m [mobject Settings {[m
   /** Declare global dependency versions here to avoid mismatches in multi part dependencies */[m
   //noinspection ScalaStyle[m
   object versions {[m
[31m-    val drtLib = "v791"[m
[32m+[m[32m    val drtLib = "v20240322_1"[m
 [m
     val scala = "2.13.12"[m
     val scalaDom = "2.8.0"[m
[1mdiff --git a/server/src/main/resources/config/akka.conf b/server/src/main/resources/config/akka.conf[m
[1mindex 57d4acc9b..8af0e19c7 100644[m
[1m--- a/server/src/main/resources/config/akka.conf[m
[1m+++ b/server/src/main/resources/config/akka.conf[m
[36m@@ -88,6 +88,10 @@[m [makka {[m
       "uk.gov.homeoffice.drt.protobuf.messages.SlasUpdates.SetSlaConfigMessage" = protobuf[m
       "uk.gov.homeoffice.drt.protobuf.messages.SlasUpdates.SlaConfigsMessage" = protobuf[m
       "uk.gov.homeoffice.drt.protobuf.messages.config.Configs.RemoveConfigMessage" = protobuf[m
[32m+[m[32m      "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.LiveFeedArrivalsDiffMessage" = protobuf[m
[32m+[m[32m      "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.ForecastFeedArrivalsDiffMessage" = protobuf[m
[32m+[m[32m      "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.LiveArrivalStateSnapshotMessage" = protobuf[m
[32m+[m[32m      "uk.gov.homeoffice.drt.protobuf.messages.FeedArrivalsMessage.ForecastArrivalStateSnapshotMessage" = protobuf[m
     }[m
   }[m
 }[m
[1mdiff --git a/server/src/main/scala/actors/CrunchManagerActor.scala b/server/src/main/scala/actors/CrunchManagerActor.scala[m
[1mindex cda90989f..0e6c43f38 100644[m
[1m--- a/server/src/main/scala/actors/CrunchManagerActor.scala[m
[1m+++ b/server/src/main/scala/actors/CrunchManagerActor.scala[m
[36m@@ -1,7 +1,6 @@[m
 package actors[m
 [m
 import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber, RecalculateArrivals, Recrunch}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.{Actor, ActorRef}[m
 [m
 object CrunchManagerActor {[m
[36m@@ -10,12 +9,12 @@[m [mobject CrunchManagerActor {[m
   case class AddRecalculateArrivalsSubscriber(subscriber: ActorRef)[m
 [m
   trait ReProcessDates {[m
[31m-    val updatedMillis: UpdatedMillis[m
[32m+[m[32m    val updatedMillis: Set[Long][m
   }[m
 [m
[31m-  case class RecalculateArrivals(updatedMillis: UpdatedMillis) extends ReProcessDates[m
[32m+[m[32m  case class RecalculateArrivals(updatedMillis: Set[Long]) extends ReProcessDates[m
 [m
[31m-  case class Recrunch(updatedMillis: UpdatedMillis) extends ReProcessDates[m
[32m+[m[32m  case class Recrunch(updatedMillis: Set[Long]) extends ReProcessDates[m
 }[m
 [m
 class CrunchManagerActor extends Actor {[m
[1mdiff --git a/server/src/main/scala/actors/FlightLookups.scala b/server/src/main/scala/actors/FlightLookups.scala[m
[1mindex 989eef50d..fe658d8c6 100644[m
[1m--- a/server/src/main/scala/actors/FlightLookups.scala[m
[1m+++ b/server/src/main/scala/actors/FlightLookups.scala[m
[36m@@ -1,7 +1,6 @@[m
 package actors[m
 [m
 import actors.daily.{RequestAndTerminate, RequestAndTerminateActor, TerminalDayFlightActor}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.routing.FlightsRouterActor[m
 import actors.routing.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
[1mdiff --git a/server/src/main/scala/actors/ManifestLookups.scala b/server/src/main/scala/actors/ManifestLookups.scala[m
[1mindex 57ab8c725..87ea0b29b 100644[m
[1m--- a/server/src/main/scala/actors/ManifestLookups.scala[m
[1m+++ b/server/src/main/scala/actors/ManifestLookups.scala[m
[36m@@ -1,7 +1,6 @@[m
 package actors[m
 [m
 import actors.daily.{DayManifestActor, RequestAndTerminate, RequestAndTerminateActor}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
 import akka.pattern.ask[m
[36m@@ -25,7 +24,7 @@[m [mtrait ManifestLookupsLike {[m
   val updateManifests: ManifestsUpdate = (date: UtcDate, vms: VoyageManifests) => {[m
     val actor = system.actorOf(DayManifestActor.props(date))[m
     system.log.info(s"About to update $date with ${vms.manifests.size} manifests")[m
[31m-    requestAndTerminateActor.ask(RequestAndTerminate(actor, vms)).mapTo[UpdatedMillis][m
[32m+[m[32m    requestAndTerminateActor.ask(RequestAndTerminate(actor, vms)).mapTo[Set[Long]][m
   }[m
 [m
   val manifestsByDayLookup: ManifestLookup = (date: UtcDate, maybePit: Option[MillisSinceEpoch]) => {[m
[1mdiff --git a/server/src/main/scala/actors/MinuteLookups.scala b/server/src/main/scala/actors/MinuteLookups.scala[m
[1mindex 41773d258..1344e2de4 100644[m
[1m--- a/server/src/main/scala/actors/MinuteLookups.scala[m
[1m+++ b/server/src/main/scala/actors/MinuteLookups.scala[m
[36m@@ -1,7 +1,6 @@[m
 package actors[m
 [m
 import actors.daily._[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.routing.minutes.MinutesActorLike.MinutesLookup[m
 import actors.routing.minutes.{QueueLoadsMinutesActor, QueueMinutesRouterActor, StaffMinutesRouterActor}[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
[1mdiff --git a/server/src/main/scala/actors/daily/DayManifestActor.scala b/server/src/main/scala/actors/daily/DayManifestActor.scala[m
[1mindex 6460cf86e..8eaaf6873 100644[m
[1m--- a/server/src/main/scala/actors/daily/DayManifestActor.scala[m
[1m+++ b/server/src/main/scala/actors/daily/DayManifestActor.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.daily[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.serializers.ManifestMessageConversion[m
 import akka.actor.Props[m
 import akka.persistence.SaveSnapshotSuccess[m
[36m@@ -80,7 +79,7 @@[m [mclass DayManifestActor(year: Int, month: Int, day: Int, override val maybePointI[m
   def updateAndPersist(vms: VoyageManifests): Unit = {[m
     state = state ++ vms.toMap[m
 [m
[31m-    val replyToAndMessage = List((sender(), UpdatedMillis(vms.manifests.map(_.scheduled.millisSinceEpoch).toSet)))[m
[32m+[m[32m    val replyToAndMessage = List((sender(), vms.manifests.map(_.scheduled.millisSinceEpoch).toSet))[m
     persistAndMaybeSnapshotWithAck(ManifestMessageConversion.voyageManifestsToMessage(vms), replyToAndMessage)[m
   }[m
 [m
[1mdiff --git a/server/src/main/scala/actors/daily/FlightUpdatesSupervisor.scala b/server/src/main/scala/actors/daily/FlightUpdatesSupervisor.scala[m
[1mindex c4a9d40ee..3025e5dc4 100644[m
[1m--- a/server/src/main/scala/actors/daily/FlightUpdatesSupervisor.scala[m
[1m+++ b/server/src/main/scala/actors/daily/FlightUpdatesSupervisor.scala[m
[36m@@ -1,6 +1,6 @@[m
 package actors.daily[m
 [m
[31m-import actors.PartitionedPortStateActor.{GetFlightUpdatesSince, GetUpdatesSince}[m
[32m+[m[32mimport actors.PartitionedPortStateActor.GetFlightUpdatesSince[m
 import actors.daily.StreamingUpdatesLike.StopUpdates[m
 import akka.NotUsed[m
 import akka.actor.{Actor, ActorRef, Cancellable, Props}[m
[1mdiff --git a/server/src/main/scala/actors/daily/TerminalDayLikeActor.scala b/server/src/main/scala/actors/daily/TerminalDayLikeActor.scala[m
[1mindex 21e7911c2..86bd5fb73 100644[m
[1m--- a/server/src/main/scala/actors/daily/TerminalDayLikeActor.scala[m
[1m+++ b/server/src/main/scala/actors/daily/TerminalDayLikeActor.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.daily[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.persistence.SaveSnapshotSuccess[m
 import drt.shared.CrunchApi.{MillisSinceEpoch, MinuteLike, MinutesContainer}[m
 import org.slf4j.{Logger, LoggerFactory}[m
[36m@@ -84,13 +83,13 @@[m [mabstract class TerminalDayLikeActor[VAL <: MinuteLike[VAL, INDEX], INDEX <: With[m
 [m
   private def updateAndPersistDiff(container: MinutesContainer[VAL, INDEX]): Unit =[m
     diffFromMinutes(state, container.minutes) match {[m
[31m-      case noDifferences if noDifferences.isEmpty => sender() ! UpdatedMillis.empty[m
[32m+[m[32m      case noDifferences if noDifferences.isEmpty => sender() ! Set.empty[Long][m
       case differences =>[m
         updateStateFromDiff(differences)[m
         val messageToPersist = containerToMessage(differences)[m
         val updatedMillis = if (shouldSendEffectsToSubscriber(container))[m
[31m-          UpdatedMillis(differences.map(_.minute).toSet)[m
[31m-        else UpdatedMillis.empty[m
[32m+[m[32m          differences.map(_.minute).toSet[m
[32m+[m[32m        else Set.empty[Long][m
         val replyToAndMessage = List((sender(), updatedMillis))[m
         persistAndMaybeSnapshotWithAck(messageToPersist, replyToAndMessage)[m
     }[m
[1mdiff --git a/server/src/main/scala/actors/persistent/ManifestRouterActor.scala b/server/src/main/scala/actors/persistent/ManifestRouterActor.scala[m
[1mindex d60a43963..3acd94d47 100644[m
[1m--- a/server/src/main/scala/actors/persistent/ManifestRouterActor.scala[m
[1m+++ b/server/src/main/scala/actors/persistent/ManifestRouterActor.scala[m
[36m@@ -3,7 +3,6 @@[m [mpackage actors.persistent[m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor._[m
 import actors.persistent.ManifestRouterActor.{GetForArrival, ManifestFound, ManifestNotFound}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.persistent.staffing.GetFeedStatuses[m
 import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate, ProcessNextUpdateRequest}[m
 import akka.NotUsed[m
[36m@@ -193,7 +192,7 @@[m [mclass ManifestRouterActor(manifestLookup: ManifestLookup,[m
     case unexpected => log.error(s"Got an unexpected message: $unexpected")[m
   }[m
 [m
[31m-  def handleUpdatesAndAck(updates: VoyageManifests, replyTo: ActorRef): Future[UpdatedMillis] = {[m
[32m+[m[32m  def handleUpdatesAndAck(updates: VoyageManifests, replyTo: ActorRef): Future[Set[Long]] = {[m
     processingRequest = true[m
     val eventualEffects = sendUpdates(updates)[m
     eventualEffects[m
[36m@@ -206,23 +205,23 @@[m [mclass ManifestRouterActor(manifestLookup: ManifestLookup,[m
     eventualEffects[m
   }[m
 [m
[31m-  private def sendUpdates(updates: VoyageManifests): Future[UpdatedMillis] = {[m
[31m-    val eventualUpdatedMinutesDiff: Source[UpdatedMillis, NotUsed] =[m
[32m+[m[32m  private def sendUpdates(updates: VoyageManifests): Future[Set[Long]] = {[m
[32m+[m[32m    val eventualUpdatedMinutesDiff: Source[Set[Long], NotUsed] =[m
       Source(partitionUpdates(updates)).mapAsync(1) {[m
         case (partition, updates) => manifestsUpdate(partition, updates)[m
       }[m
     combineUpdateEffectsStream(eventualUpdatedMinutesDiff)[m
   }[m
 [m
[31m-  private def combineUpdateEffectsStream(effects: Source[UpdatedMillis, NotUsed]): Future[UpdatedMillis] =[m
[32m+[m[32m  private def combineUpdateEffectsStream(effects: Source[Set[Long], NotUsed]): Future[Set[Long]] =[m
     effects[m
[31m-      .fold[UpdatedMillis](UpdatedMillis.empty)(_ ++ _)[m
[32m+[m[32m      .fold[Set[Long]](Set.empty[Long])(_ ++ _)[m
       .log(getClass.getName)[m
       .runWith(Sink.seq)[m
[31m-      .map(_.foldLeft[UpdatedMillis](UpdatedMillis.empty)(_ ++ _))[m
[32m+[m[32m      .map(_.foldLeft[Set[Long]](Set.empty[Long])(_ ++ _))[m
       .recover { case t =>[m
         log.error("Failed to combine update effects", t)[m
[31m-        UpdatedMillis.empty[m
[32m+[m[32m        Set.empty[Long][m
       }[m
 [m
   private def persistLastSeenFileName(lastProcessedMarker: MillisSinceEpoch): Unit =[m
[1mdiff --git a/server/src/main/scala/actors/persistent/QueueLikeActor.scala b/server/src/main/scala/actors/persistent/QueueLikeActor.scala[m
[1mindex 671076efb..9b7af3f03 100644[m
[1m--- a/server/src/main/scala/actors/persistent/QueueLikeActor.scala[m
[1m+++ b/server/src/main/scala/actors/persistent/QueueLikeActor.scala[m
[36m@@ -4,7 +4,6 @@[m [mimport akka.persistence._[m
 import drt.shared.CrunchApi.MillisSinceEpoch[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import scalapb.GeneratedMessage[m
[31m-import uk.gov.homeoffice.drt.DataUpdates.Combinable[m
 import uk.gov.homeoffice.drt.actor.RecoveryActorLike[m
 import uk.gov.homeoffice.drt.actor.commands.Commands.GetState[m
 import uk.gov.homeoffice.drt.actor.commands._[m
[36m@@ -21,16 +20,16 @@[m [mobject QueueLikeActor {[m
 [m
   case object Tick[m
 [m
[31m-  object UpdatedMillis {[m
[31m-    val empty: UpdatedMillis = UpdatedMillis(Set())[m
[31m-  }[m
[32m+[m[32m  //  object UpdatedMillis {[m
[32m+[m[32m  //    val empty: UpdatedMillis = UpdatedMillis(Set())[m
[32m+[m[32m  //  }[m
 [m
[31m-  case class UpdatedMillis(effects: Set[MillisSinceEpoch]) extends Combinable[UpdatedMillis] {[m
[31m-    def ++(other: UpdatedMillis): UpdatedMillis = other match {[m
[31m-      case UpdatedMillis(toAdd) => UpdatedMillis(effects ++ toAdd)[m
[31m-      case _ => this[m
[31m-    }[m
[31m-  }[m
[32m+[m[32m  //  case class UpdatedMillis(effects: Set[MillisSinceEpoch]) extends Combinable[UpdatedMillis] {[m
[32m+[m[32m  //    def ++(other: UpdatedMillis): UpdatedMillis = other match {[m
[32m+[m[32m  //      case UpdatedMillis(toAdd) => UpdatedMillis(effects ++ toAdd)[m
[32m+[m[32m  //      case _ => this[m
[32m+[m[32m  //    }[m
[32m+[m[32m  //  }[m
 }[m
 [m
 abstract class QueueLikeActor(val now: () => SDateLike, processingRequest: MillisSinceEpoch => ProcessingRequest) extends RecoveryActorLike {[m
[36m@@ -100,18 +99,35 @@[m [mabstract class QueueLikeActor(val now: () => SDateLike, processingRequest: Milli[m
     case cr: ProcessingRequest =>[m
       self ! Seq(cr)[m
 [m
[31m-    case requests: Iterable[ProcessingRequest] =>[m
[31m-      updateState(requests)[m
[31m-      requests.headOption.map {[m
[31m-        case _: LoadProcessingRequest =>[m
[31m-          persistAndMaybeSnapshot(CrunchRequestsMessage(requests.collect {[m
[31m-            case r: LoadProcessingRequest => loadProcessingRequestToMessage(r)[m
[31m-          }.toList))[m
[31m-        case _: MergeArrivalsRequest =>[m
[31m-          persistAndMaybeSnapshot(MergeArrivalsRequestsMessage(requests.collect {[m
[31m-            case r: MergeArrivalsRequest => mergeArrivalRequestToMessage(r)[m
[31m-          }.toList))[m
[31m-      }[m
[32m+[m[32m    case requests: Iterable[_] =>[m
[32m+[m[32m      requests.headOption[m
[32m+[m[32m        .map {[m
[32m+[m[32m          case _: Long =>[m
[32m+[m[32m            requests[m
[32m+[m[32m              .collect { case l: MillisSinceEpoch => processingRequest(l) }[m
[32m+[m[32m              .filterNot(state.contains)[m
[32m+[m[32m          case _: ProcessingRequest =>[m
[32m+[m[32m            requests.collect { case r: ProcessingRequest => r }[m
[32m+[m[32m              .filterNot(state.contains)[m
[32m+[m[32m        }[m
[32m+[m[32m        .map { processingRequests =>[m
[32m+[m[32m          val maybeMessage = processingRequests.headOption.map {[m
[32m+[m[32m            case _: LoadProcessingRequest =>[m
[32m+[m[32m              CrunchRequestsMessage(processingRequests.collect {[m
[32m+[m[32m                case r: LoadProcessingRequest => loadProcessingRequestToMessage(r)[m
[32m+[m[32m              }.toList)[m
[32m+[m[32m            case _: MergeArrivalsRequest =>[m
[32m+[m[32m              MergeArrivalsRequestsMessage(processingRequests.collect {[m
[32m+[m[32m                case r: MergeArrivalsRequest => mergeArrivalRequestToMessage(r)[m
[32m+[m[32m              }.toList)[m
[32m+[m[32m          }[m
[32m+[m[32m          maybeMessage.foreach { msg =>[m
[32m+[m[32m            println(s"Persisting ${processingRequests.size} days to queue. Queue now contains ${state.size} days")[m
[32m+[m[32m            persistAndMaybeSnapshot(msg)[m
[32m+[m[32m            updateState(processingRequests)[m
[32m+[m[32m          }[m
[32m+[m
[32m+[m[32m        }[m
 [m
     case RemoveProcessingRequest(cr) =>[m
       log.info(s"Removing ${cr.date} from queue. Queue now contains ${state.size} days")[m
[36m@@ -120,7 +136,8 @@[m [mabstract class QueueLikeActor(val now: () => SDateLike, processingRequest: Milli[m
         year = Option(cr.date.year),[m
         month = Option(cr.date.month),[m
         day = Option(cr.date.day),[m
[31m-        terminalName = None))[m
[32m+[m[32m        terminalName = None,[m
[32m+[m[32m      ))[m
 [m
     case _: SaveSnapshotSuccess =>[m
       log.info(s"Successfully saved snapshot")[m
[1mdiff --git a/server/src/main/scala/actors/persistent/SortedActorRefSource.scala b/server/src/main/scala/actors/persistent/SortedActorRefSource.scala[m
[1mindex 9208a6b10..fa2a3ab5d 100644[m
[1m--- a/server/src/main/scala/actors/persistent/SortedActorRefSource.scala[m
[1m+++ b/server/src/main/scala/actors/persistent/SortedActorRefSource.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.persistent[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.ActorRef[m
 import akka.stream._[m
 import akka.stream.stage._[m
[1mdiff --git a/server/src/main/scala/actors/persistent/arrivals/ArrivalsActor.scala b/server/src/main/scala/actors/persistent/arrivals/ArrivalsActor.scala[m
[1mindex 623a9b4c6..ea3d4f066 100644[m
[1m--- a/server/src/main/scala/actors/persistent/arrivals/ArrivalsActor.scala[m
[1m+++ b/server/src/main/scala/actors/persistent/arrivals/ArrivalsActor.scala[m
[36m@@ -9,7 +9,6 @@[m [mimport scalapb.GeneratedMessage[m
 import services.graphstages.Crunch[m
 import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.StreamCompleted[m
 import uk.gov.homeoffice.drt.actor.commands.Commands.{AddUpdatesSubscriber, GetState}[m
[31m-import uk.gov.homeoffice.drt.actor.commands.MergeArrivalsRequest[m
 import uk.gov.homeoffice.drt.actor.state.ArrivalsState[m
 import uk.gov.homeoffice.drt.actor.{PersistentDrtActor, RecoveryActorLike, Sizes}[m
 import uk.gov.homeoffice.drt.arrivals.{Arrival, ArrivalsDiff, ArrivalsRestorer, UniqueArrival}[m
[36m@@ -18,7 +17,7 @@[m [mimport uk.gov.homeoffice.drt.ports.FeedSource[m
 import uk.gov.homeoffice.drt.protobuf.messages.FlightsMessage.{FeedStatusMessage, FlightStateSnapshotMessage, FlightsDiffMessage}[m
 import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion[m
 import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion._[m
[31m-import uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.SDateLike[m
 [m
 import scala.collection.immutable.SortedMap[m
 [m
[1mdiff --git a/server/src/main/scala/actors/routing/FeedArrivalsRouterActor.scala b/server/src/main/scala/actors/routing/FeedArrivalsRouterActor.scala[m
[1mindex 18f5b647c..aeea7057c 100644[m
[1m--- a/server/src/main/scala/actors/routing/FeedArrivalsRouterActor.scala[m
[1m+++ b/server/src/main/scala/actors/routing/FeedArrivalsRouterActor.scala[m
[36m@@ -3,6 +3,7 @@[m [mpackage actors.routing[m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor.{DateRangeMillisLike, PointInTimeQuery}[m
 import actors.daily.RequestAndTerminate[m
[32m+[m[32mimport actors.routing.FeedArrivalsRouterActor.FeedArrivals[m
 import akka.NotUsed[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
 import akka.pattern.ask[m
[36m@@ -13,7 +14,6 @@[m [mimport org.slf4j.{Logger, LoggerFactory}[m
 import services.SourceUtils[m
 import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates[m
 import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor[m
[31m-import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor.FeedArrivalsDiff[m
 import uk.gov.homeoffice.drt.arrivals._[m
 import uk.gov.homeoffice.drt.ports.Terminals[m
 import uk.gov.homeoffice.drt.ports.Terminals.Terminal[m
[36m@@ -24,6 +24,8 @@[m [mimport scala.concurrent.{ExecutionContext, Future}[m
 object FeedArrivalsRouterActor {[m
   private val log: Logger = LoggerFactory.getLogger(getClass)[m
 [m
[32m+[m[32m  case class FeedArrivals(arrivals: Seq[FeedArrival]) extends FlightUpdates[m
[32m+[m
   sealed trait query[m
 [m
   case class GetStateForDateRange(start: UtcDate, end: UtcDate) extends query with DateRangeMillisLike {[m
[36m@@ -39,11 +41,11 @@[m [mobject FeedArrivalsRouterActor {[m
   def updateFlights(requestAndTerminateActor: ActorRef,[m
                     props: (UtcDate, Terminal) => Props,[m
                    )[m
[31m-                   (implicit system: ActorSystem, timeout: Timeout): ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[Boolean] =[m
[31m-    (partition: (Terminal, UtcDate), diff: FlightUpdates) => {[m
[32m+[m[32m                   (implicit system: ActorSystem, timeout: Timeout): ((Terminals.Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean] =[m
[32m+[m[32m    (partition: (Terminal, UtcDate), arrivals: Seq[FeedArrival]) => {[m
       val (terminal, date) = partition[m
       val actor = system.actorOf(props(date, terminal))[m
[31m-      requestAndTerminateActor.ask(RequestAndTerminate(actor, diff)).mapTo[Boolean][m
[32m+[m[32m      requestAndTerminateActor.ask(RequestAndTerminate(actor, arrivals)).mapTo[Boolean][m
     }[m
 [m
   def feedArrivalsDayLookup(now: () => Long,[m
[36m@@ -86,8 +88,8 @@[m [mobject FeedArrivalsRouterActor {[m
 [m
 class FeedArrivalsRouterActor(allTerminals: Iterable[Terminal],[m
                               arrivalsByDayLookup: Option[MillisSinceEpoch] => UtcDate => Terminals.Terminal => Future[Seq[FeedArrival]],[m
[31m-                              updateArrivals: ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[Boolean],[m
[31m-                             ) extends RouterActorLikeWithSubscriber[FlightUpdates, (Terminal, UtcDate), Long] {[m
[32m+[m[32m                              updateArrivals: ((Terminals.Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean],[m
[32m+[m[32m                             ) extends RouterActorLikeWithSubscriber[FeedArrivals, (Terminal, UtcDate), Long] {[m
   override def receiveQueries: Receive = {[m
     case PointInTimeQuery(pit, FeedArrivalsRouterActor.GetStateForDateRange(start, end)) =>[m
       sender() ! flightsLookupService(start, end, allTerminals, Option(pit))[m
[36m@@ -105,28 +107,25 @@[m [mclass FeedArrivalsRouterActor(allTerminals: Iterable[Terminal],[m
   private val flightsLookupService: (UtcDate, UtcDate, Iterable[Terminal], Option[MillisSinceEpoch]) => Source[(UtcDate, Seq[FeedArrival]), NotUsed] =[m
     FeedArrivalsRouterActor.multiTerminalFlightsByDaySource(arrivalsByDayLookup)[m
 [m
[31m-  override def partitionUpdates: PartialFunction[FlightUpdates, Map[(Terminal, UtcDate), FlightUpdates]] = {[m
[31m-    case container: FeedArrivalsDiff[FeedArrival] =>[m
[31m-      val updates: Map[(Terminal, UtcDate), Iterable[FeedArrival]] = container.updates[m
[32m+[m[32m  override def partitionUpdates: PartialFunction[FeedArrivals, Map[(Terminal, UtcDate), FeedArrivals]] = {[m
[32m+[m[32m    case arrivals =>[m
[32m+[m[32m      println(s"**received ${arrivals.arrivals.size} arrivals to partition")[m
[32m+[m[32m      arrivals.arrivals[m
         .groupBy(arrivals => (arrivals.terminal, SDate(arrivals.scheduled).toUtcDate))[m
[31m-      val removals: Map[(Terminal, UtcDate), Iterable[UniqueArrival]] = container.removals[m
[31m-        .groupBy(arrival => (arrival.terminal, SDate(arrival.scheduled).toUtcDate))[m
[31m-[m
[31m-      val keys = updates.keys ++ removals.keys[m
[31m-      keys[m
[31m-        .map { terminalDay =>[m
[31m-          val terminalUpdates = updates.getOrElse(terminalDay, List())[m
[31m-          val terminalRemovals = removals.getOrElse(terminalDay, List())[m
[31m-          val diff = FeedArrivalsDiff(terminalUpdates, terminalRemovals)[m
[31m-          (terminalDay, diff)[m
[31m-        }[m
[31m-        .toMap[m
[32m+[m[32m        .view.mapValues(a => FeedArrivals(a.toSeq)).toMap[m
   }[m
 [m
[31m-  def updatePartition(partition: (Terminal, UtcDate), updates: FlightUpdates): Future[Set[Long]] =[m
[31m-    updateArrivals(partition, updates).map {[m
[31m-      case true => Set(SDate(partition._2).millisSinceEpoch)[m
[32m+[m[32m  override def updatePartition(partition: (Terminal, UtcDate), updates: FeedArrivals): Future[Set[Long]] = {[m
[32m+[m[32m    println(s"**sending ${updates.arrivals.size} arrivals to ${partition._1} on ${partition._2}")[m
[32m+[m[32m    updateArrivals(partition, updates.arrivals).map {[m
[32m+[m[32m      case true =>[m
[32m+[m[32m        println(s"** responding with updated millis for ${partition._1} on ${partition._2}")[m
[32m+[m[32m        Set(SDate(partition._2).millisSinceEpoch)[m
[32m+[m[32m      case false =>[m
[32m+[m[32m        println(s"** responding with no updates for ${partition._1} on ${partition._2}")[m
[32m+[m[32m        Set.empty[m
     }[m
[32m+[m[32m  }[m
 [m
[31m-  override def shouldSendEffectsToSubscriber: FlightUpdates => Boolean = _ => true[m
[32m+[m[32m  override def shouldSendEffectsToSubscriber: FeedArrivals => Boolean = _ => true[m
 }[m
[1mdiff --git a/server/src/main/scala/actors/routing/FlightsRouterActor.scala b/server/src/main/scala/actors/routing/FlightsRouterActor.scala[m
[1mindex 9ce600a42..42af8a9f9 100644[m
[1m--- a/server/src/main/scala/actors/routing/FlightsRouterActor.scala[m
[1m+++ b/server/src/main/scala/actors/routing/FlightsRouterActor.scala[m
[36m@@ -2,7 +2,6 @@[m [mpackage actors.routing[m
 [m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor._[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.routing.minutes.MinutesActorLike.{FlightsLookup, FlightsUpdate}[m
 import akka.NotUsed[m
 import akka.stream.Materializer[m
[1mdiff --git a/server/src/main/scala/actors/routing/minutes/MinutesActorLike.scala b/server/src/main/scala/actors/routing/minutes/MinutesActorLike.scala[m
[1mindex 0f35c851f..0d948880b 100644[m
[1m--- a/server/src/main/scala/actors/routing/minutes/MinutesActorLike.scala[m
[1m+++ b/server/src/main/scala/actors/routing/minutes/MinutesActorLike.scala[m
[36m@@ -2,7 +2,6 @@[m [mpackage actors.routing.minutes[m
 [m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor._[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.routing.minutes.MinutesActorLike.{MinutesLookup, MinutesUpdate}[m
 import actors.routing.{RouterActorLike, RouterActorLike2, SequentialAccessActor}[m
 import akka.NotUsed[m
[36m@@ -33,7 +32,7 @@[m [mobject MinutesActorLike {[m
 [m
   type MinutesUpdate[A, B <: WithTimeAccessor, U] = ((Terminals.Terminal, UtcDate), MinutesContainer[A, B]) => Future[Set[U]][m
   type FlightsUpdate = ((Terminals.Terminal, UtcDate), FlightUpdates) => Future[Set[Long]][m
[31m-  type ManifestsUpdate = (UtcDate, VoyageManifests) => Future[UpdatedMillis][m
[32m+[m[32m  type ManifestsUpdate = (UtcDate, VoyageManifests) => Future[Set[Long]][m
 [m
   case object ProcessNextUpdateRequest[m
 [m
[1mdiff --git a/server/src/main/scala/controllers/application/PortStateController.scala b/server/src/main/scala/controllers/application/PortStateController.scala[m
[1mindex c0e0a5cd8..71fe049b1 100644[m
[1m--- a/server/src/main/scala/controllers/application/PortStateController.scala[m
[1m+++ b/server/src/main/scala/controllers/application/PortStateController.scala[m
[36m@@ -3,7 +3,6 @@[m [mpackage controllers.application[m
 import actors.CrunchManagerActor.{RecalculateArrivals, Recrunch}[m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor.{GetStateForDateRange, GetStateForTerminalDateRange, GetUpdatesSince, PointInTimeQuery}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.pattern.ask[m
 import akka.util.Timeout[m
 import com.google.inject.Inject[m
[36m@@ -128,7 +127,7 @@[m [mclass PortStateController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInt[m
         to <- LocalDate.parse(toStr)[m
       } yield {[m
         val datesToReCrunch = DateRange(from, to).map { localDate => SDate(localDate).millisSinceEpoch }.toSet[m
[31m-        ctrl.applicationService.crunchManagerActor ! UpdatedMillis(datesToReCrunch)[m
[32m+[m[32m        ctrl.applicationService.crunchManagerActor ! datesToReCrunch[m
         Ok(s"Queued dates $from to $to for re-crunch")[m
       }[m
       maybeFuture.getOrElse(BadRequest(s"Unable to parse dates '$fromStr' or '$toStr'"))[m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/AzinqFeed.scala b/server/src/main/scala/drt/server/feeds/AzinqFeed.scala[m
[1mindex 6a890f8d6..d7788b582 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/AzinqFeed.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/AzinqFeed.scala[m
[36m@@ -8,10 +8,9 @@[m [mimport akka.http.scaladsl.unmarshalling.Unmarshal[m
 import akka.stream.Materializer[m
 import akka.stream.scaladsl.Source[m
 import drt.server.feeds.Feed.FeedTick[m
[31m-import drt.shared.FlightsApi.Flights[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import spray.json.{DefaultJsonProtocol, RootJsonFormat}[m
[31m-import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.FeedArrival[m
 [m
 import scala.concurrent.{ExecutionContext, Future}[m
 [m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/api/ManifestArrivalKeys.scala b/server/src/main/scala/drt/server/feeds/api/ManifestArrivalKeys.scala[m
[1mindex 8b0625d28..58ef38dac 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/api/ManifestArrivalKeys.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/api/ManifestArrivalKeys.scala[m
[36m@@ -76,3 +76,4 @@[m [mcase class DbManifestArrivalKeys(tables: Tables, destinationPortCode: PortCode)[m
         }[m
       }[m
 }[m
[41m+[m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/cirium/CiriumFeed.scala b/server/src/main/scala/drt/server/feeds/cirium/CiriumFeed.scala[m
[1mindex 09259add2..6143d3140 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/cirium/CiriumFeed.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/cirium/CiriumFeed.scala[m
[36m@@ -11,7 +11,7 @@[m [mimport drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeed[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import uk.gov.homeoffice.cirium.JsonSupport._[m
 import uk.gov.homeoffice.cirium.services.entities.CiriumFlightStatus[m
[31m-import uk.gov.homeoffice.drt.arrivals.{FeedArrival, LiveArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.LiveArrival[m
 import uk.gov.homeoffice.drt.ports.PortCode[m
 import uk.gov.homeoffice.drt.ports.Terminals.{A2, InvalidTerminal, T1, Terminal}[m
 import uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/lcy/LCYClient.scala b/server/src/main/scala/drt/server/feeds/lcy/LCYClient.scala[m
[1mindex 73a2db1b8..498bedddf 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/lcy/LCYClient.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/lcy/LCYClient.scala[m
[36m@@ -6,9 +6,8 @@[m [mimport akka.http.scaladsl.model._[m
 import akka.http.scaladsl.model.headers.{BasicHttpCredentials, ModeledCustomHeader, ModeledCustomHeaderCompanion}[m
 import akka.http.scaladsl.unmarshalling.{Unmarshal, Unmarshaller}[m
 import akka.stream.Materializer[m
[31m-import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}[m
 import drt.server.feeds.common.HttpClient[m
[31m-import drt.shared.FlightsApi.Flights[m
[32m+[m[32mimport drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}[m
 import org.slf4j.{Logger, LoggerFactory}[m
 [m
 import scala.concurrent.ExecutionContext.Implicits.global[m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXFeed.scala b/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXFeed.scala[m
[1mindex b6924678b..61f1d2464 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXFeed.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXFeed.scala[m
[36m@@ -1,9 +1,9 @@[m
 package drt.server.feeds.legacy.bhx[m
 [m
 import uk.co.bhx.online.flightinformation.FlightInformationSoap[m
[31m-import uk.gov.homeoffice.drt.arrivals.{Arrival, ForecastArrival, LiveArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.{ForecastArrival, LiveArrival}[m
 [m
[31m-import scala.collection.JavaConverters._[m
[32m+[m[32mimport scala.jdk.CollectionConverters.CollectionHasAsScala[m
 [m
 case class BHXFeed(serviceSoap: FlightInformationSoap) extends BHXLiveArrivals with BHXForecastArrivals {[m
 [m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXForecastFeedLegacy.scala b/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXForecastFeedLegacy.scala[m
[1mindex 986058331..0667bd826 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXForecastFeedLegacy.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/legacy/bhx/BHXForecastFeedLegacy.scala[m
[36m@@ -2,9 +2,8 @@[m [mpackage drt.server.feeds.legacy.bhx[m
 [m
 import akka.actor.typed[m
 import akka.stream.scaladsl.Source[m
[31m-import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}[m
 import drt.server.feeds.Feed.FeedTick[m
[31m-import drt.shared.FlightsApi.Flights[m
[32m+[m[32mimport drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess}[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import uk.gov.homeoffice.drt.time.SDate[m
 [m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/lgw/LgwForecastFeed.scala b/server/src/main/scala/drt/server/feeds/lgw/LgwForecastFeed.scala[m
[1mindex e9f9830be..a7a12de35 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/lgw/LgwForecastFeed.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/lgw/LgwForecastFeed.scala[m
[36m@@ -4,7 +4,6 @@[m [mimport akka.actor.ActorSystem[m
 import akka.actor.typed.ActorRef[m
 import akka.stream.scaladsl.Source[m
 import drt.server.feeds.{ArrivalsFeedFailure, ArrivalsFeedResponse, ArrivalsFeedSuccess, Feed}[m
[31m-import drt.shared.FlightsApi.Flights[m
 [m
 object LgwForecastFeed {[m
   private val log = org.slf4j.LoggerFactory.getLogger(getClass)[m
[1mdiff --git a/server/src/main/scala/drt/server/feeds/lhr/forecast/LHRForecastXLSExtractor.scala b/server/src/main/scala/drt/server/feeds/lhr/forecast/LHRForecastXLSExtractor.scala[m
[1mindex 3d21492d2..8fe60e8a0 100644[m
[1m--- a/server/src/main/scala/drt/server/feeds/lhr/forecast/LHRForecastXLSExtractor.scala[m
[1m+++ b/server/src/main/scala/drt/server/feeds/lhr/forecast/LHRForecastXLSExtractor.scala[m
[36m@@ -5,7 +5,7 @@[m [mimport drt.server.feeds.common.XlsExtractorUtil._[m
 import drt.server.feeds.lhr.LHRForecastFeed[m
 import org.apache.poi.ss.usermodel.DateUtil[m
 import org.slf4j.{Logger, LoggerFactory}[m
[31m-import uk.gov.homeoffice.drt.arrivals.{Arrival, ForecastArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.ForecastArrival[m
 import uk.gov.homeoffice.drt.time.TimeZoneHelper.europeLondonId[m
 import uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
 [m
[1mdiff --git a/server/src/main/scala/services/arrivals/ArrivalsAdjustmentsLike.scala b/server/src/main/scala/services/arrivals/ArrivalsAdjustmentsLike.scala[m
[1mindex e3c859655..962bb32a5 100644[m
[1m--- a/server/src/main/scala/services/arrivals/ArrivalsAdjustmentsLike.scala[m
[1m+++ b/server/src/main/scala/services/arrivals/ArrivalsAdjustmentsLike.scala[m
[36m@@ -1,7 +1,7 @@[m
 package services.arrivals[m
 [m
 import org.slf4j.{Logger, LoggerFactory}[m
[31m-import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.Arrival[m
 import uk.gov.homeoffice.drt.ports.PortCode[m
 [m
 trait ArrivalsAdjustmentsLike {[m
[1mdiff --git a/server/src/main/scala/services/arrivals/EdiArrivalsTerminalAdjustments.scala b/server/src/main/scala/services/arrivals/EdiArrivalsTerminalAdjustments.scala[m
[1mindex a85d7882e..4d7b3d40b 100644[m
[1m--- a/server/src/main/scala/services/arrivals/EdiArrivalsTerminalAdjustments.scala[m
[1m+++ b/server/src/main/scala/services/arrivals/EdiArrivalsTerminalAdjustments.scala[m
[36m@@ -1,7 +1,7 @@[m
 package services.arrivals[m
 [m
 import org.slf4j.{Logger, LoggerFactory}[m
[31m-import uk.gov.homeoffice.drt.arrivals.{Arrival, FeedArrival, LiveArrival}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.Arrival[m
 import uk.gov.homeoffice.drt.ports.Terminals.{A1, A2}[m
 [m
 object EdiArrivalsTerminalAdjustments extends ArrivalsAdjustmentsLike {[m
[1mdiff --git a/server/src/main/scala/services/crunch/CrunchManager.scala b/server/src/main/scala/services/crunch/CrunchManager.scala[m
[1mindex 2cc96cb8c..887a03421 100644[m
[1m--- a/server/src/main/scala/services/crunch/CrunchManager.scala[m
[1m+++ b/server/src/main/scala/services/crunch/CrunchManager.scala[m
[36m@@ -1,7 +1,6 @@[m
 package services.crunch[m
 [m
 import actors.CrunchManagerActor.{ReProcessDates, Recrunch}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.ActorRef[m
 import akka.pattern.ask[m
 import akka.util.Timeout[m
[36m@@ -15,13 +14,13 @@[m [mimport scala.concurrent.ExecutionContext[m
 object CrunchManager {[m
   private val log = LoggerFactory.getLogger(getClass)[m
 [m
[31m-  def queueDaysToReProcess(crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike, message: UpdatedMillis => ReProcessDates): Unit = {[m
[32m+[m[32m  def queueDaysToReProcess(crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike, message: Set[Long] => ReProcessDates): Unit = {[m
     val today = now()[m
     val millisToCrunchStart = Crunch.crunchStartWithOffset(offsetMinutes) _[m
     val daysToReCrunch = (0 until forecastMaxDays).map(d => {[m
       millisToCrunchStart(today.addDays(d)).millisSinceEpoch[m
     }).toSet[m
[31m-    crunchManager ! message(UpdatedMillis(daysToReCrunch))[m
[32m+[m[32m    crunchManager ! message(daysToReCrunch)[m
   }[m
 [m
   def queueDaysToReCrunchWithUpdatedSplits(flightsActor: ActorRef, crunchManager: ActorRef, offsetMinutes: Int, forecastMaxDays: Int, now: () => SDateLike)[m
[1mdiff --git a/server/src/main/scala/services/crunch/RunnableCrunch.scala b/server/src/main/scala/services/crunch/RunnableCrunch.scala[m
[1mindex 4ef0d9e36..fd3702e51 100644[m
[1m--- a/server/src/main/scala/services/crunch/RunnableCrunch.scala[m
[1m+++ b/server/src/main/scala/services/crunch/RunnableCrunch.scala[m
[36m@@ -1,5 +1,6 @@[m
 package services.crunch[m
 [m
[32m+[m[32mimport actors.routing.FeedArrivalsRouterActor.FeedArrivals[m
 import akka.actor.ActorRef[m
 import akka.pattern.StatusReply.Ack[m
 import akka.stream._[m
[36m@@ -8,10 +9,8 @@[m [mimport drt.server.feeds.{ArrivalsFeedResponse, ArrivalsFeedSuccess, ManifestsFee[m
 import drt.shared.CrunchApi._[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import services.StreamSupervision[m
[31m-import services.metrics.Metrics[m
 import uk.gov.homeoffice.drt.actor.acking.AckingReceiver.{StreamCompleted, StreamFailure, StreamInitialized}[m
[31m-import uk.gov.homeoffice.drt.arrivals.{ArrivalsDiff, FeedArrival}[m
[31m-import uk.gov.homeoffice.drt.time.{SDate, UtcDate}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.FeedArrival[m
 [m
 import scala.concurrent.{ExecutionContext, Future}[m
 [m
[36m@@ -21,26 +20,26 @@[m [mobject RunnableCrunch {[m
   val oneDayMillis: Int = 60 * 60 * 24 * 1000[m
 [m
   def apply[FR, MS, SAD](forecastBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[31m-                             forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[31m-                             liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[31m-                             liveArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[31m-                             manifestsLiveSource: Source[ManifestsFeedResponse, MS],[m
[31m-                             actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],[m
[31m-                             forecastBaseArrivalsActor: ActorRef,[m
[31m-                             forecastArrivalsActor: ActorRef,[m
[31m-                             liveBaseArrivalsActor: ActorRef,[m
[31m-                             liveArrivalsActor: ActorRef,[m
[31m-                             applyPaxDeltas: List[FeedArrival] => Future[List[FeedArrival]],[m
[31m-[m
[31m-                             manifestsActor: ActorRef,[m
[31m-[m
[31m-                             portStateActor: ActorRef,[m
[31m-[m
[31m-                             forecastMaxMillis: () => MillisSinceEpoch[m
[31m-                            )[m
[31m-                            (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, MS, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {[m
[31m-[m
[31m-    val arrivalsKillSwitch = KillSwitches.single[ArrivalsFeedResponse][m
[32m+[m[32m                         forecastArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[32m+[m[32m                         liveBaseArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[32m+[m[32m                         liveArrivalsSource: Source[ArrivalsFeedResponse, FR],[m
[32m+[m[32m                         manifestsLiveSource: Source[ManifestsFeedResponse, MS],[m
[32m+[m[32m                         actualDesksAndWaitTimesSource: Source[ActualDeskStats, SAD],[m
[32m+[m[32m                         forecastBaseArrivalsActor: ActorRef,[m
[32m+[m[32m                         forecastArrivalsActor: ActorRef,[m
[32m+[m[32m                         liveBaseArrivalsActor: ActorRef,[m
[32m+[m[32m                         liveArrivalsActor: ActorRef,[m
[32m+[m[32m                         applyPaxDeltas: List[FeedArrival] => Future[List[FeedArrival]],[m
[32m+[m
[32m+[m[32m                         manifestsActor: ActorRef,[m
[32m+[m
[32m+[m[32m                         portStateActor: ActorRef,[m
[32m+[m
[32m+[m[32m                         forecastMaxMillis: () => MillisSinceEpoch[m
[32m+[m[32m                        )[m
[32m+[m[32m                        (implicit ec: ExecutionContext): RunnableGraph[(FR, FR, FR, FR, MS, SAD, UniqueKillSwitch, UniqueKillSwitch)] = {[m
[32m+[m
[32m+[m[32m    val arrivalsKillSwitch = KillSwitches.single[FeedArrivals][m
     val manifestsLiveKillSwitch = KillSwitches.single[ManifestsFeedResponse][m
 [m
     import akka.stream.scaladsl.GraphDSL.Implicits._[m
[36m@@ -83,26 +82,33 @@[m [mobject RunnableCrunch {[m
 [m
           // @formatter:off[m
           forecastBaseArrivalsSourceSync.out.map {[m
[31m-            case ArrivalsFeedSuccess(as, ca) =>[m
[32m+[m[32m            case ArrivalsFeedSuccess(as, _) =>[m
               val maxScheduledMillis = forecastMaxMillis()[m
[31m-              ArrivalsFeedSuccess(as.filter(_.scheduled < maxScheduledMillis), ca)[m
[31m-            case failure => failure[m
[32m+[m[32m              FeedArrivals(as.filter(_.scheduled < maxScheduledMillis))[m
[32m+[m[32m            case _ =>[m
[32m+[m[32m              FeedArrivals(List())[m
           }.mapAsync(1) {[m
[31m-            case ArrivalsFeedSuccess(as, _) =>[m
[31m-              applyPaxDeltas(as.toList)[m
[31m-                .map(updated => ArrivalsFeedSuccess(updated, SDate.now()))[m
[31m-            case failure => Future.successful(failure)[m
[32m+[m[32m            case FeedArrivals(as) =>[m
[32m+[m[32m              applyPaxDeltas(as.toList).map(FeedArrivals(_))[m
           } ~> baseArrivalsSink[m
 [m
[31m-          forecastArrivalsSourceSync ~> fcstArrivalsSink[m
[32m+[m[32m          forecastArrivalsSourceSync.out.map {[m
[32m+[m[32m            case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)[m
[32m+[m[32m            case _ => FeedArrivals(List())[m
[32m+[m[32m          } ~> fcstArrivalsSink[m
 [m
[31m-          liveBaseArrivalsSourceSync ~> liveBaseArrivalsSink[m
[32m+[m[32m          liveBaseArrivalsSourceSync.out.map {[m
[32m+[m[32m            case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)[m
[32m+[m[32m            case _ => FeedArrivals(List())[m
[32m+[m[32m          } ~> liveBaseArrivalsSink[m
 [m
[31m-          liveArrivalsSourceSync ~> arrivalsKillSwitchSync ~> liveArrivalsSink[m
[32m+[m[32m          liveArrivalsSourceSync.out.map {[m
[32m+[m[32m            case ArrivalsFeedSuccess(as, _) => FeedArrivals(as)[m
[32m+[m[32m            case _ => FeedArrivals(List())[m
[32m+[m[32m          } ~> arrivalsKillSwitchSync ~> liveArrivalsSink[m
 [m
           manifestsLiveSourceSync.out.collect {[m
             case ManifestsFeedSuccess(manifests, createdAt) =>[m
[31m-              Metrics.successCounter("manifestsLive.arrival", manifests.length)[m
               ManifestsFeedSuccess(manifests, createdAt)[m
           } ~> manifestsLiveKillSwitchSync ~> manifestsSink[m
 [m
[36m@@ -117,17 +123,4 @@[m [mobject RunnableCrunch {[m
       .fromGraph(graph)[m
       .withAttributes(StreamSupervision.resumeStrategyWithLog(RunnableCrunch.getClass.getName))[m
   }[m
[31m-[m
[31m-  private def addPredictionsToDiff(addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff], date: UtcDate, diff: ArrivalsDiff)[m
[31m-                                  (implicit executionContext: ExecutionContext): Future[ArrivalsDiff] =[m
[31m-    if (diff.toUpdate.nonEmpty) {[m
[31m-      log.info(f"Looking up arrival predictions for ${diff.toUpdate.size} arrivals on ${date.day}%02d/${date.month}%02d/${date.year}")[m
[31m-      val startMillis = SDate.now().millisSinceEpoch[m
[31m-      val withoutPredictions = diff.toUpdate.count(_._2.predictedTouchdown.isEmpty)[m
[31m-      addArrivalPredictions(diff).map { diffWithPredictions =>[m
[31m-        val millisTaken = SDate.now().millisSinceEpoch - startMillis[m
[31m-        log.info(s"Arrival prediction lookups finished for $withoutPredictions arrivals. Took ${millisTaken}ms")[m
[31m-        diffWithPredictions[m
[31m-      }[m
[31m-    } else Future.successful(diff)[m
 }[m
[1mdiff --git a/server/src/main/scala/services/crunch/deskrecs/DynamicRunnableDeskRecs.scala b/server/src/main/scala/services/crunch/deskrecs/DynamicRunnableDeskRecs.scala[m
[1mindex d8b48f4c4..852477c0e 100644[m
[1m--- a/server/src/main/scala/services/crunch/deskrecs/DynamicRunnableDeskRecs.scala[m
[1m+++ b/server/src/main/scala/services/crunch/deskrecs/DynamicRunnableDeskRecs.scala[m
[36m@@ -1,15 +1,13 @@[m
 package services.crunch.deskrecs[m
 [m
 import akka.NotUsed[m
[31m-import akka.stream.scaladsl.{Flow, Source}[m
[32m+[m[32mimport akka.stream.scaladsl.Flow[m
 import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer, PassengersMinute}[m
 import drt.shared._[m
[31m-import manifests.passengers.{ManifestLike, ManifestPaxCount}[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import services.crunch.desklimits.TerminalDeskLimitsLike[m
 import services.crunch.deskrecs.DynamicRunnableDeployments.PassengersToQueueMinutes[m
 import uk.gov.homeoffice.drt.actor.commands.ProcessingRequest[m
[31m-import uk.gov.homeoffice.drt.arrivals.Arrival[m
 import uk.gov.homeoffice.drt.ports.Terminals.Terminal[m
 [m
 import scala.concurrent.{ExecutionContext, Future}[m
[1mdiff --git a/server/src/main/scala/services/crunch/deskrecs/OptimisationProviders.scala b/server/src/main/scala/services/crunch/deskrecs/OptimisationProviders.scala[m
[1mindex f0740284c..ac3caf8ba 100644[m
[1m--- a/server/src/main/scala/services/crunch/deskrecs/OptimisationProviders.scala[m
[1m+++ b/server/src/main/scala/services/crunch/deskrecs/OptimisationProviders.scala[m
[36m@@ -4,11 +4,10 @@[m [mimport actors.PartitionedPortStateActor.{GetFlights, GetStateForDateRange}[m
 import akka.NotUsed[m
 import akka.actor.ActorRef[m
 import akka.pattern.ask[m
[31m-import akka.stream.Materializer[m
[31m-import akka.stream.scaladsl.{Sink, Source}[m
[32m+[m[32mimport akka.stream.scaladsl.Source[m
 import akka.util.Timeout[m
 import drt.shared.CrunchApi.{MillisSinceEpoch, MinutesContainer, PassengersMinute, StaffMinute}[m
[31m-import drt.shared.{ArrivalKey, TM, TQM}[m
[32m+[m[32mimport drt.shared.{TM, TQM}[m
 import manifests.ManifestLookupLike[m
 import manifests.passengers.{ManifestLike, ManifestPaxCount}[m
 import org.slf4j.{Logger, LoggerFactory}[m
[1mdiff --git a/server/src/main/scala/services/crunch/deskrecs/PortDesksAndWaitsProvider.scala b/server/src/main/scala/services/crunch/deskrecs/PortDesksAndWaitsProvider.scala[m
[1mindex a8c6741d8..c4aa0eb2f 100644[m
[1m--- a/server/src/main/scala/services/crunch/deskrecs/PortDesksAndWaitsProvider.scala[m
[1m+++ b/server/src/main/scala/services/crunch/deskrecs/PortDesksAndWaitsProvider.scala[m
[36m@@ -8,7 +8,6 @@[m [mimport services.TryCrunchWholePax[m
 import services.crunch.desklimits.TerminalDeskLimitsLike[m
 import services.crunch.deskrecs[m
 import services.graphstages.{DynamicWorkloadCalculator, FlightFilter, WorkloadCalculatorLike}[m
[31m-import services.TryCrunchWholePax[m
 import uk.gov.homeoffice.drt.arrivals.FlightsWithSplits[m
 import uk.gov.homeoffice.drt.ports.Queues._[m
 import uk.gov.homeoffice.drt.ports.Terminals.Terminal[m
[1mdiff --git a/server/src/main/scala/services/exports/Forecast.scala b/server/src/main/scala/services/exports/Forecast.scala[m
[1mindex ef5879f47..d3af520c6 100644[m
[1m--- a/server/src/main/scala/services/exports/Forecast.scala[m
[1m+++ b/server/src/main/scala/services/exports/Forecast.scala[m
[36m@@ -2,13 +2,10 @@[m [mpackage services.exports[m
 [m
 import drt.shared.CrunchApi._[m
 import drt.shared.PortState[m
[31m-import uk.gov.homeoffice.drt.time.SDate[m
 import uk.gov.homeoffice.drt.ports.AirportConfig[m
 import uk.gov.homeoffice.drt.ports.Queues.Queue[m
 import uk.gov.homeoffice.drt.ports.Terminals.Terminal[m
[31m-import uk.gov.homeoffice.drt.time.SDateLike[m
[31m-[m
[31m-import scala.collection.immutable[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
 [m
 object Forecast {[m
   def headlineFigures(startOfForecast: SDateLike,[m
[1mdiff --git a/server/src/main/scala/services/healthcheck/ArrivalUpdatesHealthCheck.scala b/server/src/main/scala/services/healthcheck/ArrivalUpdatesHealthCheck.scala[m
[1mindex 87ebeb8b3..461c57bd5 100644[m
[1m--- a/server/src/main/scala/services/healthcheck/ArrivalUpdatesHealthCheck.scala[m
[1m+++ b/server/src/main/scala/services/healthcheck/ArrivalUpdatesHealthCheck.scala[m
[36m@@ -4,7 +4,6 @@[m [mimport akka.NotUsed[m
 import akka.stream.Materializer[m
 import akka.stream.scaladsl.Source[m
 import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits[m
[31m-import uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.ApiSplitsWithHistoricalEGateAndFTPercentages[m
 import uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}[m
 [m
 import scala.concurrent.ExecutionContext[m
[1mdiff --git a/server/src/main/scala/services/healthcheck/PercentageHealthCheck.scala b/server/src/main/scala/services/healthcheck/PercentageHealthCheck.scala[m
[1mindex fb972c31c..d9a9be7c7 100644[m
[1m--- a/server/src/main/scala/services/healthcheck/PercentageHealthCheck.scala[m
[1m+++ b/server/src/main/scala/services/healthcheck/PercentageHealthCheck.scala[m
[36m@@ -4,7 +4,7 @@[m [mimport akka.NotUsed[m
 import akka.stream.Materializer[m
 import akka.stream.scaladsl.{Sink, Source}[m
 import uk.gov.homeoffice.drt.arrivals.ApiFlightWithSplits[m
[31m-import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.{SDateLike, UtcDate}[m
 [m
 import scala.concurrent.{ExecutionContext, Future}[m
 [m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ActorsServiceLike.scala b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ActorsServiceLike.scala[m
[1mindex 84564566e..06c79ed2d 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ActorsServiceLike.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ActorsServiceLike.scala[m
[36m@@ -3,6 +3,7 @@[m [mpackage uk.gov.homeoffice.drt.crunchsystem[m
 import akka.actor.ActorRef[m
 [m
 trait ActorsServiceLike {[m
[32m+[m[32m  val requestAndTerminateActor: ActorRef[m
   val portStateActor: ActorRef[m
   val liveShiftsReadActor: ActorRef[m
   val liveFixedPointsReadActor: ActorRef[m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/DrtSystemInterface.scala b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/DrtSystemInterface.scala[m
[1mindex 6cb1dae20..efbb602c7 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/DrtSystemInterface.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/DrtSystemInterface.scala[m
[36m@@ -91,7 +91,8 @@[m [mtrait DrtSystemInterface extends UserRoleProviderLike[m
     manifestLookupService = manifestLookupService,[m
     minuteLookups = minuteLookups,[m
     actorService = actorService,[m
[31m-    persistentStateActors = persistentActors[m
[32m+[m[32m    persistentStateActors = persistentActors,[m
[32m+[m[32m    requestAndTerminateActor = actorService.requestAndTerminateActor,[m
   )[m
   def run(): Unit[m
 [m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ProdDrtSystem.scala b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ProdDrtSystem.scala[m
[1mindex 1aad1eb77..37992079a 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ProdDrtSystem.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/crunchsystem/ProdDrtSystem.scala[m
[36m@@ -51,12 +51,14 @@[m [mcase class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtPara[m
 [m
   override val abFeatureService: IABFeatureDao = ABFeatureDao(AggregateDb.db)[m
 [m
[31m-  lazy override val actorService: ActorsServiceLike = ActorsServiceService(journalType = StreamingJournal.forConfig(config),[m
[32m+[m[32m  lazy override val actorService: ActorsServiceLike = ActorsServiceService([m
[32m+[m[32m    journalType = StreamingJournal.forConfig(config),[m
     airportConfig = airportConfig,[m
     now = now,[m
     forecastMaxDays = params.forecastMaxDays,[m
     flightLookups = flightLookups,[m
[31m-    minuteLookups = minuteLookups)[m
[32m+[m[32m    minuteLookups = minuteLookups,[m
[32m+[m[32m  )[m
 [m
   lazy val feedService: FeedService = ProdFeedService([m
     journalType = journalType,[m
[36m@@ -67,7 +69,7 @@[m [mcase class ProdDrtSystem @Inject()(airportConfig: AirportConfig, params: DrtPara[m
     paxFeedSourceOrder = paxFeedSourceOrder,[m
     flightLookups = flightLookups,[m
     manifestLookups = manifestLookups,[m
[31m-    requestAndTerminateActor = applicationService.requestAndTerminateActor,[m
[32m+[m[32m    requestAndTerminateActor = actorService.requestAndTerminateActor,[m
   )[m
 [m
 [m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/service/ActorsServiceService.scala b/server/src/main/scala/uk/gov/homeoffice/drt/service/ActorsServiceService.scala[m
[1mindex 8535023e0..57376a2dc 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/service/ActorsServiceService.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/service/ActorsServiceService.scala[m
[36m@@ -2,9 +2,10 @@[m [mpackage uk.gov.homeoffice.drt.service[m
 [m
 import actors.PartitionedPortStateActor.{flightUpdatesProps, queueUpdatesProps, staffUpdatesProps}[m
 import actors._[m
[31m-import actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, StaffUpdatesSupervisor}[m
[32m+[m[32mimport actors.daily.{FlightUpdatesSupervisor, QueueUpdatesSupervisor, RequestAndTerminateActor, StaffUpdatesSupervisor}[m
 import actors.persistent.staffing.{FixedPointsActor, ShiftsActor, StaffMovementsActor}[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
[32m+[m[32mimport akka.util.Timeout[m
 import uk.gov.homeoffice.drt.crunchsystem.ActorsServiceLike[m
 import uk.gov.homeoffice.drt.ports.AirportConfig[m
 import uk.gov.homeoffice.drt.time.SDateLike[m
[36m@@ -17,8 +18,10 @@[m [mcase class ActorsServiceService(journalType: StreamingJournalLike,[m
                                 now: () => SDateLike,[m
                                 forecastMaxDays: Int,[m
                                 flightLookups: FlightLookupsLike,[m
[31m-                                minuteLookups: MinuteLookupsLike)(implicit system: ActorSystem) extends ActorsServiceLike {[m
[31m-[m
[32m+[m[32m                                minuteLookups: MinuteLookupsLike,[m
[32m+[m[32m                               )[m
[32m+[m[32m                               (implicit system: ActorSystem, timeout: Timeout) extends ActorsServiceLike {[m
[32m+[m[32m  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "request-and-terminate-actor")[m
   override val liveShiftsReadActor: ActorRef = system.actorOf(ShiftsActor.streamingUpdatesProps([m
     journalType, airportConfig.minutesToCrunch, now), name = "shifts-read-actor")[m
   override val liveFixedPointsReadActor: ActorRef = system.actorOf(FixedPointsActor.streamingUpdatesProps([m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala b/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[1mindex e847cb6f7..b52e576fd 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[36m@@ -3,7 +3,7 @@[m [mpackage uk.gov.homeoffice.drt.service[m
 import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber}[m
 import actors.DrtStaticParameters.{startOfTheMonth, time48HoursAgo}[m
 import actors._[m
[31m-import actors.daily.{PassengersActor, RequestAndTerminateActor}[m
[32m+[m[32mimport actors.daily.PassengersActor[m
 import actors.persistent._[m
 import actors.persistent.staffing.{FixedPointsActor, GetFeedStatuses, ShiftsActor, StaffMovementsActor}[m
 import akka.actor.{ActorRef, ActorSystem, Props, typed}[m
[36m@@ -60,7 +60,6 @@[m [mimport uk.gov.homeoffice.drt.time._[m
 [m
 import javax.inject.Singleton[m
 import scala.collection.SortedSet[m
[31m-import scala.collection.immutable.SortedMap[m
 import scala.concurrent.duration.{DurationInt, DurationLong}[m
 import scala.concurrent.{Await, ExecutionContext, Future}[m
 import scala.util.{Failure, Success}[m
[36m@@ -77,7 +76,9 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
                               manifestLookupService: ManifestLookupLike,[m
                               minuteLookups: MinuteLookupsLike,[m
                               actorService: ActorsServiceLike,[m
[31m-                              persistentStateActors: PersistentStateActors)[m
[32m+[m[32m                              persistentStateActors: PersistentStateActors,[m
[32m+[m[32m                              requestAndTerminateActor: ActorRef,[m
[32m+[m[32m                             )[m
                              (implicit system: ActorSystem, ec: ExecutionContext, mat: Materializer, timeout: Timeout) {[m
   val log: Logger = LoggerFactory.getLogger(getClass)[m
   private val walkTimeProvider: (Terminal, String, String) => Option[Int] = WalkTimeProvider(params.gateWalkTimesFilePath, params.standWalkTimesFilePath)[m
[36m@@ -176,8 +177,6 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
     airportConfig.minutesToCrunch,[m
     params.forecastMaxDays)), "egate-banks-updates-actor")[m
 [m
[31m-  val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor()), "request-and-terminate-actor")[m
[31m-[m
   val shiftsSequentialWritesActor: ActorRef = system.actorOf(ShiftsActor.sequentialWritesProps([m
     now, startOfTheMonth(now), requestAndTerminateActor, system), "shifts-sequential-writes-actor")[m
   val fixedPointsSequentialWritesActor: ActorRef = system.actorOf(FixedPointsActor.sequentialWritesProps([m
[36m@@ -443,25 +442,23 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
     val actors = persistentStateActors[m
 [m
     val futurePortStates: Future[([m
[31m-      Option[FeedSourceStatuses],[m
[31m-        SortedSet[ProcessingRequest],[m
[32m+[m[32m      SortedSet[ProcessingRequest],[m
         SortedSet[ProcessingRequest],[m
         SortedSet[ProcessingRequest],[m
         SortedSet[ProcessingRequest],[m
         SortedSet[ProcessingRequest],[m
       )] = {[m
       for {[m
[31m-        aclStatus <- feedService.forecastBaseFeedArrivalsActor.ask(GetFeedStatuses).mapTo[Option[FeedSourceStatuses]][m
         mergeArrivalsQueue <- actors.mergeArrivalsQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]][m
         crunchQueue <- actors.crunchQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]][m
         deskRecsQueue <- actors.deskRecsQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]][m
         deploymentQueue <- actors.deploymentQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]][m
         staffingUpdateQueue <- actors.staffingQueueActor.ask(GetState).mapTo[SortedSet[ProcessingRequest]][m
[31m-      } yield (aclStatus, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)[m
[32m+[m[32m      } yield (mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)[m
     }[m
 [m
     futurePortStates.onComplete {[m
[31m-      case Success((maybeAclStatus, mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>[m
[32m+[m[32m      case Success((mergeArrivalsQueue, crunchQueue, deskRecsQueue, deploymentQueue, staffingUpdateQueue)) =>[m
         system.log.info(s"Successfully restored initial state for App")[m
 [m
         val crunchInputs: CrunchSystem[typed.ActorRef[Feed.FeedTick]] = startCrunchSystem([m
[36m@@ -474,18 +471,23 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
         feedService.liveBaseFeedPollingActor ! Enable(crunchInputs.liveBaseArrivalsResponse)[m
         feedService.liveFeedPollingActor ! Enable(crunchInputs.liveArrivalsResponse)[m
 [m
[31m-        for {[m
[31m-          aclStatus <- maybeAclStatus[m
[31m-          lastSuccess <- aclStatus.feedStatuses.lastSuccessAt[m
[31m-        } yield {[m
[31m-          val twelveHoursAgo = SDate.now().addHours(-12).millisSinceEpoch[m
[31m-          if (lastSuccess < twelveHoursAgo) {[m
[31m-            val minutesToNextCheck = (Math.random() * 90).toInt.minutes[m
[31m-            log.info(s"Last ACL check was more than 12 hours ago. Will check in ${minutesToNextCheck.toMinutes} minutes")[m
[31m-            system.scheduler.scheduleOnce(minutesToNextCheck) {[m
[31m-              feedService.fcstBaseFeedPollingActor ! AdhocCheck[m
[32m+[m[32m        if (!airportConfig.aclDisabled) {[m
[32m+[m[32m          feedService.forecastBaseArrivalsFeedStatusActor[m
[32m+[m[32m            .ask(GetFeedStatuses).mapTo[FeedSourceStatuses][m
[32m+[m[32m            .map { aclStatus =>[m
[32m+[m[32m              for {[m
[32m+[m[32m                lastSuccess <- aclStatus.feedStatuses.lastSuccessAt[m
[32m+[m[32m              } yield {[m
[32m+[m[32m                val twelveHoursAgo = SDate.now().addHours(-12).millisSinceEpoch[m
[32m+[m[32m                if (lastSuccess < twelveHoursAgo) {[m
[32m+[m[32m                  val minutesToNextCheck = (Math.random() * 90).toInt.minutes[m
[32m+[m[32m                  log.info(s"Last ACL check was more than 12 hours ago. Will check in ${minutesToNextCheck.toMinutes} minutes")[m
[32m+[m[32m                  system.scheduler.scheduleOnce(minutesToNextCheck) {[m
[32m+[m[32m                    feedService.fcstBaseFeedPollingActor ! AdhocCheck[m
[32m+[m[32m                  }[m
[32m+[m[32m                }[m
[32m+[m[32m              }[m
             }[m
[31m-          }[m
         }[m
         val lastProcessedLiveApiMarker: Option[MillisSinceEpoch] =[m
           if (refetchApiData) None[m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/service/ProdFeedService.scala b/server/src/main/scala/uk/gov/homeoffice/drt/service/ProdFeedService.scala[m
[1mindex 0e3bad287..c768a4bec 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/service/ProdFeedService.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/service/ProdFeedService.scala[m
[36m@@ -9,6 +9,7 @@[m [mimport actors.routing.FeedArrivalsRouterActor[m
 import akka.actor.typed.scaladsl.adapter.ClassicActorSystemOps[m
 import akka.actor.{ActorRef, ActorSystem, CoordinatedShutdown, Props, Scheduler, typed}[m
 import akka.pattern.ask[m
[32m+[m[32mimport akka.stream.Materializer[m
 import akka.stream.scaladsl.{Sink, Source}[m
 import akka.util.Timeout[m
 import akka.{Done, NotUsed}[m
[36m@@ -38,7 +39,6 @@[m [mimport org.slf4j.{Logger, LoggerFactory}[m
 import play.api.Configuration[m
 import services.arrivals.MergeArrivals.FeedArrivalSet[m
 import services.{Retry, RetryDelays, StreamSupervision}[m
[31m-import uk.gov.homeoffice.drt.DataUpdates.FlightUpdates[m
 import uk.gov.homeoffice.drt.actor.TerminalDayFeedArrivalActor[m
 import uk.gov.homeoffice.drt.arrivals._[m
 import uk.gov.homeoffice.drt.feeds.FeedSourceStatuses[m
[36m@@ -55,7 +55,7 @@[m [mimport scala.concurrent.{ExecutionContext, Future}[m
 object ProdFeedService {[m
   def arrivalFeedProvidersInOrder(feedActorsWithPrimary: Seq[(Boolean, Option[FiniteDuration], ActorRef)],[m
                                  )[m
[31m-                                 (implicit timeout: Timeout, ec: ExecutionContext): Seq[DateLike => Future[FeedArrivalSet]] =[m
[32m+[m[32m                                 (implicit timeout: Timeout, ec: ExecutionContext, mat: Materializer): Seq[DateLike => Future[FeedArrivalSet]] =[m
     feedActorsWithPrimary[m
       .map {[m
         case (isPrimary, maybeFuzzyThreshold, actor) =>[m
[36m@@ -63,9 +63,10 @@[m [mobject ProdFeedService {[m
             val start = SDate(date)[m
             val end = start.addDays(1).addMinutes(-1)[m
             actor[m
[31m-              .ask(GetFlights(start.millisSinceEpoch, end.millisSinceEpoch))[m
[31m-              .mapTo[Map[UniqueArrival, Arrival]][m
[31m-              .map(f => FeedArrivalSet(isPrimary, maybeFuzzyThreshold, f))[m
[32m+[m[32m              .ask(FeedArrivalsRouterActor.GetStateForDateRange(start.toUtcDate, end.toUtcDate))[m
[32m+[m[32m              .mapTo[Source[(UtcDate, Seq[FeedArrival]), NotUsed]][m
[32m+[m[32m              .flatMap(s => s.runWith(Sink.fold(Seq[FeedArrival]())((acc, next) => acc ++ next._2)))[m
[32m+[m[32m              .map(f => FeedArrivalSet(isPrimary, maybeFuzzyThreshold, f.map(fa => (fa.unique -> fa.toArrival())))[m
           }[m
           arrivalsForDate[m
       }[m
[36m@@ -121,15 +122,15 @@[m [mtrait FeedService {[m
 [m
   val flightModelPersistence: ModelPersistence = Flight()[m
 [m
[31m-  private val liveArrivalsFeedStatusActor: ActorRef =[m
[32m+[m[32m  val liveArrivalsFeedStatusActor: ActorRef =[m
     system.actorOf(PortLiveArrivalsActor.streamingUpdatesProps(journalType), name = "live-arrivals-feed-status")[m
[31m-  private val liveBaseArrivalsFeedStatusActor: ActorRef =[m
[32m+[m[32m  val liveBaseArrivalsFeedStatusActor: ActorRef =[m
     system.actorOf(CiriumLiveArrivalsActor.streamingUpdatesProps(journalType), name = "live-base-arrivals-feed-status")[m
[31m-  private val forecastArrivalsFeedStatusActor: ActorRef =[m
[32m+[m[32m  val forecastArrivalsFeedStatusActor: ActorRef =[m
     system.actorOf(PortForecastArrivalsActor.streamingUpdatesProps(journalType), name = "forecast-arrivals-feed-status")[m
[31m-  private val forecastBaseArrivalsFeedStatusActor: ActorRef =[m
[32m+[m[32m  val forecastBaseArrivalsFeedStatusActor: ActorRef =[m
     system.actorOf(AclForecastArrivalsActor.streamingUpdatesProps(journalType), name = "forecast-base-arrivals-feed-status")[m
[31m-  private val manifestsFeedStatusActor: ActorRef =[m
[32m+[m[32m  val manifestsFeedStatusActor: ActorRef =[m
     system.actorOf(ManifestRouterActor.streamingUpdatesProps(journalType), name = "manifests-feed-status")[m
 [m
   private val feedStatusActors: Map[FeedSource, ActorRef] = Map([m
[36m@@ -290,7 +291,7 @@[m [mcase class ProdFeedService(journalType: StreamingJournalLike,[m
 [m
   private def updateForecastBaseArrivals(source: FeedSource,[m
                                          props: (Int, Int, Int, Terminal, FeedSource, Option[MillisSinceEpoch], () => MillisSinceEpoch, Int) => Props,[m
[31m-                                        ): ((Terminal, UtcDate), FlightUpdates) => Future[Boolean] =[m
[32m+[m[32m                                        ): ((Terminal, UtcDate), Seq[FeedArrival]) => Future[Boolean] =[m
     FeedArrivalsRouterActor.updateFlights([m
       requestAndTerminateActor,[m
       (d, t) => props(d.year, d.month, d.day, t, source, None, nowMillis, 250),[m
[36m@@ -298,13 +299,13 @@[m [mcase class ProdFeedService(journalType: StreamingJournalLike,[m
 [m
   override val forecastBaseFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor([m
     airportConfig.terminals,[m
[31m-    getFeedArrivalsLookup(AclFeedSource, TerminalDayFeedArrivalActor.forecast),[m
[31m-    updateForecastBaseArrivals(AclFeedSource, TerminalDayFeedArrivalActor.forecast),[m
[32m+[m[32m    getFeedArrivalsLookup(AclFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = true)),[m
[32m+[m[32m    updateForecastBaseArrivals(AclFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = true)),[m
   )), name = "forecast-base-arrivals-actor")[m
   override val forecastFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor([m
     airportConfig.terminals,[m
[31m-    getFeedArrivalsLookup(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast),[m
[31m-    updateForecastBaseArrivals(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast),[m
[32m+[m[32m    getFeedArrivalsLookup(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = false)),[m
[32m+[m[32m    updateForecastBaseArrivals(ForecastFeedSource, TerminalDayFeedArrivalActor.forecast(processRemovals = false)),[m
   )), name = "forecast-arrivals-actor")[m
   override val liveFeedArrivalsActor: ActorRef = system.actorOf(Props(new FeedArrivalsRouterActor([m
     airportConfig.terminals,[m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/testsystem/TestActorService.scala b/server/src/main/scala/uk/gov/homeoffice/drt/testsystem/TestActorService.scala[m
[1mindex 5c718979b..336b3f8ef 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/testsystem/TestActorService.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/testsystem/TestActorService.scala[m
[36m@@ -1,7 +1,9 @@[m
 package uk.gov.homeoffice.drt.testsystem[m
 [m
 import actors._[m
[32m+[m[32mimport actors.daily.RequestAndTerminateActor[m
 import akka.actor.{ActorRef, ActorSystem, Props}[m
[32m+[m[32mimport akka.util.Timeout[m
 import uk.gov.homeoffice.drt.crunchsystem.ActorsServiceLike[m
 import uk.gov.homeoffice.drt.ports.AirportConfig[m
 import uk.gov.homeoffice.drt.testsystem.TestActors._[m
[36m@@ -12,8 +14,10 @@[m [mcase class TestActorService(journalType: StreamingJournalLike,[m
                             now: () => SDateLike,[m
                             forecastMaxDays: Int,[m
                             flightLookups: FlightLookupsLike,[m
[31m-                            minuteLookups: MinuteLookupsLike)(implicit system: ActorSystem) extends ActorsServiceLike {[m
[31m-[m
[32m+[m[32m                            minuteLookups: MinuteLookupsLike,[m
[32m+[m[32m                           )[m
[32m+[m[32m                           (implicit system: ActorSystem, timeout: Timeout) extends ActorsServiceLike {[m
[32m+[m[32m  override val requestAndTerminateActor: ActorRef = system.actorOf(Props(new RequestAndTerminateActor), "request-and-terminate-actor")[m
   override val liveShiftsReadActor: ActorRef = system.actorOf(TestShiftsActor.streamingUpdatesProps([m
     journalType, airportConfig.minutesToCrunch, now), name = "shifts-read-actor")[m
   override val liveFixedPointsReadActor: ActorRef = system.actorOf(TestFixedPointsActor.streamingUpdatesProps([m
[1mdiff --git a/server/src/test/scala/actors/daily/RequestAndTerminateActorSpec.scala b/server/src/test/scala/actors/daily/RequestAndTerminateActorSpec.scala[m
[1mindex 70e929adf..b32415ca2 100644[m
[1m--- a/server/src/test/scala/actors/daily/RequestAndTerminateActorSpec.scala[m
[1m+++ b/server/src/test/scala/actors/daily/RequestAndTerminateActorSpec.scala[m
[36m@@ -1,15 +1,13 @@[m
 package actors.daily[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.Props[m
 import akka.pattern.ask[m
 import drt.shared.CrunchApi.{CrunchMinute, MinutesContainer}[m
[32m+[m[32mimport services.crunch.CrunchTestLike[m
 import uk.gov.homeoffice.drt.ports.Queues.EeaDesk[m
[31m-import uk.gov.homeoffice.drt.time.SDateLike[m
 import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}[m
[31m-import uk.gov.homeoffice.drt.time.SDate[m
[31m-import services.crunch.CrunchTestLike[m
 import uk.gov.homeoffice.drt.testsystem.TestActors.{ResetData, TestTerminalDayQueuesActor}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
 [m
 import scala.concurrent.Await[m
 import scala.concurrent.duration._[m
[36m@@ -34,7 +32,7 @@[m [mclass RequestAndTerminateActorSpec extends CrunchTestLike {[m
       val result = Await.result(requestsActor.ask(RequestAndTerminate(actor, container)), 5.seconds)[m
 [m
       "I should get a diff of updated minutes back as an acknowledgement" >> {[m
[31m-        result.isInstanceOf[UpdatedMillis][m
[32m+[m[32m        result.isInstanceOf[Set[Long]][m
       }[m
     }[m
   }[m
[1mdiff --git a/server/src/test/scala/actors/daily/TerminalDayQueuesActorSpec.scala b/server/src/test/scala/actors/daily/TerminalDayQueuesActorSpec.scala[m
[1mindex 29c7a6970..143c8b296 100644[m
[1m--- a/server/src/test/scala/actors/daily/TerminalDayQueuesActorSpec.scala[m
[1m+++ b/server/src/test/scala/actors/daily/TerminalDayQueuesActorSpec.scala[m
[36m@@ -1,16 +1,14 @@[m
 package actors.daily[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
[31m-import uk.gov.homeoffice.drt.actor.commands.Commands.GetState[m
 import akka.actor.{ActorRef, Props}[m
 import akka.pattern.ask[m
 import drt.shared.CrunchApi.{CrunchMinute, DeskRecMinute, MinutesContainer}[m
 import drt.shared.TQM[m
[31m-import uk.gov.homeoffice.drt.time.SDate[m
 import services.crunch.CrunchTestLike[m
[32m+[m[32mimport uk.gov.homeoffice.drt.actor.commands.Commands.GetState[m
 import uk.gov.homeoffice.drt.ports.Queues.{EeaDesk, Queue}[m
 import uk.gov.homeoffice.drt.ports.Terminals.{T1, Terminal}[m
[31m-import uk.gov.homeoffice.drt.time.SDateLike[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.{SDate, SDateLike}[m
 [m
 import scala.concurrent.duration._[m
 import scala.concurrent.{Await, Future}[m
[36m@@ -28,11 +26,11 @@[m [mclass TerminalDayQueuesActorSpec extends CrunchTestLike {[m
 [m
     "When I send it a DeskRecMinute" >> {[m
       val drm = DeskRecMinute(terminal, queue, date.millisSinceEpoch, 1, 2, 3, 4, None)[m
[31m-      val eventualContainer = queuesActor.ask(MinutesContainer(Seq(drm))).mapTo[UpdatedMillis][m
[32m+[m[32m      val eventualContainer = queuesActor.ask(MinutesContainer(Seq(drm))).mapTo[Set[Long]][m
 [m
       "I should get back the merged CrunchMinute" >> {[m
         val result = Await.result(eventualContainer, 1.second)[m
[31m-        result === UpdatedMillis(Set(drm.minute))[m
[32m+[m[32m        result === Set(drm.minute)[m
       }[m
     }[m
   }[m
[1mdiff --git a/server/src/test/scala/actors/queues/CrunchQueueSpec.scala b/server/src/test/scala/actors/queues/CrunchQueueSpec.scala[m
[1mindex c3be15cbc..a09b6a96f 100644[m
[1m--- a/server/src/test/scala/actors/queues/CrunchQueueSpec.scala[m
[1m+++ b/server/src/test/scala/actors/queues/CrunchQueueSpec.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.queues[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.persistent.SortedActorRefSource[m
 import akka.actor.ActorRef[m
 import akka.stream.javadsl.RunnableGraph[m
[36m@@ -53,7 +52,7 @@[m [mclass CrunchQueueSpec extends CrunchTestLike with ImplicitSender {[m
       "Then I should see a CrunchRequest for the day before in UTC (midnight BST is 23:00 the day before in UTC)" >> {[m
         val daysSourceProbe: TestProbe = TestProbe()[m
         val actor = startQueueActor(daysSourceProbe, zeroOffset, SortedSet())[m
[31m-        actor ! UpdatedMillis(Set(day))[m
[32m+[m[32m        actor ! Set(day)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), zeroOffset, durationMinutes))[m
         success[m
       }[m
[36m@@ -63,7 +62,7 @@[m [mclass CrunchQueueSpec extends CrunchTestLike with ImplicitSender {[m
       "Then I should see a CrunchRequest for the day before as the offset pushes that day to cover the following midnight" >> {[m
         val daysSourceProbe: TestProbe = TestProbe()[m
         val actor = startQueueActor(daysSourceProbe, twoHourOffset, SortedSet())[m
[31m-        actor ! UpdatedMillis(Set(day))[m
[32m+[m[32m        actor ! Set(day)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), twoHourOffset, durationMinutes))[m
         success[m
       }[m
[36m@@ -76,7 +75,7 @@[m [mclass CrunchQueueSpec extends CrunchTestLike with ImplicitSender {[m
         val today = myNow().millisSinceEpoch[m
         val tomorrow = myNow().addDays(1).millisSinceEpoch[m
         watch(actor)[m
[31m-        actor ! UpdatedMillis(Set(today, tomorrow))[m
[32m+[m[32m        actor ! Set(today, tomorrow)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), twoHourOffset, durationMinutes))[m
         Thread.sleep(200)[m
         startQueueActor(daysSourceProbe, defaultAirportConfig.crunchOffsetMinutes, SortedSet())[m
[1mdiff --git a/server/src/test/scala/actors/queues/DeploymentQueueSpec.scala b/server/src/test/scala/actors/queues/DeploymentQueueSpec.scala[m
[1mindex 36ab372d1..75e5ebb07 100644[m
[1m--- a/server/src/test/scala/actors/queues/DeploymentQueueSpec.scala[m
[1m+++ b/server/src/test/scala/actors/queues/DeploymentQueueSpec.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.queues[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.persistent.SortedActorRefSource[m
 import akka.actor.ActorRef[m
 import akka.stream.javadsl.RunnableGraph[m
[36m@@ -43,7 +42,7 @@[m [mclass DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {[m
       "Then I should see a CrunchRequest for the same day (midnight BST is 23:00 the day before in UTC, but LocalDate should stay the same)" >> {[m
         val daysSourceProbe: TestProbe = TestProbe()[m
         val actor = startQueueActor(daysSourceProbe, zeroOffset)[m
[31m-        actor ! UpdatedMillis(Set(day))[m
[32m+[m[32m        actor ! Set(day)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 6), zeroOffset, durationMinutes))[m
         success[m
       }[m
[36m@@ -53,7 +52,7 @@[m [mclass DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {[m
       "Then I should see a CrunchRequest for the day before as the offset pushes that day to cover the following midnight" >> {[m
         val daysSourceProbe: TestProbe = TestProbe()[m
         val actor = startQueueActor(daysSourceProbe, twoHourOffset)[m
[31m-        actor ! UpdatedMillis(Set(day))[m
[32m+[m[32m        actor ! Set(day)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), twoHourOffset, durationMinutes))[m
         success[m
       }[m
[36m@@ -66,7 +65,7 @@[m [mclass DeploymentQueueSpec extends CrunchTestLike with ImplicitSender {[m
         val today = myNow().millisSinceEpoch[m
         val tomorrow = myNow().addDays(1).millisSinceEpoch[m
         watch(actor)[m
[31m-        actor ! UpdatedMillis(Set(today, tomorrow))[m
[32m+[m[32m        actor ! Set(today, tomorrow)[m
         daysSourceProbe.expectMsg(CrunchRequest(LocalDate(2020, 5, 5), 120, durationMinutes))[m
         Thread.sleep(200)[m
         startQueueActor(daysSourceProbe, defaultAirportConfig.crunchOffsetMinutes)[m
[1mdiff --git a/server/src/test/scala/actors/routing/SequentialAccessActorSpec.scala b/server/src/test/scala/actors/routing/SequentialAccessActorSpec.scala[m
[1mindex 1d41e54b2..8aba187c3 100644[m
[1m--- a/server/src/test/scala/actors/routing/SequentialAccessActorSpec.scala[m
[1m+++ b/server/src/test/scala/actors/routing/SequentialAccessActorSpec.scala[m
[36m@@ -1,6 +1,5 @@[m
 package actors.routing[m
 [m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.{ActorRef, Props}[m
 import akka.pattern.{StatusReply, ask}[m
 import akka.testkit.TestProbe[m
[36m@@ -127,8 +126,8 @@[m [mclass SequentialAccessActorSpec extends CrunchTestLike {[m
       PassengersMinute(Terminal("T2"), EeaDesk, SDate("2022-09-04T08:00").millisSinceEpoch, Seq(10, 11, 12), None),[m
     ))), 1.second) === StatusReply.Ack[m
 [m
[31m-    probeA.expectMsg(UpdatedMillis(Set(0, 1)))[m
[31m-    probeB.expectMsg(UpdatedMillis(Set(0, 1)))[m
[32m+[m[32m    probeA.expectMsg(Set(0, 1))[m
[32m+[m[32m    probeB.expectMsg(Set(0, 1))[m
 [m
     ackReceived[m
   }[m
[36m@@ -173,8 +172,8 @@[m [mclass SequentialAccessActorSpec extends CrunchTestLike {[m
 [m
     val ack2Received = Await.result(actor.ask(MinutesContainer(Seq(deskRecMinute))), 1.second) === StatusReply.Ack[m
 [m
[31m-    probeA.expectMsg(UpdatedMillis(Set(0, 1)))[m
[31m-    probeB.expectMsg(UpdatedMillis(Set(0, 1)))[m
[32m+[m[32m    probeA.expectMsg(Set(0, 1))[m
[32m+[m[32m    probeB.expectMsg(Set(0, 1))[m
 [m
     ack1Received && ack2Received[m
   }[m
[1mdiff --git a/server/src/test/scala/actors/routing/minutes/ManifestsRouterActorSpec.scala b/server/src/test/scala/actors/routing/minutes/ManifestsRouterActorSpec.scala[m
[1mindex b61ba5878..72b2abc89 100644[m
[1m--- a/server/src/test/scala/actors/routing/minutes/ManifestsRouterActorSpec.scala[m
[1m+++ b/server/src/test/scala/actors/routing/minutes/ManifestsRouterActorSpec.scala[m
[36m@@ -2,7 +2,6 @@[m [mpackage actors.routing.minutes[m
 [m
 import actors.ManifestLookupsLike[m
 import actors.PartitionedPortStateActor.{GetStateForDateRange, PointInTimeQuery}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.persistent.staffing.GetFeedStatuses[m
 import actors.persistent.{ApiFeedState, ManifestRouterActor}[m
 import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}[m
[36m@@ -42,7 +41,7 @@[m [mclass ManifestsRouterActorSpec extends CrunchTestLike {[m
     }[m
   }[m
 [m
[31m-  val noopUpdates: ManifestsUpdate = (_: UtcDate, _: VoyageManifests) => Future(UpdatedMillis.empty)[m
[32m+[m[32m  val noopUpdates: ManifestsUpdate = (_: UtcDate, _: VoyageManifests) => Future(Set.empty[Long])[m
 [m
   private val probe = TestProbe("")[m
 [m
[1mdiff --git a/server/src/test/scala/actors/routing/minutes/MockManifestsLookup.scala b/server/src/test/scala/actors/routing/minutes/MockManifestsLookup.scala[m
[1mindex 6f29a5687..ea1eb0476 100644[m
[1m--- a/server/src/test/scala/actors/routing/minutes/MockManifestsLookup.scala[m
[1m+++ b/server/src/test/scala/actors/routing/minutes/MockManifestsLookup.scala[m
[36m@@ -1,11 +1,10 @@[m
 package actors.routing.minutes[m
 [m
 import actors.routing.minutes.MinutesActorLike.{ManifestLookup, ManifestsUpdate}[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import akka.actor.ActorRef[m
 import drt.shared.CrunchApi.MillisSinceEpoch[m
[31m-import uk.gov.homeoffice.drt.time.UtcDate[m
 import passengersplits.parsing.VoyageManifestParser.{VoyageManifest, VoyageManifests}[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.UtcDate[m
 [m
 import scala.concurrent.{ExecutionContextExecutor, Future}[m
 [m
[36m@@ -26,6 +25,6 @@[m [mcase class MockManifestsLookup(probe: ActorRef) {[m
 [m
   def update: ManifestsUpdate = (date: UtcDate, manifests: VoyageManifests) => {[m
     probe ! (date, manifests)[m
[31m-    Future(UpdatedMillis(manifests.manifests.map(_.scheduled.millisSinceEpoch).toSet))[m
[32m+[m[32m    Future(manifests.manifests.map(_.scheduled.millisSinceEpoch).toSet)[m
   }[m
 }[m
[1mdiff --git a/server/src/test/scala/services/crunch/deskrecs/RunnableDeskRecsSpec.scala b/server/src/test/scala/services/crunch/deskrecs/RunnableDeskRecsSpec.scala[m
[1mindex abcba7378..16e02e732 100644[m
[1m--- a/server/src/test/scala/services/crunch/deskrecs/RunnableDeskRecsSpec.scala[m
[1m+++ b/server/src/test/scala/services/crunch/deskrecs/RunnableDeskRecsSpec.scala[m
[36m@@ -1,7 +1,6 @@[m
 package services.crunch.deskrecs[m
 [m
 import actors.PartitionedPortStateActor.GetFlights[m
[31m-import actors.persistent.QueueLikeActor.UpdatedMillis[m
 import actors.persistent.SortedActorRefSource[m
 import akka.NotUsed[m
 import akka.actor.{Actor, ActorRef, Props}[m
[36m@@ -86,9 +85,9 @@[m [mclass MockPortStateActor(probe: TestProbe, responseDelayMillis: Long) extends Ac[m
 [m
 class MockSplitsSinkActor() extends Actor {[m
   override def receive: Receive = {[m
[31m-    case _: SplitsForArrivals => sender() ! UpdatedMillis.empty[m
[31m-    case _: PaxForArrivals => sender() ! UpdatedMillis.empty[m
[31m-    case _: ArrivalsDiff => sender() ! UpdatedMillis.empty[m
[32m+[m[32m    case _: SplitsForArrivals => sender() ! Set.empty[Long][m
[32m+[m[32m    case _: PaxForArrivals => sender() ! Set.empty[Long][m
[32m+[m[32m    case _: ArrivalsDiff => sender() ! Set.empty[Long][m
   }[m
 }[m
 [m

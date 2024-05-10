[1mdiff --git a/client/src/main/scala/drt/client/SPAMain.scala b/client/src/main/scala/drt/client/SPAMain.scala[m
[1mindex 2a5c8099d..8f60c40c7 100644[m
[1m--- a/client/src/main/scala/drt/client/SPAMain.scala[m
[1m+++ b/client/src/main/scala/drt/client/SPAMain.scala[m
[36m@@ -6,7 +6,7 @@[m [mimport drt.client.components.TerminalDesksAndQueues.{ChartsView, Deployments, De[m
 import drt.client.components.styles._[m
 import drt.client.components.{[m
   ContactPage, ForecastFileUploadPage, GlobalStyles, Layout, PortConfigPage,[m
[31m-  PortDashboardPage, StatusPage, TerminalComponent, TerminalPlanningComponent, TrainingHubComponent, UserDashboardPage[m
[32m+[m[32m  PortDashboardPage, FeedsStatusPage, TerminalComponent, TerminalPlanningComponent, TrainingHubComponent, UserDashboardPage[m
 }[m
 import drt.client.logger._[m
 import drt.client.services.JSDateConversions.SDate[m
[36m@@ -237,7 +237,7 @@[m [mobject SPAMain {[m
   def statusRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {[m
     import dsl._[m
 [m
[31m-    staticRoute("#status", StatusLoc) ~> renderR((_: RouterCtl[Loc]) => StatusPage())[m
[32m+[m[32m    staticRoute("#status", StatusLoc) ~> renderR((_: RouterCtl[Loc]) => FeedsStatusPage())[m
   }[m
 [m
   def contactRoute(dsl: RouterConfigDsl[Loc, Unit]): dsl.Rule = {[m
[1mdiff --git a/client/src/main/scala/drt/client/actions/Actions.scala b/client/src/main/scala/drt/client/actions/Actions.scala[m
[1mindex 9166158ee..84c2805d7 100644[m
[1m--- a/client/src/main/scala/drt/client/actions/Actions.scala[m
[1m+++ b/client/src/main/scala/drt/client/actions/Actions.scala[m
[36m@@ -203,6 +203,10 @@[m [mobject Actions {[m
 [m
   object RequestRecalculateArrivals extends Action[m
 [m
[32m+[m[32m  object RequestMissingHistoricSplits extends Action[m
[32m+[m
[32m+[m[32m  object RequestMissingPaxNos extends Action[m
[32m+[m
   case class GetForecastAccuracy(localDate: LocalDate) extends Action[m
 [m
   case object ClearForecastAccuracy extends Action[m
[1mdiff --git a/client/src/main/scala/drt/client/components/FeedsStatusPage.scala b/client/src/main/scala/drt/client/components/FeedsStatusPage.scala[m
[1mindex 14c627593..aee31e715 100644[m
[1m--- a/client/src/main/scala/drt/client/components/FeedsStatusPage.scala[m
[1m+++ b/client/src/main/scala/drt/client/components/FeedsStatusPage.scala[m
[36m@@ -1,8 +1,9 @@[m
 package drt.client.components[m
 [m
 import diode.data.Pot[m
[31m-import drt.client.actions.Actions.{RequestForecastRecrunch, RequestRecalculateArrivals}[m
[32m+[m[32mimport drt.client.actions.Actions.{RequestForecastRecrunch, RequestMissingHistoricSplits, RequestMissingPaxNos, RequestRecalculateArrivals}[m
 import drt.client.components.ToolTips._[m
[32m+[m[32mimport drt.client.components.styles.DrtTheme[m
 import drt.client.logger.{Logger, LoggerFactory}[m
 import drt.client.modules.GoogleEventTracker[m
 import drt.client.services.JSDateConversions.SDate[m
[36m@@ -11,6 +12,7 @@[m [mimport drt.client.services.handlers.CheckFeed[m
 import drt.shared.CrunchApi.MillisSinceEpoch[m
 import io.kinoplan.scalajs.react.material.ui.core.MuiButton._[m
 import io.kinoplan.scalajs.react.material.ui.core._[m
[32m+[m[32mimport io.kinoplan.scalajs.react.material.ui.core.system.ThemeProvider[m
 import io.kinoplan.scalajs.react.material.ui.icons.MuiIcons[m
 import io.kinoplan.scalajs.react.material.ui.icons.MuiIconsModule.RefreshOutlined[m
 import japgolly.scalajs.react.component.Scala.Component[m
[36m@@ -22,7 +24,7 @@[m [mimport uk.gov.homeoffice.drt.feeds.{FeedSourceStatuses, FeedStatusFailure, FeedS[m
 import uk.gov.homeoffice.drt.ports._[m
 [m
 [m
[31m-object StatusPage {[m
[32m+[m[32mobject FeedsStatusPage {[m
 [m
   val log: Logger = LoggerFactory.getLogger(getClass.getName)[m
 [m
[36m@@ -51,6 +53,14 @@[m [mobject StatusPage {[m
         SPACircuit.dispatch(RequestRecalculateArrivals)[m
       }[m
 [m
[32m+[m[32m      def requestMissingHistoricSplitsLookup(): Callback = Callback {[m
[32m+[m[32m        SPACircuit.dispatch(RequestMissingHistoricSplits)[m
[32m+[m[32m      }[m
[32m+[m
[32m+[m[32m      def requestMissingPaxNos(): Callback = Callback {[m
[32m+[m[32m        SPACircuit.dispatch(RequestMissingPaxNos)[m
[32m+[m[32m      }[m
[32m+[m
 [m
       modelRcp { proxy =>[m
 [m
[36m@@ -121,9 +131,23 @@[m [mobject StatusPage {[m
             <.br(),[m
             <.h2("Crunch"),[m
             <.div(^.className := "crunch-actions-container",[m
[31m-              MuiButton(variant = "outlined", size = "medium", color = Color.primary)(<.div("Re-crunch forecast", ^.onClick --> requestForecastRecrunch())),[m
[31m-              MuiButton(variant = "outlined", size = "medium", color = Color.primary)(<.div("Refresh splits", ^.onClick --> requestSplitsRefresh())),[m
[31m-              MuiButton(variant = "outlined", size = "medium", color = Color.primary)(<.div("Recalculate arrivals", ^.onClick --> requestRecalculateArrivals())),[m
[32m+[m[32m              ThemeProvider(DrtTheme.theme)([m
[32m+[m[32m                MuiButton(variant = "outlined", color = Color.primary)([m
[32m+[m[32m                  <.div("Re-crunch forecast", ^.onClick --> requestForecastRecrunch())[m
[32m+[m[32m                ),[m
[32m+[m[32m                MuiButton(variant = "outlined", color = Color.primary)([m
[32m+[m[32m                  <.div("Refresh splits", ^.onClick --> requestSplitsRefresh())[m
[32m+[m[32m                ),[m
[32m+[m[32m                MuiButton(variant = "outlined", color = Color.primary)([m
[32m+[m[32m                  <.div("Recalculate arrivals", ^.onClick --> requestRecalculateArrivals())[m
[32m+[m[32m                ),[m
[32m+[m[32m                MuiButton(variant = "outlined", color = Color.primary)([m
[32m+[m[32m                  <.div("Lookup missing historic splits", ^.onClick --> requestMissingHistoricSplitsLookup())[m
[32m+[m[32m                ),[m
[32m+[m[32m                MuiButton(variant = "outlined", color = Color.primary)([m
[32m+[m[32m                  <.div("Lookup missing forecast pax nos", ^.onClick --> requestMissingPaxNos())[m
[32m+[m[32m                ),[m
[32m+[m[32m              )[m
             )[m
           ) else EmptyVdom[m
         }[m
[1mdiff --git a/client/src/main/scala/drt/client/services/handlers/AppControlHandler.scala b/client/src/main/scala/drt/client/services/handlers/AppControlHandler.scala[m
[1mindex 8c42233e9..fbf03568d 100644[m
[1m--- a/client/src/main/scala/drt/client/services/handlers/AppControlHandler.scala[m
[1m+++ b/client/src/main/scala/drt/client/services/handlers/AppControlHandler.scala[m
[36m@@ -1,7 +1,7 @@[m
 package drt.client.services.handlers[m
 [m
 import diode.{ActionResult, ModelRW}[m
[31m-import drt.client.actions.Actions.{RequestForecastRecrunch, RequestRecalculateArrivals}[m
[32m+[m[32mimport drt.client.actions.Actions.{RequestForecastRecrunch, RequestMissingHistoricSplits, RequestMissingPaxNos, RequestRecalculateArrivals}[m
 import drt.client.services.{DrtApi, RootModel}[m
 import upickle.default.write[m
 [m
[36m@@ -13,8 +13,18 @@[m [mclass AppControlHandler[M](modelRW: ModelRW[M, RootModel]) extends LoggingAction[m
     case RequestForecastRecrunch(recalculateSplits) =>[m
       DrtApi.post("control/crunch/recalculate", write(recalculateSplits))[m
       noChange[m
[32m+[m
     case RequestRecalculateArrivals =>[m
       DrtApi.post("control/arrivals/recalculate", write(true))[m
       noChange[m
[32m+[m
[32m+[m[32m    case RequestMissingHistoricSplits =>[m
[32m+[m[32m      DrtApi.post("control/historic-splits/lookup-missing", write(true))[m
[32m+[m[32m      noChange[m
[32m+[m
[32m+[m[32m    case RequestMissingPaxNos =>[m
[32m+[m[32m      DrtApi.post("control/pax-nos/lookup-missing", write(true))[m
[32m+[m[32m      noChange[m
[32m+[m
   }[m
 }[m
[1mdiff --git a/server/src/main/resources/routes b/server/src/main/resources/routes[m
[1mindex 1c02ddfe1..1809324ba 100644[m
[1m--- a/server/src/main/resources/routes[m
[1m+++ b/server/src/main/resources/routes[m
[36m@@ -34,7 +34,12 @@[m [mGET           /crunch[m
 + nocsrf[m
 POST          /control/crunch/recalculate                                             controllers.application.PortStateController.reCrunchFullForecast[m
 + nocsrf[m
[32m+[m[32mPOST          /control/historic-splits/lookup-missing                                 controllers.application.PortStateController.lookupMissingHistoricSplits[m
[32m+[m[32m+ nocsrf[m
[32m+[m[32mPOST          /control/pax-nos/lookup-missing                                         controllers.application.PortStateController.lookupMissingPaxNos[m
[32m+[m[32m+ nocsrf[m
 POST          /control/crunch/recalculate/:startLocalDate/:endLocalDate               controllers.application.PortStateController.reCrunch(startLocalDate: String, endLocalDate: String)[m
[32m+[m
 POST          /control/arrivals/recalculate                                           controllers.application.PortStateController.reCalculateArrivals[m
 GET           /crunch-snapshot/:pit                                                   controllers.application.PortStateController.getCrunchSnapshot(pit: Long)[m
 [m
[1mdiff --git a/server/src/main/scala/actors/CrunchManagerActor.scala b/server/src/main/scala/actors/CrunchManagerActor.scala[m
[1mindex 0e6c43f38..1356d57c1 100644[m
[1m--- a/server/src/main/scala/actors/CrunchManagerActor.scala[m
[1m+++ b/server/src/main/scala/actors/CrunchManagerActor.scala[m
[36m@@ -1,13 +1,25 @@[m
 package actors[m
 [m
[31m-import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber, RecalculateArrivals, Recrunch}[m
[32m+[m[32mimport actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddQueueHistoricPaxLookupSubscriber, AddQueueHistoricSplitsLookupSubscriber, AddRecalculateArrivalsSubscriber, LookupHistoricPaxNos, LookupHistoricSplits, RecalculateArrivals, Recrunch}[m
[32m+[m[32mimport akka.Done[m
 import akka.actor.{Actor, ActorRef}[m
[32m+[m[32mimport akka.stream.Materializer[m
[32m+[m[32mimport akka.stream.scaladsl.{Sink, Source}[m
[32m+[m[32mimport org.slf4j.LoggerFactory[m
[32m+[m[32mimport uk.gov.homeoffice.drt.arrivals.UniqueArrival[m
[32m+[m[32mimport uk.gov.homeoffice.drt.time.{SDate, UtcDate}[m
[32m+[m
[32m+[m[32mimport scala.concurrent.{ExecutionContext, Future}[m
 [m
 object CrunchManagerActor {[m
   case class AddQueueCrunchSubscriber(subscriber: ActorRef)[m
 [m
   case class AddRecalculateArrivalsSubscriber(subscriber: ActorRef)[m
 [m
[32m+[m[32m  case class AddQueueHistoricSplitsLookupSubscriber(subscriber: ActorRef)[m
[32m+[m
[32m+[m[32m  case class AddQueueHistoricPaxLookupSubscriber(subscriber: ActorRef)[m
[32m+[m
   trait ReProcessDates {[m
     val updatedMillis: Set[Long][m
   }[m
[36m@@ -15,11 +27,22 @@[m [mobject CrunchManagerActor {[m
   case class RecalculateArrivals(updatedMillis: Set[Long]) extends ReProcessDates[m
 [m
   case class Recrunch(updatedMillis: Set[Long]) extends ReProcessDates[m
[32m+[m
[32m+[m[32m  case class LookupHistoricSplits(updatedMillis: Set[Long]) extends ReProcessDates[m
[32m+[m
[32m+[m[32m  case class LookupHistoricPaxNos(updatedMillis: Set[Long]) extends ReProcessDates[m
 }[m
 [m
[31m-class CrunchManagerActor extends Actor {[m
[32m+[m[32mclass CrunchManagerActor(historicManifestArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],[m
[32m+[m[32m                         historicPaxArrivalKeys: UtcDate => Future[Iterable[UniqueArrival]],[m
[32m+[m[32m                        )[m
[32m+[m[32m                        (implicit ec: ExecutionContext, mat: Materializer) extends Actor {[m
[32m+[m[32m  private val log = LoggerFactory.getLogger(getClass)[m
[32m+[m
   private var maybeQueueCrunchSubscriber: Option[ActorRef] = None[m
   private var maybeRecalculateArrivalsSubscriber: Option[ActorRef] = None[m
[32m+[m[32m  private var maybeQueueHistoricSplitsLookupSubscriber: Option[ActorRef] = None[m
[32m+[m[32m  private var maybeQueueHistoricPaxLookupSubscriber: Option[ActorRef] = None[m
 [m
   override def receive: Receive = {[m
     case AddQueueCrunchSubscriber(subscriber) =>[m
[36m@@ -28,10 +51,38 @@[m [mclass CrunchManagerActor extends Actor {[m
     case AddRecalculateArrivalsSubscriber(subscriber) =>[m
       maybeRecalculateArrivalsSubscriber = Option(subscriber)[m
 [m
[32m+[m[32m    case AddQueueHistoricSplitsLookupSubscriber(subscriber) =>[m
[32m+[m[32m      maybeQueueHistoricSplitsLookupSubscriber = Option(subscriber)[m
[32m+[m
[32m+[m[32m    case AddQueueHistoricPaxLookupSubscriber(subscriber) =>[m
[32m+[m[32m      maybeQueueHistoricPaxLookupSubscriber = Option(subscriber)[m
[32m+[m
     case Recrunch(um) =>[m
       maybeQueueCrunchSubscriber.foreach(_ ! um)[m
 [m
     case RecalculateArrivals(um) =>[m
       maybeRecalculateArrivalsSubscriber.foreach(_ ! um)[m
[32m+[m
[32m+[m[32m    case LookupHistoricSplits(um) =>[m
[32m+[m[32m      queueLookups(um, maybeQueueHistoricSplitsLookupSubscriber, historicManifestArrivalKeys, "historic splits")[m
[32m+[m
[32m+[m[32m    case LookupHistoricPaxNos(um) =>[m
[32m+[m[32m      queueLookups(um, maybeQueueHistoricPaxLookupSubscriber, historicPaxArrivalKeys, "historic pax nos")[m
   }[m
[32m+[m
[32m+[m[32m  private def queueLookups(millis: Set[Long], subscriber: Option[ActorRef], lookup: UtcDate => Future[Iterable[UniqueArrival]], label: String)[m
[32m+[m[32m                          : Unit =[m
[32m+[m[32m    Source(millis[m
[32m+[m[32m      .map(SDate(_).toUtcDate).toList[m
[32m+[m[32m      .sorted)[m
[32m+[m[32m      .mapAsync(1) { date =>[m
[32m+[m[32m        lookup(date)[m
[32m+[m[32m          .map { keys =>[m
[32m+[m[32m            if (keys.nonEmpty) {[m
[32m+[m[32m              log.info(s"Looking up ${keys.size} $label for ${date.toISOString}")[m
[32m+[m[32m              subscriber.foreach(_ ! keys)[m
[32m+[m[32m            }[m
[32m+[m[32m          }[m
[32m+[m[32m      }[m
[32m+[m[32m      .runWith(Sink.ignore)[m
 }[m
[1mdiff --git a/server/src/main/scala/actors/daily/TerminalDayFlightActor.scala b/server/src/main/scala/actors/daily/TerminalDayFlightActor.scala[m
[1mindex 67276ae54..a3d3d3250 100644[m
[1m--- a/server/src/main/scala/actors/daily/TerminalDayFlightActor.scala[m
[1m+++ b/server/src/main/scala/actors/daily/TerminalDayFlightActor.scala[m
[36m@@ -20,7 +20,6 @@[m [mimport uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion[m
 import uk.gov.homeoffice.drt.protobuf.serialisation.FlightMessageConversion._[m
 import uk.gov.homeoffice.drt.time.{SDate, SDateLike, UtcDate}[m
 [m
[31m-import scala.concurrent.ExecutionContext[m
 import scala.concurrent.duration.FiniteDuration[m
 [m
 object TerminalDayFlightActor {[m
[36m@@ -54,8 +53,7 @@[m [mobject TerminalDayFlightActor {[m
                        cutOff: Option[FiniteDuration],[m
                        paxFeedSourceOrder: List[FeedSource],[m
                        terminalSplits: Option[Splits],[m
[31m-                      )[m
[31m-                      (implicit ec: ExecutionContext): Props =[m
[32m+[m[32m                      ): Props =[m
     Props(new TerminalDayFlightActor([m
       date.year,[m
       date.month,[m
[36m@@ -174,7 +172,7 @@[m [mclass TerminalDayFlightActor(year: Int,[m
   private def requestMissingHistoricSplits(): Unit =[m
     maybeRequestHistoricSplitsActor.foreach { requestActor =>[m
       val missingHistoricSplits = state.flights.values.collect {[m
[31m-        case fws if !fws.splits.exists(_.source == Historical) => fws.unique[m
[32m+[m[32m        case fws if !fws.apiFlight.Origin.isDomesticOrCta && !fws.splits.exists(_.source == Historical) => fws.unique[m
       }[m
       requestActor ! missingHistoricSplits[m
     }[m
[36m@@ -182,7 +180,7 @@[m [mclass TerminalDayFlightActor(year: Int,[m
   private def requestMissingPax(): Unit = {[m
     maybeRequestHistoricPaxActor.foreach { requestActor =>[m
       val missingPaxSource = state.flights.values.collect {[m
[31m-        case fws if fws.apiFlight.hasNoPaxSource => fws.unique[m
[32m+[m[32m        case fws if fws.apiFlight.Origin.isDomesticOrCta && fws.apiFlight.hasNoPaxSource => fws.unique[m
       }[m
       requestActor ! missingPaxSource[m
     }[m
[1mdiff --git a/server/src/main/scala/controllers/application/PortStateController.scala b/server/src/main/scala/controllers/application/PortStateController.scala[m
[1mindex 71fe049b1..8a1209d91 100644[m
[1m--- a/server/src/main/scala/controllers/application/PortStateController.scala[m
[1m+++ b/server/src/main/scala/controllers/application/PortStateController.scala[m
[36m@@ -1,6 +1,6 @@[m
 package controllers.application[m
 [m
[31m-import actors.CrunchManagerActor.{RecalculateArrivals, Recrunch}[m
[32m+[m[32mimport actors.CrunchManagerActor.{LookupHistoricPaxNos, LookupHistoricSplits, RecalculateArrivals, Recrunch}[m
 import actors.DateRange[m
 import actors.PartitionedPortStateActor.{GetStateForDateRange, GetStateForTerminalDateRange, GetUpdatesSince, PointInTimeQuery}[m
 import akka.pattern.ask[m
[36m@@ -150,6 +150,20 @@[m [mclass PortStateController @Inject()(cc: ControllerComponents, ctrl: DrtSystemInt[m
     }[m
   }[m
 [m
[32m+[m[32m  def lookupMissingHistoricSplits: Action[AnyContent] = authByRole(SuperAdmin) {[m
[32m+[m[32m    Action.async {[m
[32m+[m[32m      queueDaysToReProcess(ctrl.applicationService.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now, m => LookupHistoricSplits(m))[m
[32m+[m[32m      Future.successful(Ok("Re-crunching without updating splits"))[m
[32m+[m[32m    }[m
[32m+[m[32m  }[m
[32m+[m
[32m+[m[32m  def lookupMissingPaxNos: Action[AnyContent] = authByRole(SuperAdmin) {[m
[32m+[m[32m    Action.async {[m
[32m+[m[32m      queueDaysToReProcess(ctrl.applicationService.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now, m => LookupHistoricPaxNos(m))[m
[32m+[m[32m      Future.successful(Ok("Re-crunching without updating splits"))[m
[32m+[m[32m    }[m
[32m+[m[32m  }[m
[32m+[m
   def reCalculateArrivals: Action[AnyContent] = authByRole(SuperAdmin) {[m
     Action.async { _ =>[m
       queueDaysToReProcess(ctrl.applicationService.crunchManagerActor, airportConfig.crunchOffsetMinutes, ctrl.params.forecastMaxDays, ctrl.now, m => RecalculateArrivals(m))[m
[1mdiff --git a/server/src/main/scala/manifests/ManifestLookup.scala b/server/src/main/scala/manifests/ManifestLookup.scala[m
[1mindex edcf4dab3..3f8da4014 100644[m
[1m--- a/server/src/main/scala/manifests/ManifestLookup.scala[m
[1m+++ b/server/src/main/scala/manifests/ManifestLookup.scala[m
[36m@@ -85,7 +85,7 @@[m [mcase class ManifestLookup(tables: Tables)[m
                                      queries: List[(String, QueryFunction)])[m
                                     (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[BestAvailableManifest])] = {[m
     val startTime = SDate.now()[m
[31m-    findFlights(uniqueArrivalKey, queries)[m
[32m+[m[32m    findFlights(uniqueArrivalKey, queries, 1)[m
       .flatMap { flightKeys =>[m
         manifestsForScheduled(flightKeys)[m
           .map(profiles => (uniqueArrivalKey, maybeManifestFromProfiles(uniqueArrivalKey, profiles)))[m
[36m@@ -102,7 +102,7 @@[m [mcase class ManifestLookup(tables: Tables)[m
                                                 queries: List[(String, QueryFunction)])[m
                                                (implicit mat: Materializer): Future[(UniqueArrivalKey, Option[ManifestPaxCount])] = {[m
     val startTime = SDate.now()[m
[31m-    findFlights(uniqueArrivalKey, queries).flatMap { flightKeys =>[m
[32m+[m[32m    findFlights(uniqueArrivalKey, queries, 1).flatMap { flightKeys =>[m
       manifestPaxForScheduled(flightKeys)[m
         .map(maybePaxCount => (uniqueArrivalKey, maybePaxCount.map { case (totalPax, transPax) =>[m
           ManifestPaxCount(SplitSources.Historical, uniqueArrivalKey, totalPax, transPax)[m
[36m@@ -116,18 +116,27 @@[m [mcase class ManifestLookup(tables: Tables)[m
   }[m
 [m
   private def findFlights(uniqueArrivalKey: UniqueArrivalKey,[m
[31m-                          queries: List[(String, QueryFunction)])[m
[31m-                         (implicit mat: Materializer): Future[Vector[(String, String, String, Timestamp)]] = queries.zipWithIndex match {[m
[32m+[m[32m                          queries: List[(String, QueryFunction)],[m
[32m+[m[32m                          queryNumber: Int,[m
[32m+[m[32m                         )[m
[32m+[m[32m                         (implicit mat: Materializer): Future[Vector[(String, String, String, Timestamp)]] = queries match {[m
     case Nil => Future(Vector.empty)[m
[31m-    case ((_, nextQuery), queryNumber) :: tail =>[m
[32m+[m[32m    case (_, nextQuery) :: tail =>[m
       val startTime = SDate.now()[m
       tables[m
         .run(nextQuery(uniqueArrivalKey))[m
[32m+[m[32m        .recover {[m
[32m+[m[32m          case t =>[m
[32m+[m[32m            log.error(s"Error looking up manifest for ${uniqueArrivalKey.toString}", t)[m
[32m+[m[32m            Vector.empty[m
[32m+[m[32m        }[m
         .flatMap {[m
           case flightsFound if flightsFound.nonEmpty =>[m
[32m+[m[32m            println(s"\nFound ${flightsFound.size} historic manifests for $uniqueArrivalKey query $queryNumber")[m
             Future(flightsFound)[m
           case _ =>[m
[31m-            findFlights(uniqueArrivalKey, tail.map(_._1))[m
[32m+[m[32m            println(s"\nNo results for $uniqueArrivalKey with query ${3 - tail.size}. ${tail.size} queries left.")[m
[32m+[m[32m            findFlights(uniqueArrivalKey, tail, queryNumber + 1)[m
         }.map { res =>[m
           val timeTaken = SDate.now().millisSinceEpoch - startTime.millisSinceEpoch[m
           if (timeTaken > 1000)[m
[36m@@ -181,27 +190,27 @@[m [mcase class ManifestLookup(tables: Tables)[m
   }[m
 [m
   /**[m
[31m-   * SELECT[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * FROM processed_json[m
[31m-   * WHERE[m
[31m-   * event_code ='DC'[m
[31m-   * and arrival_port_code='STN'[m
[31m-   * and departure_port_code='SZY'[m
[31m-   * and voyage_number=229[m
[31m-   * and EXTRACT(DOW FROM scheduled) = EXTRACT(DOW FROM TIMESTAMP '2024-04-30')::int[m
[31m-   * and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-04-30' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-04-30')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-04-30' + interval '1 week')::int)[m
[31m-   * and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-04-30' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-04-30')::int)[m
[31m-   * GROUP BY[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * ORDER BY scheduled DESC[m
[31m-   * LIMIT 6[m
[32m+[m[32m   SELECT[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   FROM processed_json[m
[32m+[m[32m   WHERE[m
[32m+[m[32m   event_code ='DC'[m
[32m+[m[32m   and arrival_port_code='STN'[m
[32m+[m[32m   and departure_port_code='BJV'[m
[32m+[m[32m   and voyage_number=1416[m
[32m+[m[32m   and EXTRACT(DOW FROM scheduled) = EXTRACT(DOW FROM TIMESTAMP '2024-05-14')::int[m
[32m+[m[32m   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)[m
[32m+[m[32m   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)[m
[32m+[m[32m   GROUP BY[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   ORDER BY scheduled DESC[m
[32m+[m[32m   LIMIT 6[m
    */[m
 [m
   private def sameFlight3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {[m
[36m@@ -231,26 +240,26 @@[m [mcase class ManifestLookup(tables: Tables)[m
   }[m
 [m
   /**[m
[31m-   * SELECT[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * FROM processed_json[m
[31m-   * WHERE[m
[31m-   * event_code ='DC'[m
[31m-   * and arrival_port_code='STN'[m
[31m-   * and departure_port_code='BER'[m
[31m-   * and voyage_number=144[m
[31m-   * and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-10' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-10')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-10' + interval '1 week')::int)[m
[31m-   * and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-10' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-10')::int)[m
[31m-   * GROUP BY[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * ORDER BY scheduled DESC[m
[31m-   * LIMIT 6[m
[32m+[m[32m   SELECT[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   FROM processed_json[m
[32m+[m[32m   WHERE[m
[32m+[m[32m   event_code ='DC'[m
[32m+[m[32m   and arrival_port_code='STN'[m
[32m+[m[32m   and departure_port_code='BJV'[m
[32m+[m[32m   and voyage_number=1416[m
[32m+[m[32m   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)[m
[32m+[m[32m   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)[m
[32m+[m[32m   GROUP BY[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   ORDER BY scheduled DESC[m
[32m+[m[32m   LIMIT 6[m
    */[m
 [m
   private def sameRouteAndDay3WeekWindowPreviousYearQuery(uniqueArrivalKey: UniqueArrivalKey): SqlStreamingAction[Vector[(String, String, String, Timestamp)], (String, String, String, Timestamp), Effect] = {[m
[36m@@ -279,26 +288,26 @@[m [mcase class ManifestLookup(tables: Tables)[m
   }[m
 [m
   /**[m
[31m-   * SELECT[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * FROM[m
[31m-   * processed_json[m
[31m-   * WHERE[m
[31m-   * event_code ='DC'[m
[31m-   * and arrival_port_code='STN'[m
[31m-   * and departure_port_code='SDR'[m
[31m-   * and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-04-29' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-04-29')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-04-29' + interval '1 week')::int)[m
[31m-   * and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-04-29' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-04-29')::int)[m
[31m-   * GROUP BY[m
[31m-   * arrival_port_code,[m
[31m-   * departure_port_code,[m
[31m-   * voyage_number,[m
[31m-   * scheduled[m
[31m-   * ORDER BY scheduled DESC[m
[31m-   * LIMIT 6[m
[32m+[m[32m   SELECT[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   FROM[m
[32m+[m[32m   processed_json[m
[32m+[m[32m   WHERE[m
[32m+[m[32m   event_code ='DC'[m
[32m+[m[32m   and arrival_port_code='STN'[m
[32m+[m[32m   and departure_port_code='BJV'[m
[32m+[m[32m   and EXTRACT(WEEK FROM scheduled) IN (EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' - interval '1 week')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14')::int, EXTRACT(WEEK FROM TIMESTAMP '2024-05-14' + interval '1 week')::int)[m
[32m+[m[32m   and EXTRACT(YEAR FROM scheduled) IN (EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '2 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14' - interval '1 year')::int, EXTRACT(YEAR FROM TIMESTAMP '2024-05-14')::int)[m
[32m+[m[32m   GROUP BY[m
[32m+[m[32m   arrival_port_code,[m
[32m+[m[32m   departure_port_code,[m
[32m+[m[32m   voyage_number,[m
[32m+[m[32m   scheduled[m
[32m+[m[32m   ORDER BY scheduled DESC[m
[32m+[m[32m   LIMIT 6[m
    */[m
 [m
   private def paxForArrivalQuery(flightKeys: Vector[(String, String, String, Timestamp)]): Future[Seq[ManifestPassengerProfile]] = {[m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala b/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[1mindex 4b31293c6..1d79e99bf 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/service/ApplicationService.scala[m
[36m@@ -1,6 +1,6 @@[m
 package uk.gov.homeoffice.drt.service[m
 [m
[31m-import actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddRecalculateArrivalsSubscriber}[m
[32m+[m[32mimport actors.CrunchManagerActor.{AddQueueCrunchSubscriber, AddQueueHistoricPaxLookupSubscriber, AddQueueHistoricSplitsLookupSubscriber, AddRecalculateArrivalsSubscriber}[m
 import actors._[m
 import actors.daily.PassengersActor[m
 import actors.persistent._[m
[36m@@ -8,7 +8,7 @@[m [mimport actors.routing.FlightsRouterActor.{AddHistoricPaxRequestActor, AddHistori[m
 import akka.actor.{ActorRef, ActorSystem, Props, typed}[m
 import akka.pattern.{StatusReply, ask}[m
 import akka.stream.scaladsl.{Flow, Sink, Source}[m
[31m-import akka.stream.{CompletionStrategy, Materializer, OverflowStrategy, UniqueKillSwitch}[m
[32m+[m[32mimport akka.stream.{Materializer, UniqueKillSwitch}[m
 import akka.util.Timeout[m
 import akka.{Done, NotUsed}[m
 import drt.server.feeds.Feed.FeedTick[m
[36m@@ -16,11 +16,9 @@[m [mimport drt.server.feeds.FeedPoller.{AdhocCheck, Enable}[m
 import drt.server.feeds._[m
 import drt.server.feeds.api.{ApiFeedImpl, DbManifestArrivalKeys, DbManifestProcessor}[m
 import drt.shared.CrunchApi.{CrunchMinute, MillisSinceEpoch, MinutesContainer, StaffMinute}[m
[31m-import drt.shared.FlightsApi.PaxForArrivals[m
 import drt.shared._[m
[31m-import manifests.passengers.{BestAvailableManifest, ManifestLike, ManifestPaxCount}[m
[32m+[m[32mimport manifests.ManifestLookupLike[m
 import manifests.queues.SplitsCalculator[m
[31m-import manifests.{ManifestLookupLike, UniqueArrivalKey}[m
 import org.slf4j.{Logger, LoggerFactory}[m
 import passengersplits.parsing.VoyageManifestParser[m
 import play.api.Configuration[m
[36m@@ -52,6 +50,7 @@[m [mimport uk.gov.homeoffice.drt.crunchsystem.{ActorsServiceLike, PersistentStateAct[m
 import uk.gov.homeoffice.drt.db.AggregateDb[m
 import uk.gov.homeoffice.drt.egates.{EgateBank, EgateBanksUpdate, EgateBanksUpdates, PortEgateBanksUpdates}[m
 import uk.gov.homeoffice.drt.ports.Queues.Queue[m
[32m+[m[32mimport uk.gov.homeoffice.drt.ports.SplitRatiosNs.SplitSources.Historical[m
 import uk.gov.homeoffice.drt.ports.Terminals.Terminal[m
 import uk.gov.homeoffice.drt.ports._[m
 import uk.gov.homeoffice.drt.ports.config.slas.SlaConfigs[m
[36m@@ -64,7 +63,7 @@[m [mimport uk.gov.homeoffice.drt.time._[m
 import javax.inject.Singleton[m
 import scala.collection.SortedSet[m
 import scala.collection.immutable.SortedMap[m
[31m-import scala.concurrent.duration.{DurationInt, DurationLong, FiniteDuration}[m
[32m+[m[32mimport scala.concurrent.duration.{DurationInt, DurationLong}[m
 import scala.concurrent.{Await, ExecutionContext, Future}[m
 import scala.util.{Failure, Success}[m
 [m
[36m@@ -198,7 +197,28 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
 [m
   private val egatesProvider: () => Future[PortEgateBanksUpdates] = () => egateBanksUpdatesActor.ask(GetState).mapTo[PortEgateBanksUpdates][m
 [m
[31m-  val crunchManagerActor: ActorRef = system.actorOf(Props(new CrunchManagerActor), name = "crunch-manager-actor")[m
[32m+[m[32m  private val missingHistoricSplitsArrivalKeysForDate: UtcDate => Future[Iterable[UniqueArrival]] =[m
[32m+[m[32m    date => flightsProvider.allTerminalsDateRange(date, date)[m
[32m+[m[32m      .map {[m
[32m+[m[32m        _._2[m
[32m+[m[32m          .filter(!_.apiFlight.Origin.isDomesticOrCta)[m
[32m+[m[32m          .filter(!_.splits.exists(_.source == Historical))[m
[32m+[m[32m          .map(_.unique)[m
[32m+[m[32m      }[m
[32m+[m[32m      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))[m
[32m+[m
[32m+[m[32m  private val missingPaxArrivalKeysForDate: UtcDate => Future[Iterable[UniqueArrival]] =[m
[32m+[m[32m    date => flightsProvider.allTerminalsDateRange(date, date)[m
[32m+[m[32m      .map {[m
[32m+[m[32m        _._2[m
[32m+[m[32m          .filter(!_.apiFlight.Origin.isDomesticOrCta)[m
[32m+[m[32m          .filter(_.apiFlight.hasNoPaxSource)[m
[32m+[m[32m          .map(_.unique)[m
[32m+[m[32m      }[m
[32m+[m[32m      .runWith(Sink.fold(Seq[UniqueArrival]())(_ ++ _))[m
[32m+[m
[32m+[m[32m  private val crunchManagerProps: Props = Props(new CrunchManagerActor(missingHistoricSplitsArrivalKeysForDate, missingPaxArrivalKeysForDate))[m
[32m+[m[32m  val crunchManagerActor: ActorRef = system.actorOf(crunchManagerProps, name = "crunch-manager-actor")[m
   private val refetchApiData: Boolean = config.get[Boolean]("crunch.manifests.refetch-live-api")[m
 [m
   val addArrivalPredictions: ArrivalsDiff => Future[ArrivalsDiff] =[m
[36m@@ -238,13 +258,13 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
       val mergeArrivalRequest: MillisSinceEpoch => MergeArrivalsRequest =[m
         (millis: MillisSinceEpoch) => MergeArrivalsRequest(SDate(millis).toUtcDate)[m
 [m
[31m-      val historicSplitsActor = RunnableHistoricSplits([m
[32m+[m[32m      val historicSplitsQueueActor = RunnableHistoricSplits([m
         airportConfig.portCode,[m
         actorService.flightsRouterActor,[m
         splitsCalculator.splitsForManifest,[m
         manifestLookupService.maybeBestAvailableManifest)[m
 [m
[31m-      val historicPaxActor = RunnableHistoricPax(airportConfig.portCode, actorService.flightsRouterActor, manifestLookupService.maybeHistoricManifestPax)[m
[32m+[m[32m      val historicPaxQueueActor = RunnableHistoricPax(airportConfig.portCode, actorService.flightsRouterActor, manifestLookupService.maybeHistoricManifestPax)[m
 [m
       val crunchRequestQueueActor: ActorRef = startPaxLoads(actors.crunchQueueActor, crunchQueue, crunchRequest)[m
 [m
[36m@@ -280,10 +300,14 @@[m [mcase class ApplicationService(journalType: StreamingJournalLike,[m
       system.scheduler.scheduleAtFixedRate(delayUntilTomorrow.millis, 1.day)(() => staffChecker.calculateForecastStaffMinutes())[m
 [m
       egateBanksUpdatesActor ! AddUpdatesSubscriber(crunchRequestQueueActor)[m
[32m+[m
       crunchManagerActor ! AddQueueCrunchSubscriber(crunchRequestQueueActor)[m
       crunchManagerActor ! AddRecalculateArrivalsSubscriber(mergeArrivalsRequestQueueActor)[m
[31m-      actorService.flightsRouterActor ! AddHistoricSplitsRequestActor(historicSplitsActor)[m
[31m-      actorService.flightsRouterActor ! AddHistoricPaxRequestActor(historicPaxActor)[m
[32m+[m[32m      crunchManagerActor ! AddQueueHistoricSplitsLookupSubscriber(historicSplitsQueueActor)[m
[32m+[m[32m      crunchManagerActor ! AddQueueHistoricPaxLookupSubscriber(historicPaxQueueActor)[m
[32m+[m
[32m+[m[32m      actorService.flightsRouterActor ! AddHistoricSplitsRequestActor(historicSplitsQueueActor)[m
[32m+[m[32m      actorService.flightsRouterActor ! AddHistoricPaxRequestActor(historicPaxQueueActor)[m
 [m
       feedService.forecastBaseFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)[m
       feedService.forecastFeedArrivalsActor ! AddUpdatesSubscriber(mergeArrivalsRequestQueueActor)[m
[1mdiff --git a/server/src/main/scala/uk/gov/homeoffice/drt/service/ManifestPersistence.scala b/server/src/main/scala/uk/gov/homeoffice/drt/service/ManifestPersistence.scala[m
[1mindex 3fc999330..a658f73a4 100644[m
[1m--- a/server/src/main/scala/uk/gov/homeoffice/drt/service/ManifestPersistence.scala[m
[1m+++ b/server/src/main/scala/uk/gov/homeoffice/drt/service/ManifestPersistence.scala[m
[36m@@ -21,11 +21,11 @@[m [mimport scala.concurrent.{ExecutionContext, Future}[m
 object ManifestPersistence {[m
   private val log = LoggerFactory.getLogger(getClass)[m
 [m
[31m-  def persistSplitsFromManifest(flightsForDate: UtcDate => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],[m
[31m-                                splitsFromManifest: (ManifestLike, Terminal) => Splits,[m
[31m-                                persistSplits: Iterable[(UniqueArrival, Splits)] => Future[Done],[m
[31m-                               )[m
[31m-                               (implicit mat: Materializer, ec: ExecutionContext): Iterable[ManifestLike] => Future[Done] =[m
[32m+[m[32m  private def persistSplitsFromManifest(flightsForDate: UtcDate => Source[(UtcDate, Seq[ApiFlightWithSplits]), NotUsed],[m
[32m+[m[32m                                        splitsFromManifest: (ManifestLike, Terminal) => Splits,[m
[32m+[m[32m                                        persistSplits: Iterable[(UniqueArrival, Splits)] => Future[Done],[m
[32m+[m[32m                                       )[m
[32m+[m[32m                                       (implicit mat: Materializer, ec: ExecutionContext): Iterable[ManifestLike] => Future[Done] =[m
     keys => Source(keys.groupBy(_.scheduled.toUtcDate))[m
       .flatMapConcat {[m
         case (date, manifests) =>[m
[36m@@ -51,16 +51,16 @@[m [mobject ManifestPersistence {[m
         Done[m
       }[m
 [m
[31m-  def manifestsFeedResponsePersistor(manifestsRouterActor: ActorRef)[m
[31m-                                    (implicit timeout: Timeout, ec: ExecutionContext): ManifestsFeedResponse => Future[Done] =[m
[32m+[m[32m  private def manifestsFeedResponsePersistor(manifestsRouterActor: ActorRef)[m
[32m+[m[32m                                            (implicit timeout: Timeout, ec: ExecutionContext): ManifestsFeedResponse => Future[Done] =[m
     response => manifestsRouterActor.ask(response).map(_ => Done)[m
 [m
   private def manifestsToSplitsPersistor(flightsRouterActor: ActorRef, splitsForManifest: (ManifestLike, Terminal) => Splits)[m
                                         (implicit[m
[31m-                                 timeout: Timeout,[m
[31m-                                 ec: ExecutionContext,[m
[31m-                                 mat: Materializer,[m
[31m-                                ): Iterable[ManifestLike] => Future[Done] = ManifestPersistence.persistSplitsFromManifest([m
[32m+[m[32m                                         timeout: Timeout,[m
[32m+[m[32m                                         ec: ExecutionContext,[m
[32m+[m[32m                                         mat: Materializer,[m
[32m+[m[32m                                        ): Iterable[ManifestLike] => Future[Done] = ManifestPersistence.persistSplitsFromManifest([m
     FlightsProvider(flightsRouterActor).allTerminalsSingleDate,[m
     splitsForManifest,[m
     FlightsRouterActor.persistSplits(flightsRouterActor),[m

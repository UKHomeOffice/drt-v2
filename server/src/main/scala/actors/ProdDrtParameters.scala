package actors

import actors.persistent.Sizes.oneMegaByte
import com.amazonaws.auth.AWSCredentials
import org.slf4j.{Logger, LoggerFactory}
import play.api.Configuration

import scala.concurrent.duration._

trait DrtParameters {
  val gateWalkTimesFilePath: String
  val standWalkTimesFilePath: String

  val forecastMaxDays: Int
  val aclDisabled: Boolean
  val aclHost: Option[String]
  val aclUsername: Option[String]
  val aclKeyPath: Option[String]
  val refreshArrivalsOnStart: Boolean
  val flushArrivalsOnStart: Boolean
  val recrunchOnStart: Boolean

  val useNationalityBasedProcessingTimes: Boolean

  val isSuperUserMode: Boolean

  val bhxIataEndPointUrl: String
  val bhxIataUsername: String
  val maybeBhxSoapEndPointUrl: Option[String]

  val maybeLtnLiveFeedUrl: Option[String]
  val maybeLtnLiveFeedUsername: Option[String]
  val maybeLtnLiveFeedPassword: Option[String]
  val maybeLtnLiveFeedToken: Option[String]
  val maybeLtnLiveFeedTimeZone: Option[String]

  val maybeLGWNamespace: Option[String]
  val maybeLGWSASToKey: Option[String]
  val maybeLGWServiceBusUri: Option[String]

  val maybeGlaLiveUrl: Option[String]
  val maybeGlaLiveToken: Option[String]
  val maybeGlaLivePassword: Option[String]
  val maybeGlaLiveUsername: Option[String]

  val useApiPaxNos: Boolean
  val displayRedListInfo: Boolean

  val enableToggleDisplayWaitTimes: Boolean
  val adjustEGateUseByUnder12s: Boolean

  val lcyLiveEndPointUrl: String
  val lcyLiveUsername: String
  val lcyLivePassword: String

  //ignore ACL flight removals X seconds after the end of the day.
  val maybeRemovalCutOffSeconds: Option[FiniteDuration]
}

case class ProdDrtParameters(config: Configuration) extends DrtParameters {
  val log: Logger = LoggerFactory.getLogger(getClass)

  override val gateWalkTimesFilePath: String = config.get[String]("walk_times.gates_csv_url")
  override val standWalkTimesFilePath: String = config.get[String]("walk_times.stands_csv_url")

  override val forecastMaxDays: Int = config.get[Int]("crunch.forecast.max_days")
  override val aclDisabled: Boolean = config.getOptional[Boolean]("acl.disabled").getOrElse(false)
  override val aclHost: Option[String] = config.getOptional[String]("acl.host")
  override val aclUsername: Option[String] = config.getOptional[String]("acl.username")
  override val aclKeyPath: Option[String] = config.getOptional[String]("acl.keypath")
  override val refreshArrivalsOnStart: Boolean = config.getOptional[Boolean]("crunch.refresh-arrivals-on-start").getOrElse(false)
  override val flushArrivalsOnStart: Boolean = config.getOptional[Boolean]("crunch.flush-arrivals-on-start").getOrElse(false)
  override val recrunchOnStart: Boolean = config.getOptional[Boolean]("crunch.recrunch-on-start").getOrElse(false)

  override val useNationalityBasedProcessingTimes: Boolean = config.getOptional[String]("feature-flags.nationality-based-processing-times").isDefined

  override val isSuperUserMode: Boolean = config.getOptional[String]("feature-flags.super-user-mode").isDefined

  override val bhxIataEndPointUrl: String = config.get[String]("feeds.bhx.iata.endPointUrl")
  override val bhxIataUsername: String = config.get[String]("feeds.bhx.iata.username")

  override val maybeBhxSoapEndPointUrl: Option[String] = config.getOptional[String]("feeds.bhx.soap.endPointUrl")

  override val maybeLtnLiveFeedUrl: Option[String] = config.getOptional[String]("feeds.ltn.live.url")
  override val maybeLtnLiveFeedUsername: Option[String] = config.getOptional[String]("feeds.ltn.live.username")
  override val maybeLtnLiveFeedPassword: Option[String] = config.getOptional[String]("feeds.ltn.live.password")
  override val maybeLtnLiveFeedToken: Option[String] = config.getOptional[String]("feeds.ltn.live.token")
  override val maybeLtnLiveFeedTimeZone: Option[String] = config.getOptional[String]("feeds.ltn.live.timezone")

  override val maybeLGWNamespace: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.namespace")
  override val maybeLGWSASToKey: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.sas_to_Key")
  override val maybeLGWServiceBusUri: Option[String] = config.getOptional[String]("feeds.lgw.live.azure.service_bus_uri")

  override val maybeGlaLiveUrl: Option[String] = config.getOptional[String]("feeds.gla.url")
  override val maybeGlaLiveToken: Option[String] = config.getOptional[String]("feeds.gla.token")
  override val maybeGlaLivePassword: Option[String] = config.getOptional[String]("feeds.gla.password")
  override val maybeGlaLiveUsername: Option[String] = config.getOptional[String]("feeds.gla.username")

  override val useApiPaxNos: Boolean = config.get[Boolean]("feature-flags.use-api-pax-nos")
  override val displayRedListInfo: Boolean = config.get[Boolean]("feature-flags.display-red-list-info")

  override val enableToggleDisplayWaitTimes: Boolean = config.get[Boolean]("feature-flags.enable-toggle-display-wait-times")
  override val adjustEGateUseByUnder12s: Boolean = config.get[Boolean]("feature-flags.adjust-egates-use-by-u12s")

  override val lcyLiveEndPointUrl: String = config.get[String]("feeds.lcy.live.endPointUrl")
  override val lcyLiveUsername: String = config.get[String]("feeds.lcy.live.username")
  override val lcyLivePassword: String = config.get[String]("feeds.lcy.live.password")

  //ignore ACL flight removals X seconds after the end of the day.
  override val maybeRemovalCutOffSeconds: Option[FiniteDuration] = config.getOptional[Int]("acl.removal-cutoff-seconds").map(s => s.seconds)

}
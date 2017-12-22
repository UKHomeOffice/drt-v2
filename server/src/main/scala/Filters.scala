import com.google.inject.Inject
import controllers.NoCacheFilter
import org.slf4j.LoggerFactory
import play.api.Environment
import play.api.http.HttpFilters

class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter) extends HttpFilters {
  val log = LoggerFactory.getLogger(getClass)
  override val filters = {
    log.info(s"getting filters")
    Seq(noCache)
  }
}
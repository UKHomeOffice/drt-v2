import com.google.inject.Inject
import controllers.NoCacheFilter
import play.api.Environment
import play.api.http.HttpFilters

class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter) extends HttpFilters {
  override val filters = Seq(noCache)
}
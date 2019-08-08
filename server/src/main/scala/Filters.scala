import filters.NoCacheFilter
import javax.inject.{Inject, Singleton}
import play.api.Environment
import play.api.http.HttpFilters
import play.filters.headers.SecurityHeadersFilter

@Singleton
class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter) extends HttpFilters {
  override val filters = Seq(noCache, SecurityHeadersFilter())
}

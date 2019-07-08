import javax.inject.{Inject, Singleton}
import filters.{ACPRedirectFilter, NoCacheFilter, SecurityHeadersFilter}
import play.api.Environment
import play.api.http.HttpFilters

@Singleton
class Filters @Inject()(
                         env: Environment,
                         acpRedirectFilter : ACPRedirectFilter, noCache: NoCacheFilter, securityHeaders: SecurityHeadersFilter) extends HttpFilters {
  override val filters = Seq(acpRedirectFilter, noCache, securityHeaders)
}

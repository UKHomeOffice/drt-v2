import javax.inject.{Inject, Singleton}

import controllers.{NoCacheFilter, SecurityHeadersFilter}
import play.api.Environment
import play.api.http.HttpFilters

@Singleton
class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter, securityHeaders: SecurityHeadersFilter) extends HttpFilters {
  override val filters = Seq(noCache, securityHeaders)
}

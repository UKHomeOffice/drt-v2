import filters.NoCacheFilter
import javax.inject.{Inject, Singleton}
import play.api.Environment
import play.api.http.HttpFilters
import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}

@Singleton
class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter) extends HttpFilters {
  override val filters = Seq(noCache, SecurityHeadersFilter(SecurityHeadersConfig(
    frameOptions = None,
    xssProtection = None,
    contentSecurityPolicy = Option("default-src 'self'; script-src 'self' www.google-analytics.com ajax.googleapis.com; style-src 'self' cdnjs.cloudflare.com 'unsafe-inline'; font-src 'self' cdnjs.cloudflare.com"),
    contentTypeOptions = None
  )))
}

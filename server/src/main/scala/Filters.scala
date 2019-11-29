import filters.NoCacheFilter
import javax.inject.{Inject, Singleton}
import play.api.Environment
import play.api.http.HttpFilters
import play.api.mvc.EssentialFilter
import play.filters.headers.{SecurityHeadersConfig, SecurityHeadersFilter}

@Singleton
class Filters @Inject()(env: Environment,
                        noCache: NoCacheFilter) extends HttpFilters {
  override val filters: Seq[EssentialFilter] = Seq(noCache, SecurityHeaders.filter)
}

object SecurityHeaders {
  val default = "default-src 'self'"
  val javaScript = "script-src 'self' www.google-analytics.com ajax.googleapis.com 'sha256-g7xvSeFvOZB+aVUYeI3wlm6J3HMikqnm32sVJHUX9f8='"
  val styles = "style-src 'self' cdnjs.cloudflare.com 'unsafe-inline'"
  val fonts = "font-src 'self' cdnjs.cloudflare.com"
  val images = "img-src 'self' https://www.google-analytics.com www.google-analytics.com"

  val filter = SecurityHeadersFilter(SecurityHeadersConfig(
    frameOptions = None,
    xssProtection = None,
    contentSecurityPolicy = Option(default + "; " + javaScript + "; " + styles + "; " + fonts + "; " + images),
    contentTypeOptions = None
  ))
}

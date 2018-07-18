import javax.inject.{Singleton, Inject}
import controllers.NoCacheFilter
import play.api.Environment
import play.api.http.HttpFilters

@Singleton
class Filters @Inject()(
                         env: Environment,
                         noCache: NoCacheFilter) extends HttpFilters {
  override val filters = Seq(noCache)
}
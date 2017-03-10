name := "bundle"

scalaVersion := "2.11.8"

lazy val bundle = taskKey[Int]("bundle")

enablePlugins(SbtWeb)

enablePlugins(SbtJsEngine)



import com.typesafe.sbt.jse.JsEngineImport.JsEngineKeys._
import com.typesafe.sbt.jse.SbtJsTask._
import com.typesafe.sbt.jse.SbtJsEngine.autoImport.JsEngineKeys._
import scala.concurrent.duration._

bundle := {
  (npmNodeModules in Assets).value
  val inf = (baseDirectory.value / "start.js").getAbsolutePath
  val res = baseDirectory.value.getParentFile / "client" / "src" / "main" / "resources"
  res.mkdirs
  val outf = (res / "bundle.js").getAbsolutePath
  val modules = (baseDirectory.value / "node_modules").getAbsolutePath
  println(s"Bundling: ${inf}\n -> ${outf}")
  executeJs(state.value,
    JsEngineKeys.EngineType.Node,
    None,
    Seq(modules),
    baseDirectory.value / "browserify.js",
    Seq(inf, outf),
    30.seconds)
  1
}

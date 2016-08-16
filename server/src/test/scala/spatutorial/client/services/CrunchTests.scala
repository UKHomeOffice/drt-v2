package spatutorial.client.services

import javax.script._

import spatutorial.shared.{TodoNormal, TodoHigh, TodoLow, TodoItem}
import utest._

object TryRenjin {
  lazy val manager = new ScriptEngineManager()
  lazy val engine = manager.getEngineByName("Renjin")

  def crunch() = {
    if (engine == null) throw new RuntimeException("Couldn't load Renjin script engine on the classpath")
    engine.eval("df <- data.frame(x=1:10, y=(1:10)+rnorm(n=10))")
    engine.eval("print(df)")
    engine.eval("print(lm(y ~ x, df))")
  }
}

object CrunchTests extends TestSuite {

  def tests = TestSuite {
    'canCrunch - {
      TryRenjin.crunch()
      assert(true)
    }

  }
}


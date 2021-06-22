package drt.shared

import drt.shared.Terminals._
import drt.shared.TimeUtil._
import drt.shared.api.{TerminalWalkTimes, WalkTime, WalkTimes}
import org.specs2.mutable.Specification
class WalkTimesSpec extends Specification {

  "When formatting a walk time as minutes and seconds" >> {

    "Given a round minute I should get back the minute with nos mentioned" >> {
      val millis = 60000L
      val result: String = MinuteAsNoun(millisToMinutes(millis)).display

      result === "1 minute"
    }

    "Given 90s I should get back 1 minute" >> {
      val millis = 90000L
      val result: String =  MinuteAsNoun(millisToMinutes(millis)).display

      result === "1 minute"
    }
  }

  val stand1T1 = WalkTime("stand1", T1, 10000L)
  val stand2T1 = WalkTime("stand2", T1, 40000L)
  val stand1T2 = WalkTime("stand1", T2, 20000L)

  val gate1T1 = WalkTime("gate1", T1, 10000L)
  val gate2T1 = WalkTime("gate2", T1, 20000L)
  val gate1T2 = WalkTime("gate1", T2, 40000L)

  "Given a seq of standWalkTimes and a seq of gateWalkTimes I should get back a WalktTimes case class" >> {

    val standWalkTimes = Seq(
      stand1T1,
      stand2T1,
      stand1T2,
    )


    val gateWalkTimes = Seq(
      gate1T1,
      gate2T1,
      gate1T2,
    )

    val result = WalkTimes(gateWalkTimes, standWalkTimes)

    val expected = WalkTimes(
      Map(
        T2 -> TerminalWalkTimes(
          Map(
            "gate1" -> gate1T2
          ),
          Map(
            "stand1" -> stand1T2
          ),
        ),
        T1 -> TerminalWalkTimes(
          Map(
            "gate1" -> gate1T1,
            "gate2" -> gate2T1
          ),
          Map(
            "stand1" -> stand1T1,
            "stand2" -> stand2T1
          ),
        ),
      ),
    )

    result === expected
  }

  "When getting a walk time for an arrival" >> {

    val gateWalkTimes = Seq(
      gate1T1,
      gate2T1,
      gate1T2,
    )

    val standWalkTimes = Seq(
      stand1T1,
      stand2T1,
      stand1T2,
    )

    val wt = WalkTimes(gateWalkTimes, standWalkTimes)

    val walkTimeProvider: (Option[String], Option[String], Terminal) => String = wt.walkTimeForArrival(300000L)

    "Given a gate and no stand I should get back the gate walk time" >> {

      val result = walkTimeProvider(Option("gate1"), None, T1)

      result === s"${gate1T1.inMinutes} minute walk time"
    }

    "Given a stand and no gate I should get back the stand walk time" >> {

      val result = walkTimeProvider(None, Option("stand1"), T1)

      result === s"${stand1T1.inMinutes} minute walk time"
    }

    "Given a gate and a stand I should get back the gate walk time" >> {

      val result = walkTimeProvider(Option("gate1"), Option("stand1"), T1)

      result === s"${gate1T1.inMinutes} minute walk time"
    }

    "Given no gate or stand I should get back the default walk time" >> {

      val result = walkTimeProvider(None, None, T1)

      result === "5 minutes (default walk time for terminal)"
    }

    "Given a non existent gate I should get back the default walk time" >> {

      val result = walkTimeProvider(Option("notValid"), None, T1)

      result === "5 minutes (default walk time for terminal)"
    }

    "Given a non existent stand I should get back the default walk time" >> {

      val result = walkTimeProvider(None, Option("notValid"), T1)

      result === "5 minutes (default walk time for terminal)"
    }

    "Given a non existent gate and a valid stand I should get back the stand time" >> {

      val result = walkTimeProvider(Option("notValid"), Option("stand1"), T1)

      result === s"${stand1T1.inMinutes} minute walk time"
    }

    "Given a non existent stand and a valid gate I should get back the gate time" >> {

      val result = walkTimeProvider(Option("gate1"), Option("notValid"), T1)

      result === s"${gate1T1.inMinutes} minute walk time"
    }
  }

}

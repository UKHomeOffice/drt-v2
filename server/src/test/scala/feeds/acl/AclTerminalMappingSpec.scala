package feeds.acl

import drt.server.feeds.acl.AclFeed
import drt.shared.PortCode
import drt.shared.Terminals._
import org.specs2.mutable.Specification

class AclTerminalMappingSpec extends Specification {

  "Given an ACL terminal name for a port" >> {
    "When I apply the terminal map to International terminals" >> {
      "Then I should get the right terminal for the port" >> {

        val portAclTerminalToExpectedTerminal = Map(
          "BFS" -> Map(
            "1I" -> T1,
          ),
          "BHX" -> Map(
            "1I" -> T1,
            "2I" -> T2,
          ),
          "BRS" -> Map(
            "1I" -> T1,
          ),
          "EDI" -> Map(
            "1I" -> A1
          ),
          "EMA" -> Map(
            "1I" -> T1,
          ),
          "GLA" -> Map(
            "1I" -> T1,
          ),
          "LCY" -> Map(
            "Ter" -> T1,
          ),
          "LGW" -> Map(
            "1I" -> S,
            "2I" -> N,
          ),
          "LHR" -> Map(
            "2I" -> T2,
            "3I" -> T3,
            "4I" -> T4,
            "5I" -> T5,
          ),
          "LPL" -> Map(
            "1I" -> T1,
          ),
          "LTN" -> Map(
            "1I" -> T1,
          ),
          "MAN" -> Map(
            "T1" -> T1,
            "T2" -> T2,
            "T3" -> T3
          ),
          "NCL" -> Map(
            "1I" -> T1,
          ),
          "STN" -> Map(
            "1I" -> T1,
          )
        )

        portAclTerminalToExpectedTerminal.map {
          case (portCode, terminalMap) =>
            terminalMap.map {
              case (mapFrom, mapTo) =>
                s"$portCode ${AclFeed.aclToPortMapping(PortCode(portCode))(Terminal(mapFrom))}" === s"$portCode ${mapTo}"
            }
        }
        success
      }
    }
    "When I apply the terminal map to Domestic/CTA terminals" >> {
      "Then I should get the right terminal for the port" >> {

        val portAclTerminalToExpectedTerminal = Map(
          "BHX" -> Map(
            "1D" -> T1,
            "2D" -> T2,
          ),
          "BFS" -> Map(
            "1D" -> T1,
          ),
          "BRS" -> Map(
            "1D" -> T1,
          ),
          "EMA" -> Map(
            "1D" -> T1,
          ),
          "GLA" -> Map(
            "1D" -> T1,
          ),
          "LCY" -> Map(
            "MainApron" -> T1,
          ),
          "LGW" -> Map(
            "1D" -> S,
            "2D" -> N,
          ),
          "LHR" -> Map(
            "2D" -> T2,
            "5D" -> T5,
          ),
          "LPL" -> Map(
            "1D" -> T1,
          ),
          "LTN" -> Map(
            "1D" -> T1,
          ),
          "STN" -> Map(
            "CTA" -> T1,
          )
        )
        
        portAclTerminalToExpectedTerminal.map {
          case (portCode, terminalMap) =>
            terminalMap.map {
              case (mapFrom, mapTo) =>

                s"$portCode ${AclFeed.aclToPortMapping(PortCode(portCode))(Terminal(mapFrom))}" === s"$portCode ${mapTo}"
            }
        }
        success
      }
    }
  }


}

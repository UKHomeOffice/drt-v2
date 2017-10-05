package services.inputfeeds

import com.typesafe.config.ConfigFactory
import drt.shared.Arrival
import net.schmizz.sshj.sftp.SFTPClient
import org.specs2.mutable.Specification
import server.feeds.acl.AclFeed._


class AclSpec extends Specification {
  "Looking at flights" >> {
    skipped("Integration test for ACL - requires SSL certificate to run")
    val ftpServer = ConfigFactory.load.getString("acl.host")
    val username = ConfigFactory.load.getString("acl.username")
    val path = ConfigFactory.load.getString("acl.keypath")

    val sftp: SFTPClient = sftpClient(ftpServer, username, path)
    val latestFile = latestFileForPort(sftp, "MAN")
    println(s"latestFile: $latestFile")
    val aclArrivals: List[Arrival] = arrivalsFromCsvContent(contentFromFileName(sftp, latestFile))

    val todayArrivals = aclArrivals
      .filter(_.SchDT < "2017-10-05T23:00")
      .groupBy(_.Terminal)

    todayArrivals.foreach {
      case (tn, arrivals) =>
        val tByUniqueId = todayArrivals(tn).groupBy(_.uniqueId)
        println(s"uniques for $tn: ${tByUniqueId.keys.size} flights")
        val nonUniques = tByUniqueId.filter {
          case (uid, a) => a.length > 1
        }.foreach {
          case (uid, a) => println(s"non-unique: $uid -> $a")
        }
    }

    todayArrivals.keys.foreach(t => println(s"terminal $t has ${todayArrivals(t).size} flights"))

    true
  }

  "Given ACL csv content containing a header line and one arrival line " +
    "When I ask for the arrivals " +
    "Then I should see a list containing the appropriate Arrival" >> {
    val csvContent =
      """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
        |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
      """.stripMargin

    val arrivals = arrivalsFromCsvContent(csvContent)
    val expected = List(Arrival("4U", "Forecast", "", "", "", "", "", "", 180, 149, 0, "", "", -904483842, "LHR", "T2", "4U0460", "4U0460", "CGN", "2017-10-13T07:10:00Z", 1507878600000L, 0, None))

    arrivals === expected
  }

  "Given ACL csv content containing a header line and one departure line " +
    "When I ask for the arrivals " +
    "Then I should see an empty list" >> {
    val csvContent =
      """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
        |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
      """.stripMargin

    val arrivals = arrivalsFromCsvContent(csvContent)
    val expected = List()

    arrivals === expected
  }
}

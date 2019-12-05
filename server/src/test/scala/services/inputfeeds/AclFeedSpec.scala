package services.inputfeeds

import com.typesafe.config.ConfigFactory
import controllers.ArrivalGenerator
import drt.server.feeds.acl.AclFeed
import drt.server.feeds.acl.AclFeed.{arrivalsFromCsvContent, contentFromFileName, latestFileForPort, sftpClient}
import drt.shared
import drt.shared.FlightsApi.Flights
import drt.shared.PaxTypesAndQueues._
import drt.shared.Terminals._
import drt.shared._
import server.feeds.{ArrivalsFeedFailure, ArrivalsFeedSuccess}
import services.SDate
import services.crunch.CrunchTestLike

import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.duration._


class AclFeedSpec extends CrunchTestLike {
  val regularTerminalMapping: Terminal => Terminal = (t: Terminal) => t

  "ACL feed failures" >> {
    val aclFeed = AclFeed("nowhere.nowhere", "badusername", "badpath", PortCode("BAD"), (_: Terminal) => T1)

    val result = aclFeed.requestArrivals.getClass

    val expected = classOf[ArrivalsFeedFailure]

    result === expected
  }

  "ACL feed parsing" >> {

    "Given ACL csv content containing a header line and one arrival line " +
      "When I ask for the arrivals " +
      "Then I should see a list containing the appropriate Arrival" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,A,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List(Arrival(Operator = Option(Operator("4U")), Status = ArrivalStatus("ACL Forecast"), Estimated = None, Actual = None,
        EstimatedChox = None, ActualChox = None, Gate = None, Stand = None, MaxPax = Option(180), ActPax = Option(149),
        TranPax = None, RunwayID = None, BaggageReclaimId = None, AirportID = PortCode("LHR"), Terminal = T2,
        rawICAO = "4U0460", rawIATA = "4U0460", Origin = PortCode("CGN"),FeedSources = Set(shared.AclFeedSource),
        Scheduled = 1507878600000L, PcpTime = None))

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one departure line " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460,0.827777802944183
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List()

      arrivals === expected
    }

    "Given ACL csv content containing a header line and one positioning flight " +
      "When I ask for the arrivals " +
      "Then I should see an empty list" >> {
      val csvContent =
        """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
          |32A,,LHR,D,09SEP2016 0606,2017-10-13,0000500,29SEP2017 0959,A320,EDDK,EDDK,CGN,DE,4U,STAR ALLIANCE,GERMANWINGS GMBH,CGN,DE,T2-Intl & CTA,S17,180,0460,J,,2I,0710,4U,0461,4U0460P,0.827777802944183
        """.stripMargin

      val arrivals = arrivalsFromCsvContent(csvContent, regularTerminalMapping)
      val expected = List()

      arrivals === expected
    }

    "For BFS" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |320,,BFS,A,23MAY2019 1052,2019-12-29,0000007,07NOV2019 1442,A320,LBWN,LBWN,VAR,BG,BGH,,Balkan Holidays Air Ltd,VAR,BG,International,W19,0,5501,P,,1I,0950,BGH,5502,BGH5501,0
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("BFS"))
        val expected = Set((VoyageNumber(5501), T1))

        arrivals === expected
      }
    }

    "For BHX" >> {
      "Given ACL csv content containing a header line and a flight for each of the 3 terminals  " +
        "When I ask for the arrivals " +
        "Then I should see one arrival for each terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |73H,,BHX,A,05APR2019 0936,2019-12-14,0000060,11JUL2019 0954,B738,LROP,LROP,OTP,RO,0B,,Blue Air,OTP,RO,Terminal 1 (Intl & CTA),W19,189,0151,J,,1I,1015,0B,0152,0B0151,0.699999988079071
            |DH4,,BHX,A,10MAY2019 0909,2019-12-24,0200000,24OCT2019 1702,DH8D,EHAM,EHAM,AMS,NL,BE,,Flybe,AMS,NL,Terminal 2 (Intl & CTA),W19,78,0102,J,,2I,1015,,,BE0102,0.709999978542328
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("BHX"))
        val expected = Set(
          (VoyageNumber(151), T1),
          (VoyageNumber(102), T2)
        )

        arrivals === expected
      }
    }

    "For BRS" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |AT7,,BRS,A,14MAY2019 1436,2019-12-08,0000007,22NOV2019 1713,AT72,EIDW,EIDW,DUB,IE,EI,,Aer Lingus,DUB,IE,International Arrivals,W19,72,3280,J,,1I,0800,EI,3281,EI3280,0.649999976158142
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("BRS"))
        val expected = Set((VoyageNumber(3280), T1))

        arrivals === expected
      }
    }

    "For EDI" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |319,,EDI,A,14MAY2019 0642,2019-12-16,1000000,04JUL2019 1438,A319,LFPG,LFPG,CDG,FR,AF,SKYTEAM,Air France,CDG,FR,International Arrivals,W19,143,1686,J,,1I,1040,AF,1687,AF1686,0.819999992847443
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("EDI"))
        val expected = Set((VoyageNumber(1686), A1))

        arrivals === expected
      }
    }

    "For EMA" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |DH4,,EMA,A,14MAY2019 1514,2020-01-13,1000000,22OCT2019 1255,DH8D,EHAM,EHAM,AMS,NL,BE,,Flybe,AMS,NL,International Arrivals,W19,78,1094,J,,1I,1900,,,BE1094,0.709999978542328
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("EMA"))
        val expected = Set((VoyageNumber(1094), T1))

        arrivals === expected
      }
    }

    "For GLA" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |73H,,GLA,A,16MAY2019 1034,2019-12-29,0000007,16MAY2019 1034,B738,LROP,LROP,OTP,RO,0B,,Blue Air,OTP,RO,International Arrs,W19,189,0141,J,,1I,0900,0B,0142,0B0141,0.819999992847443
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("GLA"))
        val expected = Set((VoyageNumber(141), T1))

        arrivals === expected
      }
    }

    "For LCY" >> {
      "Given ACL csv content containing a header line and an international flight " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with the correct terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |E90,,LCY,A,09SEP2019 1414,2020-05-02,0000060,29OCT2019 0843,E190,LIML,LIML,LIN,IT,AZ,SKYTEAM,Alitalia,LIN,IT,MainApron,S20,100,0216,J,,TER,1025,AZ,0215,AZ0216,0.800000011920929
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("LCY"))
        val expected = Set((VoyageNumber(216), T1))

        arrivals === expected
      }
    }

    "For LGW" >> {
      "Given ACL csv content containing a header line and one flight with an unmapped terminal name " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with its mapped terminal name" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |320,,LGW,A,15APR2019 1134,2020-01-03,0000500,05JUN2019 1257,A320,GMTT,GMTT,TNG,MA,3O,,Air Arabia Maroc,TNG,MA,South International,W19,174,0101,J,,1I,1440,3O,0102,3O0101,0.889999985694885
            |73W,,LGW,A,09SEP2019 1535,2020-04-15,0030000,30OCT2019 0930,B737,UGGG,UGGG,TBS,GE,A9,,Georgian Airways,TBS,GE,North International,S20,132,0751,J,,2I,2050,A9,0752,A90751,0.959999978542328
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("LGW"))
        val expected = Set(
          (VoyageNumber(101), S),
          (VoyageNumber(751), N))

        arrivals === expected
      }
    }

    "For LHR" >> {
      "Given ACL csv content containing a header line and one flight with an unmapped terminal name " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with its mapped terminal name" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |321,,LHR,A,10APR2019 1127,2019-12-28,0000060,22AUG2019 1327,A321,LGAV,LGAV,ATH,GR,A3,STAR ALLIANCE,Aegean Airlines S.A.,ATH,GR,T2-Intl & CTA,W19,206,0600,J,,2I,1110,A3,0601,A30600,0.889999985694885
            |772,,LHR,A,06SEP2019 1203,2020-04-15,0030000,09OCT2019 0643,B772,KDFW,KDFW,DFW,US,AA,ONEWORLD,American Airlines Inc,DFW,US,Terminal 3,S20,273,0020,J,,3I,0710,,,AA0020,0.829999983310699
            |320,,LHR,A,10APR2019 1127,2019-12-06,0000500,13NOV2019 0744,A320,LFPG,LFPG,CDG,FR,AF,SKYTEAM,Air France,CDG,FR,Terminal 4,W19,174,1080,J,,4I,1820,,,AF1080,0.860000014305115
            |351,,LHR,A,06SEP2019 1203,2020-04-02,0004000,09OCT2019 0645,A35K,RJAA,RJAA,NRT,JP,BA,ONEWORLD,British Airways Plc,NRT,JP,T5-Int,S20,331,0006,J,,5I,1620,,,BA0006,0.870000004768372
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("LHR"))
        val expected = Set(
          (VoyageNumber(600), T2),
          (VoyageNumber(20), T3),
          (VoyageNumber(1080), T4),
          (VoyageNumber(6), T5)
        )

        arrivals === expected
      }
    }

    "For LPL" >> {
      "Given ACL csv content containing a header line and one flight with an unmapped terminal name " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with its mapped terminal name" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |73H,,LPL,A,16MAY2019 1039,2019-12-11,0030000,22NOV2019 1101,B738,LROP,LROP,OTP,RO,0B,,Blue Air,OTP,RO,International Arrivals,W19,189,0133,J,,1I,1745,0B,0134,0B0133,0.600000023841858
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("LPL"))
        val expected = Set((VoyageNumber(133), T1))

        arrivals === expected
      }
    }

    "For LTN" >> {
      "Given ACL csv content containing a header line and one flight with an unmapped terminal name " +
        "When I ask for the arrivals " +
        "Then I should see the arrival with its mapped terminal name" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |735,,LTN,A,06SEP2019 1323,2020-04-26,0000007,26NOV2019 1117,B735,LROP,LROP,OTP,RO,0B,,Blue Air,OTP,RO,International Arrivals,S20,60,0131,J,,1I,0850,0B,0132,0B0131,0.930000007152557
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("LTN"))
        val expected = Set((VoyageNumber(131), T1))

        arrivals === expected
      }
    }

    "For MAN" >> {
      "Given ACL csv content containing a header line and a flight for each of the 3 terminals  " +
        "When I ask for the arrivals " +
        "Then I should see one arrival for each terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |32B,,MAN,A,04OCT2019 1103,2020-05-04,1000000,29OCT2019 0909,A321,LGKR,LGKR,CFU,GR,6Y,,SmartLynx Airlines Latvia,CFU,GR,Terminal 1,S20,220,0702,C,,T1,0055,,,6Y0702,1
            |333,,MAN,A,03OCT2019 0602,2020-04-02,0004000,03OCT2019 0602,A333,VIDP,VIDP,DEL,IN,6E,,Indigo,DEL,IN,Terminal 2,S20,341,1503,J,,T2,1725,6E,1504,6E1503,0.800000011920929
            |76W,,MAN,A,09APR2019 0633,2019-12-04,0030000,23JUL2019 1636,B763,KPHL,KPHL,PHL,US,AA,ONEWORLD,American Airlines Inc,PHL,US,Terminal 3,W19,209,0734,J,,T3,0855,AA,0735,AA0734,1
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("MAN"))
        val expected = Set(
          (VoyageNumber(702), T1),
          (VoyageNumber(1503), T2),
          (VoyageNumber(734), T3))

        arrivals === expected
      }
    }

    "For NCL" >> {
      "Given ACL csv content containing a header line and a flight for each of the 3 terminals  " +
        "When I ask for the arrivals " +
        "Then I should see one arrival for each terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |318,,NCL,A,01OCT2019 1555,2020-04-04,0000060,01OCT2019 1556,A318,LFPG,LFPG,CDG,FR,AF,SKYTEAM,Air France,CDG,FR,International Arrivals,S20,131,1058,J,,1I,0935,AF,1059,AF1058,0.879999995231628
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("NCL"))
        val expected = Set((VoyageNumber(1058), T1))

        arrivals === expected
      }
    }

    "For STN" >> {
      "Given ACL csv content containing a header line and a flight for each of the 3 terminals  " +
        "When I ask for the arrivals " +
        "Then I should see one arrival for each terminal" >> {
        val csvContent =
          """A/C,ACReg,Airport,ArrDep,CreDate,Date,DOOP,EditDate,Icao Aircraft Type,Icao Last/Next Station,Icao Orig/Dest Station,LastNext,LastNextCountry,Ope,OpeGroup,OpeName,OrigDest,OrigDestCountry,Res,Season,Seats,ServNo,ST,ove.ind,Term,Time,TurnOpe,TurnServNo,OpeFlightNo,LoadFactor
            |734,,STN,A,05NOV2019 0919,2020-04-02,0004000,06NOV2019 1101,B734,LATI,LATI,TIA,AL,2B,,Albawings,TIA,AL,T1- Intl,S20,170,0221,J,,1I,0815,2B,0222,2B0221,0.939999997615814
        """.stripMargin

        val arrivals = voyageNumbersAndTerminals(csvContent, PortCode("STN"))
        val expected = Set((VoyageNumber(221), T1))

        arrivals === expected
      }
    }
  }

  def voyageNumbersAndTerminals(csvContent: String, portCode: PortCode): Set[(VoyageNumber, Terminal)] = {
    arrivalsFromCsvContent(csvContent, AclFeed.aclToPortMapping(portCode)).map(a => (a.VoyageNumber, a.Terminal)).toSet
  }

  "ACL Flights " >> {
    "Given an ACL feed with one flight and no live flights" +
      "When I ask for a crunch " +
      "Then I should see that flight in the PortState" >> {
      val scheduled = "2017-01-01T00:00Z"
      val arrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
      val aclFlight = Flights(List(arrival))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlight))

      val expected = Set(arrival.copy(FeedSources = Set(AclFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given an ACL feed with one flight and the same flight in the live feed" +
      "When I ask for a crunch " +
      "Then I should see the one flight in the PortState with the ACL flightcode and live chox" >> {
      val scheduled = "2017-01-01T00:00Z"
      val aclFlight = ArrivalGenerator.arrival(iata = "BA0001", schDt = scheduled, actPax = Option(10))
      val aclFlights = Flights(List(aclFlight))
      val liveFlight = ArrivalGenerator.arrival(iata = "BAW001", schDt = scheduled, actPax = Option(20), actChoxDt = "2017-01-01T00:30Z")
      val liveFlights = Flights(List(liveFlight))

      val fiveMinutes = 600d / 60

      val crunch = runCrunchGraph(
        now = () => SDate(scheduled),
        airportConfig = airportConfig.copy(terminalProcessingTimes = Map(T1 -> Map(eeaMachineReadableToDesk -> fiveMinutes))))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclFlights))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(liveFlights))

      val expected = Set(liveFlight.copy(CarrierCode = aclFlight.CarrierCode, VoyageNumber = aclFlight.VoyageNumber, FeedSources = Set(AclFeedSource, LiveFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given some initial ACL & live arrivals, one ACL arrival and no live arrivals " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL arrival with the initial live arrivals" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl = Set(
        ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast")),
        ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast")))
      val initialLive = Set(
        ArrivalGenerator.arrival(iata = "BA0003", schDt = "2017-01-01T00:25Z", actPax = Option(99), status = ArrivalStatus("scheduled")))

      val newAcl = Set(
        ArrivalGenerator.arrival(iata = "BA0011", schDt = "2017-01-01T00:10Z", actPax = Option(105), status = ArrivalStatus("forecast")))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(initialLive.toList)))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(newAcl.toList)))

      val expected = initialLive.map(_.copy(FeedSources = Set(LiveFeedSource))) ++ newAcl.map(_.copy(FeedSources = Set(AclFeedSource)))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given some initial arrivals, no ACL arrivals and one live arrival " +
      "When I ask for a crunch " +
      "Then I should only see the initial arrivals updated with the live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"))
      val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast"))
      val initialAcl = Set(initialAcl1, initialAcl2)
      val initialLive = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = ArrivalStatus("scheduled"))).map(a => (a.unique, a))

      val newLive = Set(
        ArrivalGenerator.arrival(iata = "BAW0001", schDt = "2017-01-01T00:05Z", actPax = Option(105), status = ArrivalStatus("estimated"), estDt = "2017-01-01T00:06Z"))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduledLive),
        initialLiveArrivals = initialLive
      )

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(Flights(initialAcl.toList)))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(Flights(newLive.toList)))

      val expected = newLive.map(_.copy(CarrierCode = CarrierCode("BA"), VoyageNumber = VoyageNumber(1), FeedSources = Set(LiveFeedSource, AclFeedSource))) + initialAcl2.copy(FeedSources = Set(AclFeedSource))

      crunch.portStateTestProbe.fishForMessage(3 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given one ACL arrival followed by one live arrival and initial arrivals which don't match them " +
      "When I ask for a crunch " +
      "Then I should only see the new ACL & live arrivals plus the initial live arrival" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val initialAcl1 = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"))
      val initialAcl2 = ArrivalGenerator.arrival(iata = "BA0002", schDt = "2017-01-01T00:15Z", actPax = Option(151), status = ArrivalStatus("forecast"))
      val initialAcl = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(initialAcl1, initialAcl2).map(a => (a.unique, a))
      val initialLive = mutable.SortedMap[UniqueArrival, Arrival]() ++ List(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(99), status = ArrivalStatus("scheduled"))).map(a => (a.unique, a))

      val newAcl = Flights(Seq(ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:07Z", actPax = Option(105))))
      val newLive = Flights(Seq(ArrivalGenerator.arrival(iata = "BAW0001", schDt = "2017-01-01T00:06Z", actPax = Option(105))))

      val crunch = runCrunchGraph(
        now = () => SDate(scheduledLive),
        initialForecastBaseArrivals = initialAcl,
        initialLiveArrivals = initialLive
      )

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(newAcl))
      offerAndWait(crunch.liveArrivalsInput, ArrivalsFeedSuccess(newLive))

      val expected = newAcl.flights.map(_.copy(FeedSources = Set(AclFeedSource))).toSet ++
        newLive.flights.map(_.copy(FeedSources = Set(LiveFeedSource))).toSet ++
        initialLive.values.map(_.copy(FeedSources = Set(LiveFeedSource)))

      crunch.portStateTestProbe.fishForMessage(5 seconds) {
        case ps: PortState =>
          val flightsResult = ps.flights.values.map(_.apiFlight).toSet
          flightsResult == expected
      }

      crunch.liveArrivalsInput.complete()

      success
    }

    "Given one ACL arrival followed by the same single ACL arrival " +
      "When I ask for a crunch " +
      "Then I should still see the arrival, ie it should not have been removed" >> {
      val scheduledLive = "2017-01-01T00:00Z"

      val aclArrival = ArrivalGenerator.arrival(iata = "BA0001", schDt = "2017-01-01T00:05Z", actPax = Option(150), status = ArrivalStatus("forecast"), feedSources = Set(AclFeedSource))

      val aclInput1 = Flights(Seq(aclArrival))
      val aclInput2 = Flights(Seq(aclArrival))

      val crunch = runCrunchGraph(now = () => SDate(scheduledLive))

      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput1))
      offerAndWait(crunch.baseArrivalsInput, ArrivalsFeedSuccess(aclInput2))

      val portStateFlightLists = crunch.portStateTestProbe.receiveWhile(3 seconds) {
        case PortState(f, _, _) => f.values.map(_.apiFlight)
      }

      val nonEmptyFlightsList = List(aclArrival.copy(FeedSources = Set(AclFeedSource)))
      val expected = List(nonEmptyFlightsList)

      crunch.liveArrivalsInput.complete()

      portStateFlightLists.distinct === expected
    }
  }

  "Looking at flights" >> {
    skipped("Integration test for ACL - requires SSL certificate to run")
    val ftpServer = ConfigFactory.load.getString("acl.host")
    val username = ConfigFactory.load.getString("acl.username")
    val path = ConfigFactory.load.getString("acl.keypath")

    val sftp = sftpClient(AclFeed.sshClient(ftpServer, username, path))
    val latestFile = latestFileForPort(sftp, PortCode("MAN"))
    println(s"latestFile: $latestFile")
    val aclArrivals: List[Arrival] = arrivalsFromCsvContent(contentFromFileName(sftp, latestFile), regularTerminalMapping)

    val todayArrivals = aclArrivals
      .filter(_.Scheduled < SDate("2017-10-05T23:00").millisSinceEpoch)
      .groupBy(_.Terminal)

    todayArrivals.foreach {
      case (tn, _) =>
        val tByUniqueId = todayArrivals(tn).groupBy(_.uniqueId)
        println(s"uniques for $tn: ${tByUniqueId.keys.size} flights")
        tByUniqueId.filter {
          case (_, a) => a.length > 1
        }.foreach {
          case (uid, a) => println(s"non-unique: $uid -> $a")
        }
    }

    todayArrivals.keys.foreach(t => println(s"terminal $t has ${todayArrivals(t).size} flights"))

    success
  }
}

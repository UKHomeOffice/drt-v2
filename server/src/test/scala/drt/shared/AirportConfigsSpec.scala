package drt.shared

import org.specs2.mutable.Specification

class AirportConfigsSpec extends Specification {

  "AirportConfigs" should {

    "have a list size of 24 of min and max desks by terminal and queue for all ports" in {
      for {
        port <- AirportConfigs.allPorts
        terminalName <- port.minMaxDesksByTerminalQueue.keySet
        queueName <- port.minMaxDesksByTerminalQueue(terminalName).keySet
        (minDesks, maxDesks) = port.minMaxDesksByTerminalQueue(terminalName)(queueName)
      } yield {
        minDesks.size.aka(s"minDesk-> ${port.portCode} -> $terminalName -> $queueName") mustEqual 24
        maxDesks.size.aka(s"maxDesk-> ${port.portCode} -> $terminalName -> $queueName") mustEqual 24
      }
    }

    "Queue names in min max desks by terminal and queues should be defined in Queues" in {
      for {
        port <- AirportConfigs.allPorts
        terminalName <- port.minMaxDesksByTerminalQueue.keySet
        queueName <- port.minMaxDesksByTerminalQueue(terminalName).keySet
      } yield {
        Queues.queueDisplayNames.get(queueName).aka(s"$queueName not found in Queues") mustNotEqual None
      }
    }

    "All Airport config queues must be defined in Queues" in {
      for {
        port <- AirportConfigs.allPorts
        queueName <- port.queues.values.flatten
      } yield {
        Queues.queueDisplayNames.get(queueName).aka(s"$queueName not found in Queues") mustNotEqual None
      }
    }

  }

}

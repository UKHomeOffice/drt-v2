package drt.client.components.charts


import drt.client.components.ChartJSComponent.{ChartJsData, ChartJsDataSet, ChartJsOptions, ChartJsProps}
import drt.client.components.charts.DataFormat.jsonString
import utest.{TestSuite, _}

import scala.scalajs.js
import scala.scalajs.js.JSON

object ChartPropsSpec extends TestSuite {

  def tests = Tests {

    "Given a ChartJSDataSet when I convert it to JS then I should get a JS object with the correct values" - {

      val dataSet = ChartJsDataSet(
        data = js.Array(65, 59, 80, 81, 56, 55, 40),
        label = "My First dataset",
        backgroundColor = "rgba(255,99,132,0.2)",
        borderColor = "rgba(255,99,132,1)",
        borderWidth = 1,
        hoverBackgroundColor = "rgba(255,99,132,0.4)",
        hoverBorderColor = "rgba(255,99,132,1)",
      )

      val result = dataSet.toJs

      val expected: js.Dynamic = js.JSON.parse(
        """
          |{
          |  "data": [65, 59, 80, 81, 56, 55, 40],
          |  "hoverBorderColor": "rgba(255,99,132,1)",
          |  "label": "My First dataset",
          |  "backgroundColor": "rgba(255,99,132,0.2)",
          |  "borderColor": "rgba(255,99,132,1)",
          |  "borderWidth": 1,
          |  "hoverBackgroundColor": "rgba(255,99,132,0.4)"
          |}""".stripMargin
      )

      assert(jsonString(result) == jsonString(expected))
    }

    "Given some ChartJsOptions these should be converted into valid JS options" - {
      val options = ChartJsOptions("title")
      val result = options.toJs

      val expected =
        JSON.parse(
          """
            |{
            |    "scales": {
            |        "yAxes": [
            |            {
            |                "ticks": {
            |                    "beginAtZero": true
            |                }
            |            }
            |        ],
            |        "xAxes": [
            |            {
            |                "ticks": {
            |                    "beginAtZero": true
            |                }
            |            }
            |        ]
            |    },
            |    "title": {
            |        "display": true,
            |        "text": "title"
            |    },
            |    "legend": {
            |        "display": false
            |    }
            |}""".stripMargin)

      assert(jsonString(result) == jsonString(expected))
    }

    "Given just some data and labels, then the ChartJSData apply shortcut should create the relevant datasets for me" - {
      val data = ChartJsData(Seq("one", "two", "three"), Seq(10.0, 1.0, 10.0), "title")
      val result = data.toJs

      val expected =
        JSON.parse(
          """
            |{
            |    "datasets": [
            |        {
            |            "data": [ 10, 1, 10 ],
            |            "label": "title"
            |        }
            |    ],
            |    "labels": [ "one", "two", "three" ]
            |}""".stripMargin)

      assert(jsonString(result) == jsonString(expected))
    }


    "Given a scala set of props I should get back a JS set of the same props" - {

      val props = ChartJsProps(
        ChartJsData(
          datasets = Seq(ChartJsDataSet(
            data = js.Array(65, 59, 80, 81, 56, 55, 40),
            label = "My First dataset",
            backgroundColor = "rgba(255,99,132,0.2)",
            borderColor = "rgba(255,99,132,1)",
            borderWidth = 1,
            hoverBackgroundColor = "rgba(255,99,132,0.4)",
            hoverBorderColor = "rgba(255,99,132,1)",
          )),
          labels = Option(Seq("January", "February", "March", "April", "May", "June", "July"))
        ),
        300,
        150,
        ChartJsOptions()
      )

      val result = props.toJs

      val expected =
        JSON.parse(
          """
            |{
            |    "data": {
            |        "datasets": [
            |            {
            |                "data": [ 65, 59, 80, 81, 56, 55, 40 ],
            |                "hoverBorderColor": "rgba(255,99,132,1)",
            |                "label": "My First dataset",
            |                "backgroundColor": "rgba(255,99,132,0.2)",
            |                "borderColor": "rgba(255,99,132,1)",
            |                "borderWidth": 1,
            |                "hoverBackgroundColor": "rgba(255,99,132,0.4)"
            |            }
            |        ],
            |        "labels": [
            |            "January",
            |            "February",
            |            "March",
            |            "April",
            |            "May",
            |            "June",
            |            "July"
            |        ]
            |    },
            |    "options": {},
            |    "width": 300,
            |    "height": 150
            |}""".stripMargin)

      val resultJson = jsonString(result)
      val expectedJson = jsonString(expected)
      assert(resultJson == expectedJson)
    }
  }

}


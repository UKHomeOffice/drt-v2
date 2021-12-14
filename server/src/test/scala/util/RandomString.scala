package util

import scala.util.Random

object RandomString {
  def randomStringFromCharList(length: Int, chars: Seq[Char]): String = {
    val sb = new StringBuilder
    for (i <- 1 to length) {
      val randomNum = Random.nextInt(chars.length)
      sb.append(chars(randomNum))
    }
    sb.toString
  }

  def randomAlphaNumericString(length: Int): String = {
    val chars = ('a' to 'z') ++ ('A' to 'Z') ++ ('0' to '9')
    randomStringFromCharList(length, chars)
  }

  def getNRandomString(size: Int, lengthOfString: Int): List[String] = {
    (1 to size).toList.map { _ =>
      randomAlphaNumericString(lengthOfString)
    }
  }
}

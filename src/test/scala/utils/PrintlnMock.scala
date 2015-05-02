package utils

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration._

trait PrintlnMock {
  private var lastPrinted = Promise[String]
  def printed: String = {
    val result = Await.result(lastPrinted.future, 1.second)
    lastPrinted = Promise[String]
    result
  }
  def println(value: String): Unit = {
    scala.Predef.println(value)
    lastPrinted success value
  }
}

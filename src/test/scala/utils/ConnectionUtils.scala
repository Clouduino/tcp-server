package utils

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.concurrent.duration._
import org.qirx.littlespec.assertion.Assertion
import scala.concurrent.ExecutionContext.Implicits.global
import org.qirx.littlespec.Specification
import scala.util.Try
import akka.actor.ActorSystem

trait ConnectionUtils { _: Specification =>
  private var lastReceived = Promise[Short]
  def received = {
    val result = Await.result(lastReceived.future, 1.second)
    lastReceived = Promise[Short]
    result
  }

  def connectTo(address: Connection.Address)(implicit system: ActorSystemForConnection) = {
   val connection = Connection connect address
   connection receive (lastReceived success _)
   connection
  }

  val closed =
    new Assertion[Connection] {
      def assert(c: => Connection) = {
        if (isConnectionClosed(c)) Right(success)
        else Left("Connection was not closed")
      }
    }

  val notClosed =
    new Assertion[Connection] {
      def assert(c: => Connection) = {
        if (!isConnectionClosed(c)) Right(success)
        else Left("Connection was closed")
      }
    }

  private def isConnectionClosed(c: Connection) = {
    val closed = Promise[Boolean]
    c onClosed { closed success true }
    Try(Await.result(closed.future, 1.second)).toOption getOrElse false
  }
}

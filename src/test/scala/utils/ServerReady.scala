package utils

import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.Await
import scala.concurrent.duration._

object ServerReady {
  def waitForServerToBeReady(server: ActorRef) = {
    import io.clouduino.Server.Ready
    implicit val timeout = Timeout(1.second)
    Await.result(server ? Ready, 1.second)
  }
}

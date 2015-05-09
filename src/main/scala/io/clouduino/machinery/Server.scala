package io.clouduino.machinery

import scala.concurrent.Future
import akka.actor.Props
import java.net.InetSocketAddress
import akka.actor.Stash
import io.clouduino.DefaultDataHandler
import akka.actor.Actor
import akka.io.IO
import akka.actor.ActorRef
import akka.io.Tcp
import io.clouduino.ConnectionHandler
import akka.util.ByteString
import akka.pattern.pipe
import akka.pattern.ask
import akka.actor.ActorSystem
import akka.util.Timeout
import scala.concurrent.duration._

object Server {
  def apply(ip: String, port: Int, createHandler: Client => Handler): Future[StartedServer] = {
    val system = ActorSystem(s"server-$ip:$port")
    val server = system actorOf Props(new ServerActor(ip, port, createHandler))

    implicit val timeout = Timeout(1.second)

    import system.dispatcher

    server ? Bind(ip, port) flatMap {
      case Bound =>
        Future successful StartedServer(
          stop = () =>  Future {
            system.shutdown()
            system.awaitTermination()
          }
        )

      case BindFailed =>
        Future failed BindFailedException(ip, port)
    }
  }

  case class BindFailedException(ip: String, port: Int)
    extends RuntimeException(s"Failed to bind to `$ip` at port `$port`")
}

case class Client(
  ip: String,
  send: ByteString => Unit,
  disconnect: () => Unit
)

case class Handler(
  handle: ByteString => Future[Result]
)

sealed trait Result
case object Done extends Result
case object Disconnect extends Result
case class SwitchHandler(handler: Handler) extends Result

case class StartedServer(
  stop: () => Future[Unit]
)

case class Bind(ip: String, port: Int)
case object BindFailed
case object Bound

class ServerActor(
  ip: String,
  port: Int,
  createHandler: Client => Handler
) extends Actor with Stash {

  import context.system
  import context.dispatcher

  def receive = idle

  def idle: Receive = {
    case Bind(ip, port) =>
      IO(Tcp) ! Tcp.Bind(self, new InetSocketAddress(ip, port))
      context become starting(initiator = sender)
  }

  def starting(initiator: ActorRef): Receive = {

    case Tcp.CommandFailed(_: Tcp.Bind) =>
      initiator ! BindFailed
      context stop self

    case Tcp.Bound(_) =>
      initiator ! Bound
      context become acceptingConnections
  }

  def acceptingConnections: Receive = {

    case Tcp.Connected(remoteAddress, localAddress) =>
      val connection = sender
      val client = createClient(connection, remoteAddress)
      val handler = createHandler(client)
      val connectionActor =  context actorOf Props(new ConnectionActor(handler))
      connection ! Tcp.Register(connectionActor)
  }

  def createClient(connection: ActorRef, address: InetSocketAddress) =
    Client(
      ip         = address.getAddress.getHostAddress,
      send       = { data => connection ! Tcp.Write(data) },
      disconnect = { ()   => connection ! Tcp.Close }
    )
}

class ConnectionActor(handler: Handler) extends Actor with Stash {
  import Tcp._
  import context.dispatcher

  var currentHandler = handler

  def receive = waitingForData

  def waitingForData:Receive = {
    case Received(data) =>
      currentHandler handle data pipeTo self
      context become processingData
  }

  def processingData: Receive = {
    case SwitchHandler(handler) =>
      currentHandler = handler
      context become waitingForData
      unstashAll()

    case Done =>
      context become waitingForData
      unstashAll()

    case Disconnect =>
      context stop self

    case _: Received =>
      stash()
  }
}

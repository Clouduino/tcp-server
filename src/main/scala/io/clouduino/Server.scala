package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import java.net.InetSocketAddress
import akka.util.ByteString
import java.nio.charset.StandardCharsets.US_ASCII

class Server(ip: String, port: Int, listener: ActorRef) extends Actor {

  import Tcp._
  import context.system

  IO(Tcp) ! Bind(self, new InetSocketAddress(ip, port))

  def receive = connecting(
    pendingSends = Seq.empty,
    waitingForReady = Seq.empty
  )

  private def connecting(
    pendingSends: Seq[Server.Send],
    waitingForReady: Seq[ActorRef]
  ): Receive = {

    case Bound(_) =>
      context become connected
      waitingForReady foreach (_ ! Server.Ready)
      pendingSends foreach (self ! _)

    case CommandFailed(_: Bind) =>
      listener ! Server.BindFailed(ip, port)
      context stop self

    case send: Server.Send =>
      context become connecting(pendingSends :+ send, waitingForReady)

    case Server.Ready =>
      context become connecting(pendingSends, waitingForReady :+ sender)
  }

  private var handlers = Map.empty[String, ActorRef]

  private def connected: Receive = {

    case Server.Send(id, data) =>
      handlers get id foreach (_ ! ConnectionHandler.Send(data))

    case ConnectionHandler.Activated(id, handler) =>
      handlers += (id -> handler)

    case ConnectionHandler.Deactivated(handler) =>
      handlers = handlers filterNot { case (_, registered) => registered == handler }
      context stop handler

    case Connected(_, _) =>
      val connection = sender
      val handler = context actorOf ConnectionHandler.props(connection, listener, self)
      connection ! Register(handler)
  }
}

object Server {
  def props(ip: String, port: Int, listener: ActorRef): Props =
    Props(new Server(ip, port, listener))

  case class BindFailed(ip: String, port: Int)
  case class ClientConnected(id: String)
  case class ClientDisconnected(id: String)
  case class Received(id: String, data: Short)
  case class Send(id: String, data: Byte)

  case object Accepted
  case object Rejected

  case object Ready
}

object ConnectionHandler {
  def props(connection: ActorRef, listener: ActorRef, server: ActorRef): Props =
    Props(new ConnectionHandler(connection, listener, server))

  case class Activated(id: String, handler: ActorRef)
  case class Deactivated(handler: ActorRef)
  case class Send(data: Byte)

  val MAX_ID_SIZE = 256
}

class ConnectionHandler(connection: ActorRef, listener: ActorRef, server: ActorRef) extends Actor {
  import ConnectionHandler.MAX_ID_SIZE
  import Tcp._

  def receive = waitingForId()

  private def handlePeerClose: Receive = {
    case PeerClosed => server ! ConnectionHandler.Deactivated(self)
  }

  private def waitingForId(recieved: ByteString = ByteString.empty): Receive =
    handlePeerClose orElse {

      case Received(data) =>
        val id = data take MAX_ID_SIZE takeWhile (_ != 0) decodeString US_ASCII.name
        context become validatingId(id)
        listener ! Server.ClientConnected(id)
    }

  private def validatingId(id: String): Receive =
    handlePeerClose orElse {

      case Server.Accepted =>
        context become validated(id)
        server ! ConnectionHandler.Activated(id, self)

      case Server.Rejected =>
        connection ! Close
        server ! ConnectionHandler.Deactivated(self)

    }

  private def validated(id: String): Receive =
    handlePeerClose orElse {

      case ConnectionHandler.Send(data) =>
        connection ! Write(ByteString(data))

      case Received(data) =>
        data foreach { byte =>
          listener ! Server.Received(id, convert toUnsigned byte)
        }
    }
}

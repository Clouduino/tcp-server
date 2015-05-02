package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import java.net.InetSocketAddress
import akka.util.ByteString

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

    case Server.Ready => sender ! Server.Ready

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

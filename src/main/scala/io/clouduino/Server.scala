package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.actor.Stash

object Server {
  def props(ip: String, port: Int, clientHandler: ClientHandler, eventHandler: Option[EventHandler]): Props =
    Props(new Server(ip, port, clientHandler, eventHandler))

  def props(id: String, port: Int, clientHandler: ClientHandler): Props =
    props(id, port, clientHandler, None)

  case class Send(id: String, data: Byte)
  case object Ready

  trait EventHandler {
    def bindFailed(ip: String, port: Int): Unit
    def clientConnected(id: String): Unit
    def clientDisconnected(id: String): Unit
  }
}

class Server(
  ip: String,
  port: Int,
  clientHandler: ClientHandler,
  eventHandler: Option[Server.EventHandler]
) extends Actor with Stash {

  import Tcp._
  import context.system
  import context.dispatcher

  IO(Tcp) ! Bind(self, new InetSocketAddress(ip, port))

  def connectionHandler(connection: ActorRef) = Props(
    new ConnectionHandler(connection, self, new DefaultDataHandler(clientHandler))
  )

  def receive = connecting

  private def connecting: Receive = {

    case CommandFailed(_: Bind) =>
      eventHandler foreach (_.bindFailed(ip, port))
      context stop self

    case Bound(_) =>
      context become connected
      unstashAll()

    case _ =>
      stash()
  }

  private var handlers = Map.empty[String, ActorRef]

  private def connected: Receive = {

    case Connected(_, _) =>
      val connection = sender
      val handler = context actorOf connectionHandler(connection)
      connection ! Register(handler)

    case Server.Ready =>
      sender ! Server.Ready

    case Server.Send(id, data) =>
      println("Server.send " + id + " " + data)
      println(handlers get id)
      handlers get id foreach (_ ! ConnectionHandler.Send(data))

    case ConnectionHandler.Activated(id, handler) =>
      eventHandler foreach (_ clientConnected id)
      handlers += (id -> handler)

    case ConnectionHandler.Deactivated(handler) =>
      val ids = handlers map (_.swap)
      ids get handler foreach { id =>
        handlers -= id
        eventHandler foreach (_ clientDisconnected id)
      }
      context stop handler
  }
}

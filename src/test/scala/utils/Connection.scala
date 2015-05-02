package utils

import java.net.InetSocketAddress
import akka.actor.Props
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.io.Tcp
import akka.util.ByteString
import io.clouduino.convert
import akka.io.IO

trait Connection {
  def send(message: Array[Byte]): Unit
  def send(message: Byte): Unit = send(Array(message))
  def receive(f: Short => Unit): Unit
  def close(): Unit
}

object Connection {
  type Address = (String, Int)

  def connect(address: Address): Connection = {
    val system = ActorSystem("connections")
    val client = {
      val (ip, port) = address
      system actorOf Client.props(ip, port)
    }

    val connection = new DefaultConnection(client, onClose = system.shutdown)

    pipeCallbacks(from = client, to = connection.callback, system)

    connection
  }

  private def pipeCallbacks(from: ActorRef, to: Short => Unit, system: ActorSystem): Unit =
    system actorOf CallbackActor.props(from, to)

  private class DefaultConnection(client: ActorRef, onClose: () => Unit) extends Connection {

    private var listeners = Set.empty[Short => Unit]

    def callback(data: Short): Unit = listeners foreach (_ apply data)

    def send(message: Array[Byte]): Unit = client ! Client.Send(message)
    def receive(f: Short => Unit): Unit = listeners += f
    def close(): Unit = onClose()
  }

  object CallbackActor {
    def props(client: ActorRef, callback: Short => Unit) =
      Props(new CallbackActor(client, callback))
  }

  class CallbackActor(client: ActorRef, callback: Short => Unit) extends Actor{
    import Client.{ Received, AddListener }

    client ! AddListener(self)

    def receive = {
      case Received(data) => callback(data)
    }
  }

  object Client {
    def props(ip: String, port: Int) = Props(new Client(ip, port))

    case class Send(data: Array[Byte])
    case class AddListener(listener: ActorRef)
    case class Received(data: Short)
  }

  class Client(ip: String, port: Int) extends Actor {

    import Tcp._
    import context.system

    IO(Tcp) ! Connect(new InetSocketAddress(ip, port))

    private var listeners = Set.empty[ActorRef]
    private var pendingSends = Seq.empty[Client.Send]
    private object ProcessPendingSends

    def receive = connecting

    private def connecting: Receive =
      handleNewListeners orElse {
        case CommandFailed(_: Connect) =>
          println(s"Failed to connect at `$ip` port `$port`")
          context stop self

        case Connected(_, _) =>
          val connection = sender
          connection ! Register(self)
          context become connected(connection)
          self ! ProcessPendingSends

        case send: Client.Send =>
          pendingSends :+= send
      }

    private def handleNewListeners: Receive = {
      case Client.AddListener(listener) => listeners += listener
    }

    private def connected(connection: ActorRef): Receive =
      handleNewListeners orElse {
        case ProcessPendingSends =>
          pendingSends foreach (self ! _)
          pendingSends = Seq.empty

        case Client.Send(data) =>
          connection ! Write(ByteString(data))

        case Received(data) =>
          data.foreach { byte =>
            listeners foreach (_ ! Client.Received(convert toUnsigned byte))
          }
      }
  }
}

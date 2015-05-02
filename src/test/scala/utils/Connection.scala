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
import java.nio.charset.StandardCharsets.US_ASCII
import akka.actor.Stash

trait Connection {
  def send(message: Array[Byte]): Unit
  def send(message: Int): Unit = send(Array(message.toByte))
  def send(message: String): Unit = send(message getBytes US_ASCII)
  def receive(f: Short => Unit): Unit
  def close(): Unit
  def onClosed(f: => Unit): Unit
}

case class ActorSystemForConnection(system: ActorSystem)
object ActorSystemForConnection {
  def apply(name: String): ActorSystemForConnection =
    ActorSystemForConnection(ActorSystem(name))

  implicit def fromSystem(implicit system: ActorSystem): ActorSystemForConnection =
    ActorSystemForConnection(system)
}

object Connection {

  @volatile private var count = 0

  type Address = (String, Int)

  def connect(address: Address)(implicit connectionSystem: ActorSystemForConnection): Connection = {
    import connectionSystem.system

    val client = {
      val (ip, port) = address
      count += 1
      system.actorOf(Client.props(ip, port), "client-" + count)
    }

    val connection = new DefaultConnection(client, onClose = system stop client)

    pipeCallbacks(
      from = client,
      dataTo = connection.handleDataReceived,
      closeTo = connection.handleConnectionClosed,
      system
    )

    connection
  }

  private def pipeCallbacks(from: ActorRef, dataTo: Short => Unit, closeTo: () => Unit, system: ActorSystem): Unit =
    system actorOf Props(new CallbackActor(from, dataTo, closeTo))

  private class DefaultConnection(client: ActorRef, onClose: => Unit) extends Connection {

    private var listeners = Set.empty[Short => Unit]
    private var onClosedHandlers = Set.empty[() => Unit]
    private var closed = false

    def handleDataReceived(data: Short): Unit =
      listeners foreach (_ apply data)

    def handleConnectionClosed(): Unit = {
      closed = true
      onClosedHandlers foreach (_())
      onClose
    }

    def send(message: Array[Byte]): Unit = client ! Client.Send(message)
    def receive(f: Short => Unit): Unit = listeners += f
    def close(): Unit = handleConnectionClosed()
    def onClosed(f: => Unit): Unit =
      if (closed) f
      else onClosedHandlers += {() => f }
  }

  class CallbackActor(client: ActorRef, onReceived: Short => Unit, onClose: () => Unit) extends Actor{
    client ! Client.Register(self)

    def receive = {
      case Client.Received(data) => onReceived(data)
      case Client.Closed => onClose()
    }
  }

  object Client {
    def props(ip: String, port: Int) = Props(new Client(ip, port))

    case class Send(data: Array[Byte])
    case class Register(listener: ActorRef)
    case class Received(data: Short)
    case object Closed
  }

  class Client(ip: String, port: Int) extends Actor with Stash {

    import Tcp._
    import context.system

    IO(Tcp) ! Connect(new InetSocketAddress(ip, port))

    private var listener: Option[ActorRef] = None

    def receive = connecting

    private def connecting: Receive =
      handleDisconnect orElse handleNewListeners orElse {

        case CommandFailed(_: Connect) =>
          println(s"Failed to connect at `$ip` port `$port`")
          context stop self

        case Connected(_, _) =>
          val connection = sender
          connection ! Register(self)
          context become connected(connection)
          unstashAll()

        case _: Client.Send => stash()
      }

    private def connected(connection: ActorRef): Receive =
      handleDisconnect orElse handleNewListeners orElse {

        case Client.Send(data) =>
          connection ! Write(ByteString(data))

        case Received(data) =>
          data.foreach { byte =>
            listener foreach (_ ! Client.Received(convert toUnsigned byte))
          }
      }

    private def handleNewListeners: Receive = {
      case Client.Register(newListener) => listener = Some(newListener)
    }

    private def handleDisconnect: Receive = {
      case _: ConnectionClosed =>
        listener foreach (_ ! Client.Closed)
        context stop self
    }
  }
}

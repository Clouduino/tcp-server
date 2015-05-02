package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString
import java.nio.charset.StandardCharsets.US_ASCII
import scala.concurrent.duration._
import akka.actor.Cancellable

object ConnectionHandler {
  def props(connection: ActorRef, listener: ActorRef, server: ActorRef): Props =
    Props(new ConnectionHandler(connection, listener, server))

  case class Activated(id: String, handler: ActorRef)
  case class Deactivated(handler: ActorRef)
  case class Send(data: Byte)
}

class ConnectionHandler(connection: ActorRef, listener: ActorRef, server: ActorRef) extends Actor {
  import Tcp._
  import context.dispatcher
  import Protocol._

  object TimeOutWaitingForId

  def receive = waitingForId()

  private def handlePeerClose: Receive = {
    case PeerClosed =>
      server ! ConnectionHandler.Deactivated(self)
  }

  private def waitingForId(
    previouslyReceived: ByteString = ByteString.empty,
    cancelWaitTimeout: Option[Cancellable] = None
  ): Receive =
    handlePeerClose orElse {

      case Received(data) if previouslyReceived.size + data.size > MAX_ID_SIZE =>
        cancelWaitTimeout foreach (_.cancel())
        closeConnection(ID_NOT_RECEIVED)

      case Received(data) =>
        cancelWaitTimeout foreach (_.cancel())

        val (idPart, remaining) = data span (_ != ID_TERMINATOR)

        remaining.headOption match {
          case Some(ID_TERMINATOR) =>
            val id = {
              val idBytes = previouslyReceived ++ idPart
              idBytes decodeString US_ASCII.name
            }

            context become validatingId(id, remaining drop 1)

            listener ! Server.ClientConnected(id)

          case Some(_) =>
            sys error "This can not happen, we do a span until the terminator."

          case None =>
            val cancellable = context.system.scheduler
              .scheduleOnce(
                delay = 500.milliseconds,
                receiver = self,
                message = TimeOutWaitingForId
              )

            context become waitingForId(previouslyReceived ++ data, Some(cancellable))
        }

      case TimeOutWaitingForId => closeConnection(ID_NOT_RECEIVED)
    }

  private def validatingId(id: String, pendingData: ByteString): Receive =
    handlePeerClose orElse {

      case Server.Accepted =>
        context become validated(id)
        server ! ConnectionHandler.Activated(id, self)
        self ! Received(pendingData)

      case Server.Rejected => closeConnection(ID_NOT_ACCEPTED)

      case Received(data) =>
        context become validatingId(id, pendingData ++ data)
    }

  private def validated(id: String): Receive =
    handlePeerClose orElse {

      case ConnectionHandler.Send(data) =>
        send(data)

      case Received(data) if data.size > MAX_MESSAGE_COUNT =>
        closeConnection(DATA_NOT_ACCEPTED)

      case Received(data) =>
        data foreach {
          case byte if Protocol isReserved byte =>
            closeConnection(DATA_NOT_ACCEPTED)

          case byte =>
            listener ! Server.Received(id, convert toUnsigned byte)
        }
    }

  private def send(data: Byte) =
    connection ! Write(ByteString(data))

  private def closeConnection(message: Byte) = {
    send(message)
    server ! ConnectionHandler.Deactivated(self)
  }
}

package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.util.ByteString
import java.nio.charset.StandardCharsets.US_ASCII
import scala.concurrent.duration._
import akka.actor.Cancellable
import akka.actor.Stash

object ConnectionHandler {
  def props(connection: ActorRef, server: ActorRef): Props =
    Props(new ConnectionHandler(connection, server))

  case class Activated(id: String, handler: ActorRef)
  case class Deactivated(handler: ActorRef)
  case class Send(data: Byte)
}

class ConnectionHandler(
  connection : ActorRef,
  server     : ActorRef
) extends Actor with Stash {

  import Tcp._
  import context.dispatcher
  import context.system
  import Protocol._
  private val scheduler = system.scheduler

  private object TimeOutWaitingForId
  private case class CloseConnection(id: Option[String], message: Option[Byte])

  def receive = states.waitingForId()

  private object states {

    def waitingForId(
      previouslyReceived: ByteString = ByteString.empty,
      cancelWaitTimeout: Option[Cancellable] = None
    ): Receive =
      handlePeerClose(id = None) orElse {

        case Received(data) =>
          handleDataReceived(previouslyReceived ++ data, cancelWaitTimeout)

        case TimeOutWaitingForId =>
          closeConnection(id = None, ID_NOT_RECEIVED)
      }

    def validatingId(id: String): Receive =
      handlePeerClose(Some(id)) orElse {

        case Server.Accepted =>
          context become validated(id)
          server ! ConnectionHandler.Activated(id, self)
          unstashAll()

        case Server.Rejected =>
          closeConnection(id, ID_NOT_ACCEPTED)

        case _ => stash()
      }

    def validated(id: String): Receive =
      handlePeerClose(Some(id)) orElse {

        case ConnectionHandler.Send(data) =>
          send(data)

        case Received(data) if data.size > MAX_MESSAGE_COUNT =>
          closeConnection(id, DATA_NOT_ACCEPTED)

        case Received(data) =>
          data foreach {
            case byte if Protocol isReserved byte =>
              closeConnection(id, DATA_NOT_ACCEPTED)

            case byte =>
              server ! Server.Received(id, convert toUnsigned byte)
          }
      }

    def closing: Receive = {
      case CloseConnection(id, message) =>
        message foreach send
        id foreach (server ! Server.ClientDisconnected(_))
        server ! ConnectionHandler.Deactivated(self)

      case _ => // ignore any other messages
    }

    private def handlePeerClose(id: Option[String]): Receive = {
      case _: ConnectionClosed => closeConnection(id, message = None)
    }
  }

  private def handleDataReceived(data: ByteString, cancelWaitTimeout: Option[Cancellable]) = {
    cancelWaitTimeout foreach (_.cancel())

    if (data.size > MAX_ID_SIZE) closeConnection(id = None, ID_NOT_RECEIVED)
    else handleIdData(data)
  }

  private def handleIdData(data: ByteString) = {

    val (id, remaining) = data span (_ != ID_TERMINATOR)

    if (remaining.nonEmpty) validateId(id, remaining drop 1)
    else waitForMoreData(data)
  }

  private def waitForMoreData(data: ByteString) = {
    val cancellable = scheduler.scheduleOnce(500.millis, self, TimeOutWaitingForId)

    context become states.waitingForId(previouslyReceived = data, Some(cancellable))
  }

  private def validateId(data: ByteString, remaining: ByteString) = {
    val id = data decodeString US_ASCII.name

    context become states.validatingId(id)

    self ! Received(remaining)
    server ! Server.ClientConnected(id)
  }

  private def send(data: Byte) =
    connection ! Write(ByteString(data))

  private def closeConnection(id: String, message: Byte): Unit =
    closeConnection(Some(id), Some(message))

  private def closeConnection(id: Option[String], message: Byte): Unit =
    closeConnection(id, Some(message))

  private def closeConnection(id: Option[String], message: Option[Byte]): Unit = {
    unstashAll()
    context become states.closing
    self ! CloseConnection(id, message)
  }
}

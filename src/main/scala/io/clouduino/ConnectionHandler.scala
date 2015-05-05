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
import akka.pattern.pipe
import scala.reflect.ClassTag
import scala.concurrent.Future
import akka.actor.Status

object ConnectionHandler {
  case class Activated(id: String, handler: ActorRef)
  case class Deactivated(handler: ActorRef)
  case class Send(data: Byte)
}

class ConnectionHandler(
  connection   : ActorRef,
  server       : ActorRef,
  dataHhandler : DataHandler
) extends Actor with Stash {

  import Tcp._
  import context.dispatcher
  import context.system
  import Protocol._

  val scheduler = system.scheduler

  def receive = waitingForId()

  def waitingForId(
    previouslyReceived: ByteString = ByteString.empty,
    cancelWaitTimeout: Option[Cancellable] = None
  ): Receive =
    handlePeerClose(id = None) orElse {

      case Received(data) =>
        cancelWaitTimeout foreach (_.cancel())
        withHandler(
          execute = _ extractId (previouslyReceived ++ data),
          handler  = handleExtractIdResult
        )

      case TimeOutWaitingForId =>
        closeConnection(id = None, ID_NOT_RECEIVED)
    }

  def handleExtractIdResult: ExtractIdResult => Unit = {

    case Extracted(id, remaining) =>
      self ! Received(remaining)
      server ! ConnectionHandler.Activated(id, self)
      changeStateTo(waitingForData(id))

    case Invalid(id) => closeConnection(id, ID_NOT_ACCEPTED)
    case TooMuchData => closeConnection(id = None, ID_NOT_RECEIVED)

    case NotEnoughData(data) =>
      val cancellable = scheduler.scheduleOnce(500.millis, self, TimeOutWaitingForId)
      changeStateTo(waitingForId(previouslyReceived = data, Some(cancellable)))
  }

  def waitingForData(id: String): Receive =
    handlePeerClose(Some(id)) orElse {

      case ConnectionHandler.Send(data) =>
        send(data)

      case Received(data) =>
        withHandler(
          execute = _.handleData(id, data),
          handler = handleDataResult(id)
        )
    }

  def handleDataResult(id: String): HandleDataResult => Unit = {
    case DataNotAccepted => closeConnection(id, DATA_NOT_ACCEPTED)
    case TooMuchData     => closeConnection(id, DATA_NOT_ACCEPTED)
    case DataAccepted    => changeStateTo(waitingForData(id))
  }

  def closing: Receive = {
    case CloseConnection(id, message) =>
      message foreach send
      server ! ConnectionHandler.Deactivated(self)

    case _ => // ignore any other messages
  }

  def handlePeerClose(id: Option[String]): Receive = {
    case _: ConnectionClosed => closeConnection(id, message = None)
  }

  def send(data: Byte) = connection ! Write(ByteString(data))

  def closeConnection(id: String, message: Byte): Unit =
    closeConnection(Some(id), Some(message))

  def closeConnection(id: Option[String], message: Byte): Unit =
    closeConnection(id, Some(message))

  def closeConnection(id: Option[String], message: Option[Byte]): Unit = {
    changeStateTo(closing)
    self ! CloseConnection(id, message)
  }

  def changeStateTo(newState: Receive): Unit = {
    context become newState
    unstashAll()
  }

  def withHandler[T : ClassTag](execute: DataHandler => Future[T], handler: T => Unit): Unit = {
    changeStateTo {
      case t: T => handler(t)
      case _: Received => stash()
    }
    execute(dataHhandler) pipeTo self
  }

  object TimeOutWaitingForId
  case class CloseConnection(id: Option[String], message: Option[Byte])

  override def unhandled(m: Any) = {
    println("Unhandled " + m)
    super.unhandled(m)
    sys error s"Unhandled: $m"
  }
}

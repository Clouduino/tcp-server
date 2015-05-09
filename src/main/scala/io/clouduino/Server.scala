package io.clouduino

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.io.Tcp
import akka.io.IO
import java.net.InetSocketAddress
import akka.util.ByteString
import akka.actor.Stash
import io.clouduino.machinery.StartedServer
import io.clouduino.machinery.Handler
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import io.clouduino.machinery.Done
import io.clouduino.machinery.SwitchHandler
import io.clouduino.machinery.Disconnect
import io.clouduino.machinery.Client
import io.clouduino.machinery.ProgramBuilder
import io.clouduino.machinery.Result
import akka.actor.ActorSystem

object Server {

  val data: ByteString = ???
  val byte: Byte = ???
  val id: String = ???
  val remaining: ByteString = ???

  case class SendData(data: ByteString)
  case class Header(data: ByteString)
  case object NoHeader
  case class Data(data : ByteString)
  // - stream of raw bytes
  // - stream of translated protocol
  // - stream of instructions
/*
val data: ByteString = ???

sealed trait Handshake
case class HandshakeComplete(id)
case object HandshakeFailed

--> [data, data, ...] --\
                    transformation
                          \--> [Handshake] --> [HandleData]


*/
  case class Stream[A](a: A *)

  implicit class StreamOperations[A](s: Stream[A]) {
    def consumeDuring[B](f: A => B): Stream[B] = ???
    def validateUsing[B](f: A => B): Stream[B] = ???
    def in[B](f: A => B): Stream[B] = ???
  }
  implicit class StreamFactory[A](a: A) {
    def and[B](b: B)(implicit append: Append[A, B]): Stream[append.Out] = ???
  }
  implicit class FutureOperations[A](f: Future[A]) {
    def in[B](f: A => B): Future[B] = ???
    def and[B](b: B): Future[B] = ???
  }

  trait Append[A, B] {
    type Out
  }
  trait LowerPriorityAppend {
    implicit def any[A <: C, B <: C, C]: Append[A, B] { type Out = C } = ???
  }
  object Append extends LowerPriorityAppend {
    implicit def stream[A <: C, B <: C, C]:Append[Stream[A], B] { type Out = C } = ???
  }

  sealed trait Response
  case class Write(bytes: ByteString) extends Response
  case object CloseConnection extends Response

  sealed trait Handshake
  case class HandshakeComplete(id: String) extends Handshake
  case object HandshakeFailed extends Handshake

  sealed trait Command
  case class RegisterClient(id: String) extends Command
  case class ProcessData(data: ByteString) extends Command

  trait ToByteString[T]
  object ToByteString {
    implicit val byteString: ToByteString[ByteString] = ???
    implicit val handshakeFailed: ToByteString[HandshakeFailed.type] = ???
    implicit val invalidId: ToByteString[InvalidId.type] = ???
    implicit val dataRejected: ToByteString[DataRejected.type] = ???
  }

  sealed trait IdValidated
  case object InvalidId extends IdValidated
  case object IdValid extends IdValidated

  sealed trait DataValidated
  case class DataAccepted(data: ByteString) extends DataValidated
  case object DataRejected extends DataValidated

  sealed trait Request[T]
  case class ValidateId(id: String) extends Request[IdValidated]

  sealed trait Instruction
  case class SwitchTo[A, T](handler: Stream[A] => T)

  val handshake: ByteString => Handshake = ???
  def respond[T](value: T)(implicit toBytes: ToByteString[T]): Write = ???
  val validateId: String => Future[IdValidated] = ???
  val tellService: Command => Future[Unit] = ???
  def askService[T]: Request[T] => Future[T] = ???
  val dataValidator: ByteString => DataValidated = ???

  def startHandshake(clientData: Stream[ByteString]) =
    clientData consumeDuring handshake in {
      case HandshakeComplete(id) => performValidation(id)
      case HandshakeFailed       => respond(HandshakeFailed) and CloseConnection
    }

  def performValidation(id: String) =
    askService(ValidateId(id)) in {
      case IdValid   => tellService(RegisterClient(id)) and SwitchTo(handlingData)
      case InvalidId => respond(InvalidId) and CloseConnection
    }

  def handlingData(clientData: Stream[ByteString]) =
    clientData validateUsing dataValidator in {
      case DataAccepted(data) => tellService(ProcessData(data))
      case DataRejected => respond(DataRejected) and CloseConnection
    }

  Stream(data, data, data)
    Stream(byte, byte, byte)
      Stream(Header(data), Data(data), Data(data))
        Stream(HandshakeComplete(id), DataAccepted(data), DataNotAccepted(data))
          Stream(RegisterClient(id), ProcessData(data), SendData(data), CloseConnection)
            /* in  */ Stream(RegisterClient(id), ProcessData(data))
            /* out */ Stream(SendData(data), CloseConnection)
          Stream(Invalid(id))
            /* in  */ Stream()
            /* out */ Stream(SendData(data), CloseConnection)
        Stream(/*NotEnoughData(data), */Extracted(id, remaining), DataAccepted(data), DataNotAccepted(data))
      Stream(/*TooMuchData*/NoHeader)
        Stream(HandshakeFailed)
          /* out */ Stream(SendData(data), CloseConnection)


  class Handlers(handler: DataHandler, client: Client)(implicit ec: ExecutionContext) {

    val programBuilder = ProgramBuilder[Result]
    import programBuilder._

    def extractIdHandler(previous: ByteString): Handler = Handler { data =>
      for {
        extractIdResult <- (handler extractId (previous ++ data)).asPart
        result <-
          switch(extractIdResult) {
            case Extracted(id, remaining) =>
              val newHandler = (???):Handler //handleDataHandler(id)
              (newHandler handle remaining).asPart map {
                case Done  => SwitchHandler(newHandler)
                case other @ (Disconnect | _: SwitchHandler) => other
              }
            case Invalid(id) =>
              disconnectWithMessage(client, Protocol.ID_NOT_ACCEPTED)
            case TooMuchData =>
              disconnectWithMessage(client, Protocol.ID_NOT_RECEIVED)
            case NotEnoughData(data) =>
              Return(SwitchHandler(extractIdHandler(previous = data)))
          }
      } yield result
    }
/*
    def handleDataHandler(id: String): Handler = Handler { data =>
      for {
        handleDataResult <- handler.handleData(id, data).asPart
        result  <-
          switch(handleDataResult) {
            case DataAccepted(_) => Return(Done)
            case DataNotAccepted(_) | TooMuchData =>
              disconnectWithMessage(client, Protocol.DATA_NOT_ACCEPTED)
          }
      } yield result
    }
*/
    private def disconnectWithMessage(client: Client, message: Byte) = {
      client.send(ByteString(message))
      Return(Disconnect)
    }

    private def switch[T](value: T)(f: T => Part[Result]) = f(value)
  }

  case class StartedServer(
    stop: () => Unit,
    send: (String, ByteString) => Unit
  )

  def apply(ip: String, port: Int, clientHandler: ClientHandler, eventHandler: Option[EventHandler])(implicit ec: ExecutionContext): Future[StartedServer] = {
    val system = ActorSystem("client-id-handler")
    val handler = new DefaultDataHandler(clientHandler)
    machinery.Server(ip, port, { client =>
      val handlers = new Handlers(handler, client)
      handlers.extractIdHandler(previous = ByteString.empty)
    })
    ???
  }

  def props(ip: String, port: Int, clientHandler: ClientHandler, eventHandler: Option[EventHandler]): Props =
    ??? //Props(new Server(ip, port, clientHandler, eventHandler))

  def props(id: String, port: Int, clientHandler: ClientHandler): Props =
    props(id, port, clientHandler, None)

  case class Send(id: String, data: Byte)
  case object Ready

  trait EventHandler {
    def clientConnected(id: String): Unit
    def clientDisconnected(id: String): Unit
  }
}
/*
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
*/

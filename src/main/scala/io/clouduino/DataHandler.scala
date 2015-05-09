package io.clouduino

import akka.util.ByteString
import java.nio.charset.StandardCharsets.US_ASCII
import scala.concurrent.Future
import scala.concurrent.ExecutionContext
import io.clouduino.machinery.ProgramBuilder

trait DataHandler {
  def extractId(data: ByteString): Future[ExtractIdResult]

  def handleData(id: String, data: ByteString): Future[HandleDataResult]
}

import Protocol._

class DefaultDataHandler(handler: ClientHandler)(implicit ec: ExecutionContext) extends DataHandler {

  def extractId(data: ByteString): Future[ExtractIdResult] = {
    val programBuilder = ProgramBuilder[ExtractIdResult]
    import programBuilder._

    for {
      _               <- data.size > MAX_ID_SIZE ifTrue Return(TooMuchData)
      (idBytes, rest) =  data span (_ != ID_TERMINATOR)
      _               <- rest.isEmpty ifTrue Return(NotEnoughData(data))
      id              =  idBytes decodeString US_ASCII.name
      _               <- handler isValidId id ifFalse Return(Invalid(id))
    } yield Extracted(id, rest drop 1)
  }

  def handleData(id: String, data: ByteString): Future[HandleDataResult] = {
    val programBuilder = ProgramBuilder[HandleDataResult]
    import programBuilder._

    for {
      _          <- data.size > MAX_MESSAGE_COUNT ifTrue Return(TooMuchData)
      _          <- data exists Protocol.isReserved ifTrue Return(DataNotAccepted(data))
      handleData =  convert.toUnsigned andThen (handler.handleData(id, _))
      _          <- Future.traverse(data)(handleData).asPart
    } yield DataAccepted(data)
  }
}

sealed trait ExtractIdResult
case class Extracted(id: String, remaining: ByteString) extends ExtractIdResult
case class Invalid(id: String) extends ExtractIdResult
case class NotEnoughData(data: ByteString) extends ExtractIdResult

sealed trait HandleDataResult
case class DataAccepted(data: ByteString) extends HandleDataResult
case class DataNotAccepted(data: ByteString) extends HandleDataResult

case object TooMuchData extends ExtractIdResult with HandleDataResult

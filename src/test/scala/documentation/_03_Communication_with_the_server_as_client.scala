package documentation

import utils.Documentation
import utils.ServerReady.waitForServerToBeReady
import java.nio.charset.StandardCharsets.US_ASCII
import io.clouduino.Protocol
import io.clouduino.convert
import io.clouduino.ConnectionHandler
import scala.concurrent.duration._

object _03_Communication_with_the_server_as_client extends Documentation {

  private def unsigned(byte: Byte) = convert toUnsigned byte
  val ID_TERMINATOR     = unsigned(Protocol.ID_TERMINATOR)
  val ID_NOT_RECEIVED   = unsigned(Protocol.ID_NOT_RECEIVED)
  val ID_NOT_ACCEPTED   = unsigned(Protocol.ID_NOT_ACCEPTED)
  val DATA_NOT_ACCEPTED = unsigned(Protocol.DATA_NOT_ACCEPTED)

  import Protocol.{MAX_ID_SIZE, MAX_MESSAGE_COUNT}

  withActorSystem("connection-system") { implicit connectionSystem =>

    """|# Client -> server communication
       |
       |For this example we have use the following server setup
       | """.stripMargin -- new Example {
         import akka.actor.Actor
         import akka.actor.ActorSystem
         import akka.actor.Props
         import io.clouduino.Server

         class Listener extends Actor {

           import Server._

           def receive = {
             case ClientConnected(id) =>
               if (id == "test") sender ! Accepted
               else sender ! Rejected
           }
         }

         val system = ActorSystem("server-client-communication")
         val listener = system actorOf Props(new Listener)
         val server = system actorOf Server.props("localhost", 8888, listener)

       } chain { ServerInstance =>

       import ServerInstance.system
       import ServerInstance.server

       waitForServerToBeReady(server)

       def newConnection = connectTo("localhost" -> 8888)

    s"""|As you can see, we only accept id's that have the value "test".
        |
        |After establishing a connection, we need to send the id to the server. If we fail to
        |terminate it with a `$ID_TERMINATOR` within a certain amount of time we will receive a
        |`$ID_NOT_RECEIVED` (id not received message). After that, the connection is closed by the
        |server.
        | """.stripMargin - example {
          val connection = newConnection
          val message = "test" getBytes US_ASCII

          connection send message

          received is 240
          connection was closed
        }

    s"""|If we try to send an id longer than `$MAX_ID_SIZE` bytes, we will also result in a `$ID_NOT_RECEIVED`
        |(id not received message).
        | """.stripMargin - example {
          val connection = newConnection
          val part1 = (1 to 255).toArray map (_.toByte)
          val part2 = (1 to 255).toArray map (_.toByte)

          connection send part1
          wait(20.milliseconds)
          connection send part2

          received is 240
          connection was closed
        }

    s"""|If we supply the wrong id, the server responds with `$ID_NOT_ACCEPTED` (id not accepted message).
        | """.stripMargin - example {
          val connection = newConnection
          val id = "not test" getBytes US_ASCII
          val message = id + 0

          connection send message

          received is 241
          connection was closed
        }

    """|In some cases the id is being sent in chunks, the server can handle this.
       |""".stripMargin - sideEffectExample {
         val connection = newConnection

         connection send "t"
         wait(20.milliseconds)
         connection send "es"
         wait(20.milliseconds)
         connection send "t"
         wait(20.milliseconds)
         connection send 0

         connection was notClosed
         connection.close()
       }

    s"""|Trying to send a reserved character results in a `$DATA_NOT_ACCEPTED` (data not accepted message)
        | """.stripMargin - sideEffectExample {
          val reserved = 0 +: (0xF0 to 0xFF)

          reserved foreach { message =>

            val connection = newConnection
            connection send ("test" getBytes US_ASCII) + 0
            connection send message

            received is 242
            connection was closed
          }
        }

    s"""|Sending more than $MAX_MESSAGE_COUNT messages in one chunk resuls is a `$DATA_NOT_ACCEPTED` (data not
        |accepted message)""".stripMargin - example {
         val connection = newConnection

         connection send "test"
         connection send 0
         wait(20.milliseconds)

         val longMessage = (300 to 600).mkString getBytes US_ASCII

         connection send longMessage

         received is 242
         connection was closed
      }

    s"""|It's possible to send data and the id in one go
        | """.stripMargin - sideEffectExample {
          val connection = newConnection

          val id = ("test" getBytes US_ASCII) + 0
          val data = Array[Byte](1, 2, 3)
          val message = id ++ data

          connection send message

          connection was notClosed
          connection.close()
        }

        "preventing brute force" - {

        }

        "rate limit" - {

        }

         "Closing server" - {
           system.shutdown()
           system.awaitTermination()
           success
         }
       }

  }

  def wait(duration: Duration) =
    Thread sleep duration.toMillis

}

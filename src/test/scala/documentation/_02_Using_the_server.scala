package documentation

import utils.Documentation
import utils.Connection
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.ActorRef

object _02_Using_the_server extends Documentation {

"""|# Server
   |
   |The server is an represented by an Akka actor that is bound to an IP address and a port. To
   |use it you need obtain a reference to server the actor.
   |
   |Before we can do that, we need to first get a reference to an actor system.
   | """.stripMargin -- new Example {
     import akka.actor.ActorSystem

     val system = ActorSystem("clouduino")
   } chain { System =>

     var lastPrinted = Promise[String]
     def printed: String = {
       scala.Predef.println("Waiting for last printed")
       val result = Await.result(lastPrinted.future, 1.second)
       scala.Predef.println("Got " + result)
       scala.Predef.println("Clearing last printed")
       lastPrinted = Promise[String]
       result
     }
     def println(value: String): Unit = {
       scala.Predef.println(value)
       scala.Predef.println("Completing promise")
       lastPrinted success value
     }

     import System.system

"""|The next thing we need a way of handling client connections and their data.
   | """.stripMargin -- new Example {
     import akka.actor.Actor
     import akka.actor.Props
     import io.clouduino.Listener
     import io.clouduino.Server

     class ConnectionListener extends Actor {

       import Server.{BindFailed, ClientConnected, Received, ClientDisconnected}
       import Server.{Accepted, Rejected}

       def receive = {
         case BindFailed(ip, port) =>
           println(s"Server failed to bind to `$ip` at port `$port`")

         case ClientConnected(id) =>
           // You can use the id to check if you want to accept the client
           if (id == "let me in") {
             println(s"Accepted client with id `$id`")
             sender ! Accepted
           }
           else {
             println(s"Rejected client with id `$id`")
             sender ! Rejected
           }

         case Received(id, data) =>
           // The data is a simple unsigned byte (represented as a signed short)
           println(s"Received `$data` from the client with id `$id`")

         case ClientDisconnected(id) =>
           println(s"Client with id `$id` disconnected")
       }
     }

     object ConnectionListener {
       def props = Props(new ConnectionListener)
     }

     val listener = system.actorOf(ConnectionListener.props)

   } chain { Listener =>

   import Listener.listener

"""|Now that we have listener to deal with incomming connections, we can create the server.
   | """.stripMargin -- new Example {
     import io.clouduino.Server

     val server = system.actorOf(Server.props(ip = "localhost", port = 8888, listener = listener))

   } chain { ServerInstance =>

   import ServerInstance.server

   waitForServerToBeReady(server)

"""|The server is now ready to handle incoming connections. For more details about communicating
   |with the server from a client, checkout the part of the documentation that covers that.
   | """.stripMargin - {

   import java.nio.charset.StandardCharsets.US_ASCII

   var lastReceived = Promise[Short]
   def received = {
     val result = Await.result(lastReceived.future, 1.second)
     lastReceived = Promise[Short]
     result
   }
   val connection = Connection connect ("localhost", 8888)
   connection receive (lastReceived success _)


"""|For this example we have already created a connection. The first data we send is an string of
   |bytes followed by a null byte.
   | """.stripMargin - example {
     val NULL = 0.toByte
     val id = "let me in" getBytes US_ASCII

     val message = id :+ NULL

     connection send message

     printed is "Accepted client with id `let me in`"
   }

"""|Now that we have a connected client, we can send messages from it. Messages are one byte long.
   | """.stripMargin - example {

     val message = 234.toByte

     connection send message

     printed is "Received `234` from the client with id `let me in`"
   }

"""|We can also send messages to a certain client. For more details about communicating
   |with the server from the application, checkout the part of the documentation that
   |covers that.
   | """.stripMargin - example {
     import io.clouduino.Server.Send

     server ! Send("let me in", 210.toByte)

     received is 210
   }

"""|Make sure to shutdown the system when done
   | """.stripMargin - sideEffectExample {
     system.shutdown()
     system.awaitTermination()
   }

   }}}} // We nested some chained examples without indentation to improve readability

  private def waitForServerToBeReady(server: ActorRef) = {
    import io.clouduino.Server.Ready
     implicit val timeout = Timeout(1.second)
     Await.result(server ? Ready, 1.second)
  }
}

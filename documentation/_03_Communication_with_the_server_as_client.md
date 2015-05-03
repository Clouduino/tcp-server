**This documentation is generated from `documentation._03_Communication_with_the_server_as_client`**

---
# Client -> server communication

For this example we have use the following server setup
 
```scala
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
val server = system actorOf Server.props("localhost", 9999, listener)

```
As you can see, we only accept id's that have the value "test".

After establishing a connection, we need to send the id to the server. If we fail to
terminate it with a `0` within a certain amount of time we will receive a
`240` (id not received message). After that, the connection is closed by the
server.
 
```scala
val connection = newConnection
val message = "test" getBytes US_ASCII

connection send message

received is 240
connection was closed
```
If we try to send an id longer than `256` bytes, we will also receive a `240`
(id not received message).
 
```scala
val connection = newConnection
val part1 = (1 to 255).toArray map (_.toByte)
val part2 = (1 to 255).toArray map (_.toByte)

connection send part1
wait(20.milliseconds)
connection send part2

received is 240
connection was closed
```
If we supply the wrong id, the server responds with `241` (id not accepted message).
 
```scala
val connection = newConnection
val id = "not test" getBytes US_ASCII
val message = id + 0

connection send message

received is 241
connection was closed
```
In some cases the id is being sent in chunks, the server can handle this.

```scala
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
```
Trying to send a reserved character results in a `242` (data not accepted message)
 
```scala
val reserved = 0 +: (0xF0 to 0xFF)

reserved foreach { message =>

  val connection = newConnection
  connection send ("test" getBytes US_ASCII) + 0
  connection send message

  received is 242
  connection was closed
}
```
Sending more than `256` messages in one chunk resuls is a `242` (data not
accepted message)
```scala
val connection = newConnection

connection send "test"
connection send 0
wait(20.milliseconds)

val longMessage = (300 to 600).mkString getBytes US_ASCII

connection send longMessage

received is 242
connection was closed
```
It's possible to send data and the id in one go
 
```scala
val connection = newConnection

val id = ("test" getBytes US_ASCII) + 0
val data = Array[Byte](1, 2, 3)
val message = id ++ data

connection send message

connection was notClosed
connection.close()
```
preventing brute force
> Pending: TODO

rate limit
> Pending: TODO


Shutting down system

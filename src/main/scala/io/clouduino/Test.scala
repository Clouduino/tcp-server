package io.clouduino

import akka.stream.scaladsl.Flow
import akka.util.ByteString
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.Concat
import akka.stream.scaladsl.Source
import akka.actor.ActorSystem
import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.Sink
import akka.stream.stage.PushPullStage
import akka.stream.stage.Context
import akka.stream.stage.SyncDirective
import akka.stream.stage.DetachedStage
import akka.stream.stage.DetachedContext
import scala.concurrent.duration._
import akka.stream.scaladsl.ZipWith
import akka.stream.FanInShape.Init
import akka.stream.FanInShape.Name
import akka.stream.FanInShape2
import akka.stream.Inlet
import akka.stream.scaladsl.FlexiMerge
import akka.stream.OperationAttributes
import akka.stream.FanInShape
import scala.concurrent.Promise

object Test {

  implicit val system = ActorSystem("test")
  implicit val materializer = ActorFlowMaterializer()

  def echo[T](implicit toString: ToString[T]) =
    Flow[T].map { b => println(toString(b)); b }

  class ToString[A](f: A => String) extends (A => String) {
    def apply(a: A) = f(a)
  }
  trait LowerPriorityToString {
//    implicit def any[A] = new ToString[A](_.toString)
  }
  object ToString extends LowerPriorityToString {
    implicit val ByteString = new ToString[ByteString](_.utf8String)
  }

  sealed trait Tick
  case object Tick extends Tick

  val unit = ()

  val count =
  Source.repeat(unit).via(Flow[Unit].scan(0)((x, _) => x + 1))

  def slowed[T] =
    Flow[T, T]() { implicit builder =>
      import FlowGraph.Implicits._

      val ticker = Source[Tick](1.second, 1.second, Tick)
      val zipped = builder.add(ZipWith[Tick, T, T]((_, t) => t))

      ticker ~> zipped.in0

      (zipped.in1, zipped.out)
    }

  val addCount = Flow() { implicit builder =>
    import FlowGraph.Implicits._

    val append = builder.add(ZipWith[Int, ByteString, ByteString]((count, b) => b ++ ByteString(count.toString)))

    count ~> append.in0

    (append.in1, append.out)
  }

  val start = Source.lazyEmpty[ByteString]
  val stop = Source.lazyEmpty[Unit]

  val g =
    FlowGraph.closed(start, stop)(_ -> _) { implicit builder => (start, stop) =>
      import FlowGraph.Implicits._

      val startMessage = builder.add(Concat[ByteString])
      val concat = builder.add(Concat[ByteString])

      val connection = builder.add(Flow[ByteString])

      val stoppable = builder.add(new Stoppable[ByteString])

      val fakeElement = Source.single(ByteString("Fake Element"))


            stop ~> stoppable.stop

               start   ~> startMessage
           fakeElement ~> startMessage

          startMessage ~>  echo[ByteString]  ~> concat
            connection ~>  echo[ByteString]  ~> concat ~> stoppable.in

            connection <~ slowed[ByteString] <~ addCount <~ stoppable.out
    }

  class StoppableShape[A](init: Init[A] = Name("Stoppable"))
    extends FanInShape[A](init) {

    val stop = newInlet[Unit]("stop")
    val in = newInlet[A]("in")

    protected def construct(init: Init[A]) = new StoppableShape(init)
  }

  class Stoppable[A] extends FlexiMerge[A, StoppableShape[A]](
    new StoppableShape[A], OperationAttributes.name("Stoppable")
  ) {
    import FlexiMerge._

    def createMergeLogic(p: PortT) = new MergeLogic[A] {
      def initialState = State[A](Read(p.in)) { (ctx, _, element) =>
        ctx.emit(element)
        SameState
      }

      override def initialCompletionHandling = eagerClose
    }
  }

  def go = g.run()

  val list = Source(1 to 10)

  def test = list.via(slowed)
    .map(_.toString)
    .map(ByteString(_))
    .via(addCount)
    .via(echo)
    .runForeach(_ => ())

}

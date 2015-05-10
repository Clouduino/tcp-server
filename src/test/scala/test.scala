import org.qirx.littlespec.Specification
import scala.concurrent.Await
import scala.concurrent.duration._

object test extends Specification {

  "go" - {
    import scala.concurrent.ExecutionContext.Implicits.global

    val (start, stop) = io.clouduino.Test.go

    val unit = ()

    println("starting")
    start.success(unit)
    start.future.foreach { _ =>
      println("started")
    }

    io.clouduino.Test.system.scheduler.scheduleOnce(5.seconds)(stop.success(unit))

    stop.future.onComplete { _ =>
      println("stopped")
    }

    Await.result(stop.future, 6.seconds)
  }

  "test" - {
    val x = io.clouduino.Test.test

    Await.result(x, 15.seconds)
    success
  }

}

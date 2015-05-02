package utils

import akka.actor.ActorSystem
import org.qirx.littlespec.Specification

trait WithActorSystem { _: Specification =>

  def withActorSystem[T](name: String)(code: ActorSystem => FragmentBody):FragmentBody = {
    val system = ActorSystem(name)

    code(system)

    "Shutting down system" - {
      system.shutdown()
      system.awaitTermination()
      success
    }
  }
}

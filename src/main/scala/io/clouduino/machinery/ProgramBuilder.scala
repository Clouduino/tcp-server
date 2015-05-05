package io.clouduino.machinery

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

class ProgramBuilder[X] {
  sealed trait Part[+A] {

    def flatMap[B](f: A => Part[B]): Part[B] = FlatMap(this, f)

    def map[B](f: A => B): Part[B] = flatMap(f andThen ValueOf[B])

    def branch[B](toEither: A => Either[Part[X], Part[B]]): Part[B] =
      this map toEither flatMap {
        case Left(command) => command.map(Left(_))
        case Right(command) => command.map(Right(_))
      } flatMap ValueOfEither[B]

    def execute(implicit ec: ExecutionContext, ev: A => X): Future[X] = {
      compute.map(_.right.map(ev).merge)
    }

    private def compute(implicit ec: ExecutionContext): Future[Either[X, A]] =
      this match {
        case ValueOf(a)       => Future successful Right(a)
        case ValueOfEither(a) => Future successful a
        case ValueOfFuture(a) => a map Right.apply
        case FlatMap(a, f) =>
          a.compute flatMap {
            case Left(x) => Future successful Left(x)
            case Right(x) => f(x).compute
          }
      }
  }

  def Return[A](a:A): Part[A] = ValueOf(a)
  case class ValueOf[A](a: A) extends Part[A]
  case class ValueOfFuture[A](a: Future[A]) extends Part[A]
  case class ValueOfEither[A](a: Either[X, A]) extends Part[A]
  case class FlatMap[A, B](a: Part[A], f: A => Part[B]) extends Part[B]
  object Empty extends ValueOf(unit)
  private val unit: Unit = ()

  import scala.language.implicitConversions

  implicit def executePart(part: Part[X])(implicit ec: ExecutionContext): Future[X] =
    part.execute

  implicit class BooleanOperations(f: Part[Boolean]) {
    def ifTrue(whenTrue: => Part[X]): Part[Unit] =
      f branch (if (_) Left(whenTrue) else Right(Empty))

    def ifFalse(whenFalse: => Part[X]): Part[Unit] =
      f branch (if (_) Right(Empty) else Left(whenFalse))
  }
}

object ProgramBuilder {
  def apply[X]: ProgramBuilder[X] = new ProgramBuilder[X]
}

package io

import scala.reflect.ClassTag
import scala.language.implicitConversions

package object clouduino {
  // A few things that are missing in the standard library

  type ?=>[-A, +B] = PartialFunction[A, B]

  // should be integrated in the `orElse` method of `PartialFunction`
  // https://issues.scala-lang.org/browse/SI-9301
  implicit def widenTypeOfPartialFunction[A: ClassTag, B, C](p: A ?=> B): (C ?=> B) =
    new (C ?=> B) {
      def apply(c: C): B =
        c match {
          case a: A => p(a)
        }

      def isDefinedAt(c: C): Boolean =
        c match {
          case a: A => p isDefinedAt a
          case _    => false
        }
    }
}

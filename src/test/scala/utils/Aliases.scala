package utils

import org.qirx.littlespec.Specification
import org.qirx.littlespec.assertion.Assertion
import org.qirx.littlespec.fragments.Fragment

trait Aliases { _: Specification =>
  implicit class WasEnhancement[A](result: => A) {
    def was[B](assertion: => Assertion[B])(implicit ev: A => B): Fragment.Body =
      result must assertion
  }
}

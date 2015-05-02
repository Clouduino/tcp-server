package utils

import org.qirx.littlespec.Specification

trait Documentation extends Specification
  with CustomExamples
  with PrintlnMock
  with ConnectionUtils
  with Aliases
  with ByteUtils
  with WithActorSystem {

  //https://github.com/EECOLOR/little-spec/pull/8
  implicit class FixedIsEnhancement[A](result: A) {
    def is[B](expected: B): FragmentBody = {
      if (result != expected) failure(result + " is not equal to " + expected)
      else success
    }
  }
}

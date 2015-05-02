package utils

import org.qirx.littlespec.Specification
import org.qirx.littlespec.fragments.Code
import org.qirx.littlespec.fragments.Fragment
import org.qirx.littlespec.io.Source
import org.qirx.littlespec.macros.Location

trait CustomExamples { _: Specification =>

  def sideEffectExample(code: => Unit)(implicit location: Location): Fragment =
    createFragment(Source.codeAtLocation(location), { code; success })

  implicit class StringEnhancement(s: String) {
    def --[A <: Example](example: A): A = {
      s - createFragment(example.code, success)
      example
    }
  }

  class Example(implicit location: Location) {
    val code: Code = Source.codeAtLocation(location)

    def chain[A](f: this.type => A): A = f(this)
  }
}

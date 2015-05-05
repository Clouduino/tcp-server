package io.clouduino.convert

trait Unsigned {
  val toUnsigned: Byte => Short = byte => (byte & 0xFF).toShort
}

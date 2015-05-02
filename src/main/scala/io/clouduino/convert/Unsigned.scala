package io.clouduino.convert

trait Unsigned {
  def toUnsigned(byte: Byte): Short = (byte & 0xFF).toShort
}

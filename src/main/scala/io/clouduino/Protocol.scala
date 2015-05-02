package io.clouduino

object Protocol {
  val ID_TERMINATOR     = 0x00.toByte
  val ID_NOT_RECEIVED   = 0xF0.toByte
  val ID_NOT_ACCEPTED   = 0xF1.toByte
  val DATA_NOT_ACCEPTED = 0xF2.toByte

  val isReserved: Byte => Boolean = {
    val reserved = (0x00 +: (0xF0 to 0xFF)) map (_.toByte)
    reserved.toSet
  }

  val MAX_ID_SIZE       = 256
  val MAX_MESSAGE_COUNT = 256
}

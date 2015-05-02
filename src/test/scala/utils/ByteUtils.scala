package utils

trait ByteUtils {
  implicit class ByteArrayEnhancements(bytes: Array[Byte]) {
    def + (i: Int): Array[Byte] = bytes :+ i.toByte
  }
}

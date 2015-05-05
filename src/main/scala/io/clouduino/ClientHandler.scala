package io.clouduino

import scala.concurrent.Future

trait ClientHandler {
  def isValidId(id: String): Future[Boolean]
  def handleData(id: String, data: Short): Future[Unit]
}

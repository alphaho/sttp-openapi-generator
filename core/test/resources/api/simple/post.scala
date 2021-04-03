package io.github.ghostbuster91.sttp.client3.example

import _root_.sttp.client3._
import _root_.sttp.model._
import _root_.sttp.client3.circe._
import _root_.io.circe.generic.auto._

case class Person(name: String, age: Int)

class DefaultApi(baseUrl: String) {
  def createPerson(): Request[Person, Any] = basicRequest
    .post(uri"$baseUrl/person")
    .response(asJson[Person].getRight)
}
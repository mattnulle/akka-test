package com.example.routes

import akka.actor.Props
import akka.http.scaladsl.model.{ ContentTypes, StatusCodes }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{ Matchers, WordSpec }
import spray.json._
import org.cassandraunit.utils.EmbeddedCassandraServerHelper

class UserRoutesSpec extends WordSpec with Matchers with ScalatestRouteTest with PrettyJsonFormatSupport with CassandraSpec {

  val userManager = system.actorOf(Props(new UserManager(session)))
  val uRoutes = new UserRoutes(userManager)
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd kk:mm:ss.SSSS")

  val now = dateFormat.format(new java.util.Date())

  val request = new CreateUserRequest("matt@bitbrew.com", "mypassword", Some("Matt"))
  val user = new User(request.email, request.password, request.name, now)
  val emailError = new ErrorMessage("invalid", "email", "The email address does not appear to be valid")
  val passwordError = new ErrorMessage("invalid", "password", "The password is not strong enough")

  def timeDifference(one: String, two: String): Int = {
    (dateFormat.parse(one).getTime() - dateFormat.parse(two).getTime()).abs.toInt
  }

  def compareUsers(one: User, two: User): Unit = {
    one.email shouldBe two.email
    one.password shouldBe two.password
    one.name shouldBe two.name
    // Check that the time difference is less than a couple seconds
    timeDifference(one.createdAt, two.createdAt) should be < 2000
  }

  "UserRoutes" should {
    "answer to any requests to /users/reset by clearing the user list" in {
      Get("/users/reset") ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "{\n  \"users\": []\n}"
      }
    }
    "answer to GET requests to `/users`" in {
      Get("/users") ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "{\n  \"users\": []\n}"
      }
    }
    "handle a POST request to `/users`" in {
      Post("/users").withEntity(ContentTypes.`application/json`, request.toJson.compactPrint) ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.Created
        val response = responseAs[String].parseJson.convertTo[User]
        compareUsers(response, user)
      }
    }
    "reject a POST request if the email is invalid" in {
      // bad request in email
      val request = new CreateUserRequest("matt@ bitbrew.com", "mypassword", None)
      Post("/users").withEntity(ContentTypes.`application/json`, request.toJson.compactPrint) ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[String].parseJson.convertTo[ErrorMessage] shouldBe emailError
      }
    }
    "reject a POST request if the password is too short" in {
      // bad request in password
      val request = new CreateUserRequest("matt@bitbrew.com", "pass", None)
      Post("/users").withEntity(ContentTypes.`application/json`, request.toJson.prettyPrint) ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[String].parseJson.convertTo[ErrorMessage] shouldBe passwordError
      }
    }
    "return the user posted previously when GETting from `/users`" in {
      Get("/users") ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.OK
        // Returns a JSON list of Users, under the attribute 'users'. Grab the first to compare
        val response = responseAs[String].parseJson.convertTo[Users].users.last
        compareUsers(response, user)
      }
    }
    "answer to further requests to /users/reset by clearing the user list" in {
      Get("/users/reset") ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "{\n  \"users\": []\n}"
      }
    }
  }

}

package com.example.routes

import akka.actor.{ PoisonPill, Props }
import akka.http.scaladsl.model.{ ContentTypes, StatusCodes }
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{ Matchers, WordSpec }
import spray.json._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

class UserRoutesSpec extends WordSpec with Matchers with ScalatestRouteTest with PrettyJsonFormatSupport {

  val userManager = system.actorOf(Props[UserManager])
  val uRoutes = new UserRoutes(userManager)
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd kk:mm:ss.SSSS")

  val now = dateFormat.format(new java.util.Date())

  val request = new UserRequest("matt@bitbrew.com", "mypassword", Some("Matt"))
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
    // Check that the time difference is less than 1000 milliseconds
    timeDifference(one.createdAt, two.createdAt) should be < 1000
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
        val response = responseAs[String].parseJson.convertTo[Users]
        response shouldBe a[Users]
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
      val request = new UserRequest("matt@ bitbrew.com", "mypassword", None)
      Post("/users").withEntity(ContentTypes.`application/json`, request.toJson.compactPrint) ~> uRoutes.userRoutes ~> check {
        status shouldBe StatusCodes.UnprocessableEntity
        responseAs[String].parseJson.convertTo[ErrorMessage] shouldBe emailError
      }
    }
    "reject a POST request if the password is too short" in {
      // bad request in password
      val request = new UserRequest("matt@bitbrew.com", "pass", None)
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
  }
  "UserManager" should {
    "keep its state after a restart" in {
      implicit val timeout: Timeout = 5.seconds

      val userMan = system.actorOf(Props[UserManager], "userMan-id")
      userMan ? DropAllUsers
      userMan ? request

      val preUsers = (userMan ? GetUsers).mapTo[Users]
      val preResult = Await.result(preUsers, timeout.duration)

      userMan ! PoisonPill
      val userMan2 = system.actorOf(Props[UserManager], "userMan2-id")
      val postUsers = (userMan2 ? GetUsers).mapTo[Users]
      val postResult = Await.result(postUsers, timeout.duration)

      preResult.users.size shouldBe 1
      postResult.users.size shouldBe 1

      compareUsers(preResult.users.head, postResult.users.head)

    }
  }

}

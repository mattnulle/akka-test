package com.example.routes
import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ StatusCodes }
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import com.example.WebServer.{ as, complete, entity, get, post }
import spray.json.{ DefaultJsonProtocol, PrettyPrinter }

import scala.concurrent.duration._
import akka.actor.Status.Failure
import akka.util.Timeout

import scala.concurrent.Future

case class MyValidationException(message: String) extends Exception(message)

case class UserRequest(email: String, password: String, name: Option[String])
case class Users(users: List[User])
case class ErrorMessage(code: String, field: String, message: String)
case class User(email: String, password: String, name: Option[String], createdAt: String) {
  if (email.isEmpty) throw new MyValidationException("You must provide an email address,email")
  if (!email.matches("""(\w+)@([\w\.]+)""")) throw new MyValidationException("The email address does not appear to be valid,email")
  if (password.length() < 8) throw new MyValidationException("The password is not strong enough,password")
}
case object GetUsers

trait PrettyJsonFormatSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val printer = PrettyPrinter

  implicit val userRequestFormat = jsonFormat3(UserRequest)
  implicit val userFormat = jsonFormat4(User)
  implicit val usersFormat = jsonFormat1(Users)
  implicit val errorMessageFormat = jsonFormat3(ErrorMessage)
}

class UserManager extends Actor with ActorLogging with PrettyJsonFormatSupport {
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd kk:mm:ss.SSSS")
  var users = List.empty[User]

  def createUserFromRequest(userRequest: UserRequest): User = {
    val now = dateFormat.format(new java.util.Date())
    val user = new User(userRequest.email, userRequest.password, userRequest.name, now)
    user
  }

  def receive = {
    case userRequest @ UserRequest(email, password, name) =>
      try {
        val user = createUserFromRequest(userRequest)
        users = users :+ user
        sender() ! user
      } catch {
        case e: MyValidationException =>
          sender() ! Failure(e)
      }
    case GetUsers => sender() ! Users(users)
  }

}

class UserRoutes(userman: ActorRef) extends PrettyJsonFormatSupport {
  val userManager = userman
  val userRoutes = get { // Listens only to GET requests
    implicit val timeout: Timeout = 5.seconds
    val users: Future[Users] = (userManager ? GetUsers).mapTo[Users]
    complete(users)
  } ~
    post { // Listens to POST requests
      implicit val timeout: Timeout = 5.seconds
      entity(as[UserRequest]) { userRequest =>
        // Try to create the user
        val userCreated: Future[User] = (userManager ? userRequest).mapTo[User]
        // Handle failure of userManager gracefully
        onComplete(userCreated) {
          case scala.util.Success(value) => complete(StatusCodes.Created, value)
          case scala.util.Failure(e) => {
            val Array(msg, field) = e.getMessage().split(",")
            complete(StatusCodes.UnprocessableEntity, new ErrorMessage("invalid", field, msg))
          }
        }
      }
    }
}
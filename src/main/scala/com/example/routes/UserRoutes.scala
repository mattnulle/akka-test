package com.example.routes
import com.example.WebServer.{ as, complete, entity, get, post }
import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.actor.Status.Failure
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.pattern.ask
import akka.util.Timeout
import spray.json.{ DefaultJsonProtocol, PrettyPrinter }

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future }
import com.datastax.driver.core._

import scala.collection.JavaConverters._

// Exception
case class MyValidationException(message: String) extends Exception(message)

// Data objects (with json formatters)
case class CreateUserRequest(email: String, password: String, name: Option[String])
case class Users(users: List[User])
case class User(email: String, password: String, name: Option[String], createdAt: String) {
  if (email.isEmpty) throw new MyValidationException("You must provide an email address,email")
  if (!email.matches("""(\w+)@([\w\.]+)""")) throw new MyValidationException("The email address does not appear to be valid,email")
  if (password.length() < 8) throw new MyValidationException("The password is not strong enough,password")
}
case class ErrorMessage(code: String, field: String, message: String)

object User {
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd kk:mm:ss.SSSS")

  // Try to create a user. Might throw a MyValidationException
  def createUserFromRequest(userRequest: CreateUserRequest): User = {
    val now = dateFormat.format(new java.util.Date())
    new User(userRequest.email, userRequest.password, userRequest.name, now)
  }

  def createUserFromRow(row: Row): User = {
    new User(row.getString("email"), row.getString("password"), Some(row.getString("name")), row.getString("createdat"));
  }

}
// Messages
case object GetUsersRequest
case object DropAllUsersRequest

// JSON Formatting with Spray
trait PrettyJsonFormatSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val printer = PrettyPrinter

  implicit val userRequestFormat = jsonFormat3(CreateUserRequest)
  implicit val userFormat = jsonFormat4(User.apply)
  implicit val usersFormat = jsonFormat1(Users)
  implicit val errorMessageFormat = jsonFormat3(ErrorMessage)
}

// Actor that manages the connection with Cassandra, adding and reading users
class UserManager(session: Session) extends Actor with ActorLogging with PrettyJsonFormatSupport {

  println(session)
  val preparedSelect = session.prepare("select * from user")
  val preparedInsert = session.prepare(
    "insert into user (createdAt, name, email, password) VALUES (?, ?, ?, ?)"
  )
  val preparedTruncate = session.prepare("truncate user")

  // Receive messages
  def receive = {
    case createUserRequest: CreateUserRequest =>
      // Try to create the user from the request.
      // On success, return user; on fail, return a failure with the exception
      try {
        val user = User.createUserFromRequest(createUserRequest)

        val statement = preparedInsert.bind(user.createdAt, user.name.getOrElse(""), user.email, user.password)
        session.executeAsync(statement);
        sender() ! user
      } catch {
        case e: MyValidationException =>
          sender() ! Failure(e)
      }
    case GetUsersRequest => {
      // Read the list from the DB, turn them into users, and send.
      val future: ResultSetFuture = session.executeAsync(preparedSelect.bind());
      sender() ! future
    }
    case DropAllUsersRequest => {
      // Tell the database to drop all user rows
      session.executeAsync(preparedTruncate.bind());
      sender() ! Users(List.empty[User])
    }
  }

}

// Manages the '/users/' routes and their interaction with the UserManager actor
class UserRoutes(userman: ActorRef) extends PrettyJsonFormatSupport {

  // If we get an exception, parse it and turn into JSON
  def myExceptionHandler = ExceptionHandler {
    case e: MyValidationException =>
      val Array(msg, field) = e.getMessage().split(",")
      complete(StatusCodes.UnprocessableEntity, new ErrorMessage("invalid", field, msg))
  }

  val userManager = userman // passed in by the ActorSystem

  val userRoutes = handleExceptions(myExceptionHandler) {
    pathPrefix("users") {
      pathEndOrSingleSlash {
        get { // Listens only to GET requests
          implicit val timeout: Timeout = 5.seconds
          val future: Future[ResultSetFuture] = (userManager ? GetUsersRequest).mapTo[ResultSetFuture]
          val resultSet: ResultSetFuture = Await.result(future, 5 second)
          complete(new Users(resultSet.get.all().asScala.map(row => User.createUserFromRow(row)).toList))
        } ~
          post { // Listens to POST requests
            implicit val timeout: Timeout = 5.seconds
            entity(as[CreateUserRequest]) { userRequest =>
              // Try to create the user
              val userCreated: Future[User] = (userManager ? userRequest).mapTo[User]
              complete(StatusCodes.Created, userCreated)
            }
          }
      } ~
        path("reset") {
          implicit val timeout: Timeout = 5.seconds
          val users: Future[Users] = (userManager ? DropAllUsersRequest).mapTo[Users]
          complete(users)
        }
    }
  }
}
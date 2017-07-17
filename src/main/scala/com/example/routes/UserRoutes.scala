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
import com.datastax.driver.core.querybuilder.QueryBuilder
import com.google.common.util.concurrent.{ FutureCallback, Futures }

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
    new User(
      row.getString("email"),
      row.getString("password"),
      if (!row.getString("name").isEmpty()) Some(row.getString("name")) else None,
      row.getString("createdat")
    );
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
  //  val preparedSelect = session.prepare("select * from user")
  val preparedSelect = session.prepare(QueryBuilder.select().all().from("user"))
  val preparedInsert = session.prepare(QueryBuilder.insertInto("user")
    .value("createdAt", QueryBuilder.bindMarker())
    .value("name", QueryBuilder.bindMarker())
    .value("email", QueryBuilder.bindMarker())
    .value("password", QueryBuilder.bindMarker()))
  val preparedTruncate = session.prepare(QueryBuilder.truncate("user"))

  // Receive messages
  def receive = {
    case creationRequest: CreateUserRequest =>
      // Try to create the user from the request.
      // On success, return user; on fail, return a failure with the exception
      try {
        val user = User.createUserFromRequest(creationRequest) // validates or throws an exception
        val statement = preparedInsert.bind(user.createdAt, user.name.getOrElse(""), user.email, user.password)
        val future = session.executeAsync(statement);
        val senderVal = sender()
        Futures.addCallback(future, new FutureCallback[ResultSet] {
          override def onSuccess(resultSet: ResultSet): Unit = senderVal ! user
          override def onFailure(t: Throwable): Unit = sender() ! Failure(new Exception("Something went wrong in the database,server"))
        })
      } catch {
        case e: MyValidationException =>
          sender() ! Failure(e)
      }
    case GetUsersRequest => {
      // Read the list from the DB
      val future: ResultSetFuture = session.executeAsync(preparedSelect.bind())
      val senderVal = sender()
      Futures.addCallback(future, new FutureCallback[ResultSet] {
        override def onSuccess(result: ResultSet): Unit = senderVal ! new Users(result.all.asScala.toList.map(x => User.createUserFromRow(x)))
        override def onFailure(t: Throwable): Unit = sender() ! Failure(new Exception("Something went wrong in the database,server"))
      })
    }
    case DropAllUsersRequest => {
      // Tell the database to drop all user rows
      val future: ResultSetFuture = session.executeAsync(preparedTruncate.bind())
      val senderVal = sender()
      Futures.addCallback(future, new FutureCallback[ResultSet] {
        override def onSuccess(resultSet: ResultSet): Unit = senderVal ! Users(List.empty[User])
        override def onFailure(t: Throwable): Unit = sender() ! Failure(new Exception("Something went wrong in the database,server"))
      })
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
  implicit val timeout: Timeout = 5.seconds

  val userManager = userman // passed in by the ActorSystem

  val userRoutes = handleExceptions(myExceptionHandler) {
    pathPrefix("users") {
      pathEndOrSingleSlash {
        get { // Listens only to GET requests
          // The actor returns a Future of a Cassandra ResultSetFuture
          val users: Future[Users] = (userManager ? GetUsersRequest).mapTo[Users]
          complete(users)
        } ~
          post { // Listens to POST requests
            entity(as[CreateUserRequest]) { userRequest =>
              // Send the userRequest to the userManager and get a createdUser
              val createdUser: Future[User] = (userManager ? userRequest).mapTo[User]
              complete(StatusCodes.Created, createdUser)
            }
          }
      } ~
        path("reset") {
          val users: Future[Users] = (userManager ? DropAllUsersRequest).mapTo[Users]
          complete(users)
        }
    }
  }
}
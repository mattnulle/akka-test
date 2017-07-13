package com.example.routes
import akka.actor.{ Actor, ActorLogging, ActorRef }
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import com.example.WebServer.{ as, complete, entity, get, post }
import spray.json.{ DefaultJsonProtocol, PrettyPrinter }

import scala.concurrent.duration._
import akka.actor.Status.Failure
import akka.http.scaladsl.server.ExceptionHandler
import akka.util.Timeout
import com.datastax.driver.core.{ Cluster, ResultSet, Session }

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
case object DropAllUsers
case object KillConnection

trait PrettyJsonFormatSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val printer = PrettyPrinter

  implicit val userRequestFormat = jsonFormat3(UserRequest)
  implicit val userFormat = jsonFormat4(User)
  implicit val usersFormat = jsonFormat1(Users)
  implicit val errorMessageFormat = jsonFormat3(ErrorMessage)
}

class UserManager extends Actor with ActorLogging with PrettyJsonFormatSupport {
  val dateFormat = new java.text.SimpleDateFormat("yyyy-MM-dd kk:mm:ss.SSSS")

  val cluster: Cluster = Cluster.builder()
    .addContactPoint("127.0.0.1")
    .build();
  val session: Session = cluster.connect("test");

  def createUserFromRequest(userRequest: UserRequest): User = {
    val now = dateFormat.format(new java.util.Date())
    val user = new User(userRequest.email, userRequest.password, userRequest.name, now)
    user
  }

  override def postStop() {
    if (cluster != null) cluster.close();
  }

  def receive = {
    case userRequest @ UserRequest(email, password, name) =>
      try {
        val user = createUserFromRequest(userRequest)
        session.execute(s"insert into user (createdAt, name, email, password) VALUES ('${user.createdAt}', '${user.name.getOrElse("")}', '${user.email}', '${user.password}')");
        sender() ! user
      } catch {
        case e: MyValidationException =>
          sender() ! Failure(e)
      }
    case GetUsers => {
      val rs: ResultSet = session.execute("select * from user");
      var userSet = List.empty[User]
      rs.forEach((row) => {
        userSet = userSet :+ new User(row.getString("email"), row.getString("password"), Some(row.getString("name")), row.getString("createdat"));
      })
      sender() ! Users(userSet)
    }
    case DropAllUsers => {
      session.execute("truncate user");
      sender() ! Users(List.empty[User])
    }
    case KillConnection => if (cluster != null) cluster.close();

  }

}

class UserRoutes(userman: ActorRef) extends PrettyJsonFormatSupport {
  def myExceptionHandler = ExceptionHandler {
    case e: MyValidationException =>
      val Array(msg, field) = e.getMessage().split(",")
      complete(StatusCodes.UnprocessableEntity, new ErrorMessage("invalid", field, msg))
  }

  val userManager = userman
  val userRoutes = handleExceptions(myExceptionHandler) {
    pathPrefix("users") {
      pathEndOrSingleSlash {
        get { // Listens only to GET requests
          implicit val timeout: Timeout = 5.seconds
          val users: Future[Users] = (userManager ? GetUsers).mapTo[Users]
          complete(users)
        } ~
          post { // Listens to POST requests
            implicit val timeout: Timeout = 5.seconds
            entity(as[UserRequest]) { userRequest =>
              // Try to create the user
              val userCreated: Future[User] = (userManager ? userRequest).mapTo[User]
              complete(StatusCodes.Created, userCreated)
            }
          }
      } ~
        path("reset") {
          implicit val timeout: Timeout = 5.seconds
          val users: Future[Users] = (userManager ? DropAllUsers).mapTo[Users]
          complete(users)
        }
    }
  }
}
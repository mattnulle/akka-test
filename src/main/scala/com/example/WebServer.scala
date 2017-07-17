package com.example

import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.datastax.driver.core.{ Cluster, Session }
import com.example.routes._

import scala.io.StdIn

object WebServer extends Directives with SimpleRoutes with PrettyJsonFormatSupport {

  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    // Cassandra connection established here. If other parts of the program used
    // the DB, this code should be moved higher and passed down
    val cluster: Cluster = Cluster.builder()
      .addContactPoint("127.0.0.1")
      .build();
    try {
      val session: Session = cluster.connect("test");
      val userManager = system.actorOf(Props(new UserManager(session)), "user-manager")
      val userRoutes = new UserRoutes(userManager)

      val routes = userRoutes.userRoutes ~ BaseRoutes.baseRoutes ~ simpleRoutes
      val bindingFuture = Http().bindAndHandle(routes, "localhost", 8080)

      println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
      StdIn.readLine() // let it run until user presses return

      println("Shutting down...")
      if (cluster != null) cluster.close();
      bindingFuture
        .flatMap(_.unbind()) // trigger unbinding from the port
        .onComplete(_ => system.terminate()) // and shutdown when done
    } catch {
      case e: com.datastax.driver.core.exceptions.NoHostAvailableException => {
        println("You have to start Cassandra first. Shutting down...")
        system.terminate()
      }
    }
  }

}
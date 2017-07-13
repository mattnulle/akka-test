package com.example

import akka.actor.{ ActorSystem, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import com.example.routes._
import com.datastax.driver.core.{ Cluster, ResultSet, Row, Session }

import scala.io.StdIn

object WebServer extends Directives with SimpleRoutes with PrettyJsonFormatSupport {

  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    // needed for the future flatMap/onComplete in the end
    implicit val executionContext = system.dispatcher

    val userManager = system.actorOf(Props[UserManager], "user-manager")
    val userRoutes = new UserRoutes(userManager)

    val routes = userRoutes.userRoutes ~ BaseRoutes.baseRoutes ~ simpleRoutes

    val bindingFuture = Http().bindAndHandle(routes, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done

  }

}
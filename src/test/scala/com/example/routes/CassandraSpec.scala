package com.example.routes

import com.datastax.driver.core.Session
import org.cassandraunit.utils.EmbeddedCassandraServerHelper
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.duration._

/**
 * Common trait used to define specifications related to Cassandra access.
 */
trait CassandraSpec extends BeforeAndAfterAll with ScalaFutures {
  this: Suite ⇒

  implicit val patienceTimeout = org.scalatest.concurrent.PatienceConfiguration.Timeout(10.seconds)
  EmbeddedCassandraServerHelper.startEmbeddedCassandra()

  val session: Session = EmbeddedCassandraServerHelper.getCluster().connect()
  session.execute("CREATE KEYSPACE test WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };")
  session.execute("USE test;")
  session.execute("CREATE TABLE user ( createdAt text PRIMARY KEY, name text, email text, password text );")

  override protected def afterAll(): Unit = {
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra()
    if (EmbeddedCassandraServerHelper.getCluster() != null) EmbeddedCassandraServerHelper.getCluster().close();

    super.afterAll()
  }

}
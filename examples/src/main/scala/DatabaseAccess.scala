// Example: Accessing an SQL Database

// Let's write a Web service that talk to an SQL database. We
// will use [doobie](http://tpolecat.github.io/doobie/) as JDBC layer.

import lol.http._
import lol.html._

// We will configure doobie to use the cats `IO` effect,
// so it will play well with lolhttp 🎉.
import cats.effect.{ IO }
import cats.implicits._

// Now we just import the needed package for doobie.
import doobie.h2._
import doobie.h2.implicits._
import doobie.implicits._

import scala.concurrent.ExecutionContext.Implicits.global

object DatabaseAccess {
  def main(args: Array[String]): Unit = {

    // First create the database schema.
    val createTable = sql"""
      CREATE TABLE country (
        code       character(3)  NOT NULL,
        name       text          NOT NULL
      );
    """.update

    // Also we need to import fake data for the example.
    val importData = sql"""
      INSERT INTO country (code, name) VALUES
        ('FR', 'France'),
        ('US', 'United States'),
        ('DE', 'GERMANY');
    """.update

    // This is our setup script. Connect to the database, create the schema
    // and import the data.
    val setup = for {
      xa <- H2Transactor.newH2Transactor[IO]("jdbc:h2:mem:test;DB_CLOSE_DELAY=-1", "sa", "")
      _  <- xa.setMaxConnections(10)
      _  <- (createTable.run *> importData.run).transact(xa)
    } yield (xa)

    // We run our setup script to startup the database and we keep a
    // reference to the Transactor.
    val xa = setup.unsafeRunSync

    // Now let's focus on the HTTP service.
    Server.listen(8888) { req =>

      // Here we simply use doobie to query the database. This in an _effect_ and
      // so the result is wrapped into an `IO`.
      sql"SELECT code, name FROM country".
        query[(String,String)].
        list.
        transact(xa).
        map { resultSet =>

        // Let's transform our database result set to an HTML list
        val countries = resultSet.map { case (code, name) =>
          html"<li><strong>${code}</strong> - ${name}</li>"
        }

        // Finally we give back an Ok HTTP response.
        Ok(html"""
          <h1>${resultSet.size} Countries:</h1>
          <ul>
            $countries
          </ul>
        """)
      }
    }

    println("Listening on http://localhost:8888...")
  }
}
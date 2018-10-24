// Example: Accessing an SQL Database

// Let's write a Web service that talk to an SQL database. We
// will use [doobie](http://tpolecat.github.io/doobie/) as JDBC layer.

import lol.http._
import lol.html._

// We will configure doobie to use the cats `IO` effect,
// so it will play well with lolhttp 🎉.
import cats.effect._
import cats.implicits._

// Now we just import the needed package for doobie.
import doobie.h2._
import doobie.h2.implicits._
import doobie.implicits._
import doobie.util.ExecutionContexts

import scala.concurrent.ExecutionContext.Implicits.global

object DatabaseAccess extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    // First create the database schema.
    val createTable = sql"""
      CREATE TABLE country (
        code       character(3)  NOT NULL,
        name       text          NOT NULL
      );
    """.update

    // Also we need to import fake data for the example.
    val importData = sql"""
      INSERT INTO country (code, name) VALUESclean
        ('FR', 'France'),
        ('US', 'United States'),
        ('DE', 'GERMANY');
    """.update


    // This is our setup script. Connect to the database, create the schema
    // and import the data.
    val xa: Resource[IO, H2Transactor[IO]] = for {
      fixedThreadPool <- ExecutionContexts.fixedThreadPool[IO](32)
      cachedThreadPool <- ExecutionContexts.cachedThreadPool[IO]
      transactor <- H2Transactor.newH2Transactor[IO](
        "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1",
        "sa",
        "",
        fixedThreadPool,
        cachedThreadPool
      )
    } yield transactor

    xa.use(xa => for {
      // We run our setup script to startup the database and we keep a
      // reference to the Transactor.
      _ <- xa.setMaxConnections(10) >> (createTable.run *> importData.run).transact(xa)
      // Now let's focus on the HTTP service.
      _ <- IO(Server.listen(8888) { req =>

        // Here we simply use doobie to query the database. This in an _effect_ and
        // so the result is wrapped into an `IO`.
        sql"SELECT code, name FROM country".
          query[(String,String)].
          to[List].
          transact(xa).
          map { resultSet =>

            // Let's transform our database result set to an HTML list
            val content =
              tmpl"""
                    <h1>@resultSet.size Countries:</h1>
                    <ul>
                      @resultSet.map { case (code, name) =>
                        <li><strong>@code</strong> - @name</li>
                      }
                    </ul>
                  """

            // Finally we give back an Ok HTTP response.
            Ok(content)
        }
      })
    } yield ExitCode.Success)
  }
}
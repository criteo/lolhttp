// Example: A Github API client

// In this example we want to use the Github Json API, to retrieve
// and list all the repositories under the __criteo__ account.
import lol.http._
import lol.json._

// We will use [circe](https://circe.github.io/circe/) to handle
// the Json Http requests and responses.
import io.circe._
import io.circe.optics.JsonPath._

import scala.util._
import scala.concurrent._
import ExecutionContext.Implicits.global

// - - -

object GithubClient {
  def main(args: Array[String]): Unit = {

    // Let's start by creating an Http client connected to Github.
    // Because we have several requests to send to the API, it is more
    // efficient to keep a client connected for the duration of the program.
    // It will open and maintain a number of HTTP connections that will be reused
    // accross requests.
    val githubClient = Client("api.github.com", 443, "https")

    // Github requires that we have a User-Agent for our requests.
    val userAgent = (h"User-Agent" -> h"lolhttp")

    val criteoRepositories = for {

      // First thing, we retrieve all the repositories for the criteo
      // organization.
      //
      // We use the `run` operation here: it takes the HTTP
      // request to process and a block that will transform the HTTP response
      // into a value of your choice. After the future completion, it will
      // automatically drain the unread content if needed so the connection
      // is ready for the next request.
      repositories <- githubClient.run(Get("/users/criteo/repos").addHeaders(userAgent)) {
        _.readSuccessAs[Json].map(root.each.full_name.string.getAll)
      }

      // Next, for each repository we make an additional HTTP request to
      // retrieve the repository description (It is useless since the
      // description was already available in the first request, but it
      // is just for the sake of the example 😊).
      descriptions <- Future.sequence(repositories.map { repository =>
        githubClient.run(Get(url"/repos/$repository").addHeaders(userAgent)) {
          _.readSuccessAs[Json].map(root.description.string.getOption)
        }
      })
    } yield repositories.zip(descriptions)

    // - - -

    // Eventually, we can just display the result...
    criteoRepositories.
      andThen {
        case Success(result) =>
          result.foreach { case (repository, description) =>
            println(s"""- $repository:""")
            println(s"""  ${description.getOrElse("No description")}""")
          }
        case Failure(error) =>
          println(s"Could not fetch criteo github repositories")
          error.printStackTrace()
          System.exit(-1)
      }.
      andThen { case _ =>

        // and then, we shutdown the HTTP client.
        githubClient.stop()
      }
  }
}

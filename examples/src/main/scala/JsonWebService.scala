// Example: A Json web service

// Let's write a very minimal TODO application providing a Json web API.
import lol.http._
import lol.json._
import lol.html._

// We will use [circe](https://circe.github.io/circe/) as Json library
// for this example. You should first have a look a the circe documentation
// to understand the basics.
import io.circe._
import io.circe.syntax._
import io.circe.generic.auto._
import io.circe.optics.JsonPath._

import cats.implicits._
import cats.effect.{ IO }

import scala.util._
import scala.concurrent.ExecutionContext.Implicits.global

// - - -
object JsonWebService {

  // First, let's define a Todo case class. We let the circe macro automatically
  // generate the corresponding Json encoder/decoder for this type.
  case class Todo(id: Int, text: String, done: Boolean)

  // Also, we define an ordering for our todo values so we always list them is the right order.
  implicit val mostRecentTodoFirst: Ordering[Todo] = Ordering.by(todo => -todo.id)

  // And here is a simple helper that will be used to generate _Error_ Json values.
  def Error(msg: String): Json = Json.obj("error" -> Json.fromString(msg))

  // - - -

  // This is our state. We use a concurrent Map for storing the
  // data, and an atomic counter to generate the identifiers sequence.
  val todos = collection.concurrent.TrieMap.empty[Int,Todo]
  val idCounter = new java.util.concurrent.atomic.AtomicInteger(0)

  // - - -

  // Let's start by defining the Html part of our app. It is a `PartialService`
  // because it does not respond to all the incoming requests.
  lazy val App: PartialService = {
    case GET at url"/" =>
      // By using the `tmpl` interpolation, we generate a very minimal UI 🎷.
      Ok(tmpl"""
        <h1>@if(todos.isEmpty) {Nothing} else {A ton of things} to do</h1>
        <ol>
          @todos.values.toSeq.sorted.map { case Todo(_, text, done) =>
            <li style="text-decoration: @if(done) { "line-through" } else { "none" }">@text</li>
          }
        <ol>
      """)
    }

  // - - -

  // Then we define the Json API part. It contains all the endpoint needed
  // to create, list, update and delete todos.
  lazy val Api: PartialService = {

    // This matcher will only match GET for the `/api/todos path`. If a done parameter
    // is present in the incoming queryString it will be bound to the `doneFilter` value.
    case GET at url"/api/todos?done=$doneFilter" =>
      Try(doneFilter.toBoolean).toOption match {
        // If the done parameter value was missing or was not a boolean value we return everything.
        case None =>
          Ok(todos.values.toSeq.sortBy(_.id).asJson)
        // Otherwise we extract the actual list of todos and we transform it into a Json array.
        case Some(isDone) =>
          Ok(todos.values.toSeq.filter(_.done == isDone).sorted.asJson)
      }

    // For this one, we actually need to get a reference of the request value, because we want
    // to consume it's json content.
    case request @ POST at url"/api/todos" =>
      // We first try to read to content into a Json value (this is an asynchronous operation and the
      // Future will fail if the Json can't be parsed).
      //
      // Then we use Circe optics to extract the `text` field from the incoming Json.
      request.readAs[Json].map(root.text.string.getOption).map(_.get).map { text =>
        val nextId = idCounter.incrementAndGet
        val newTodo = Todo(nextId, text, false)
        todos += (nextId -> newTodo)
        Created(newTodo.asJson)
      }.recover { case _ =>
        BadRequest(Error("Please specify the `text' field for this item."))
      }

    // Nothing special here, but look how we handle the 404 case.
    case GET at url"/api/todos/$id" =>
      IO {
        Try(id.toInt).toOption.flatMap(todos.get).map { todo =>
          Ok(todo.asJson)
        }.getOrElse {
          NotFound(Error(s"No todo found for id: `$id'"))
        }
      }

    // Again we need to keep a reference on the request in order to read the
    // json body.
    case request @ POST at url"/api/todos/$id" =>
      Try(id.toInt).toOption.flatMap(todos.get).map { todo =>
        request.readAs[Json].map { jsonBody =>
          val updatedTodo = todo.copy(
            text = root.text.string.getOption(jsonBody).getOrElse(todo.text),
            done = root.done.boolean.getOption(jsonBody).getOrElse(todo.done)
          )
          todos += (todo.id -> updatedTodo)
          Ok(updatedTodo.asJson)
        }
      }.getOrElse {
        NotFound(Error(s"No todo found for id: `$id'"))
      }

    // Our last route handle deletion of the todos. Because deletion is idempotent
    // we succeed even if the id was not found.
    case DELETE at url"/api/todos/$id" =>
      todos -= Try(id.toInt).toOption.getOrElse(-1)
      Ok
  }

  // - - -

  def main(args: Array[String]): Unit = {
    // It is now time to actually bootstrap the application. We start an HTTP server, and we
    // compose the App and Api functions we just defined.
    Server.listen(8888)(App.orElse(Api).orElse { case _ => NotFound })

    // Done! We can use a Json client to interact with the app.
    println("Listening on http://localhost:8888...")
  }
}

package lol.http

class UrlsTests extends Tests {

  test("Path parts") {
    "/" match { case url"/" => succeed }
    "/hello" match { case url"/hello" => succeed }
    "/hello/world" match { case url"/hello" => fail(); case _ => succeed }
    "/world" match { case url"/$name" => name should be ("world") }
    "/hello/world" match { case url"/hello/$name" => name should be ("world") }
    "world" match { case url"$name" => name should be ("world") }
    "/hello?name=world" match { case url"/hello" => succeed }
    "/hello?world" match { case url"/hello" => succeed }
  }

  test("Path tail") {
    "/app/hello/world" match { case url"$file..." => file should be ("/app/hello/world") }
    "/app/hello/world" match { case url"/$file..." => file should be ("app/hello/world") }
    "/app/hello/world" match { case url"/app/$file..." => file should be ("hello/world") }
    "/app/hello/world" match { case url"/app/hello/$file..." => file should be ("world") }
    "/app/hello/world" match { case url"/app/hello$file..." => file should be ("/world") }
    "/app/hello/world" match { case url"/app/hello/world$file..." => file should be ("") }
  }

  test("QueryString") {
    "/?page=2" match { case url"/?page=$page" => page should be ("2") }
    "/?page=2&sort=asc" match { case url"/?page=$page" => page should be ("2") }
    "/?page=2&sort=asc" match { case url"/?page=$page&sort=$sort" => page should be ("2"); sort should be ("asc") }
    "/?sort=asc&page=2" match { case url"/?page=$page&sort=$sort" => page should be ("2"); sort should be ("asc") }
    "/?sort=asc&page=2" match { case url"/?page=$page&sort=asc" => page should be ("2") }
    "/?sort=asc&page=2" match { case url"/?page=$page&sort=desc" => fail(); case _ => succeed }
    "/?sort=asc&page=2" match { case url"/?sort=asc" => succeed }
    "/?sort=asc&page=2" match { case url"/?sort=desc" => fail(); case _ => succeed }
    "/?sort=asc" match { case url"/?page=$page" => page should be ("") }
    "/" match { case url"/?page=$page" => page should be ("") }
    "/" match { case url"/?page=$page&sort=asc" => fail(); case _ => succeed }
    "/?page=2&page=4" match { case url"/?page=$page" => fail(); case _ => succeed }
    "/?sort=sort-asc&page=2" match { case url"/?sort=sort-$sort" => sort should be ("asc") }
    "/?name=jean-claude" match { case url"/?name=$first-$last" => (first,last) should be ("jean" -> "claude") }
    "/?section=&update=now" match { case url"/?section=&update=$date" => succeed }
    "/?update=now" match { case url"/?section=&update=$date" => fail(); case _ => succeed}
    "/?section=contact&update=now" match { case url"/?section=&update=$date" => fail(); case _ => succeed }
    "/?section=contact" match { case url"/?section=&update=$date" => fail(); case _ => succeed }
    "/?section=" match { case url"/?section=&update=$date" => date should be ("") }
  }

  test("QueryString in request") {
    Get("/").parsedQueryString should be (Nil)
    Get("/").queryStringParameters should be (Map.empty)
    Get("/?sort=asc&page=2").parsedQueryString should be (List("sort" -> "asc", "page" -> "2"))
    Get("/?sort=asc&page=2").queryStringParameters should be (Map("sort" -> "asc", "page" -> "2"))
    Get("/?sort=asc&page=2&sort=desc").parsedQueryString should be (List("sort" -> "asc", "page" -> "2", "sort" -> "desc"))
    Get("/?sort=asc&page=2").queryStringParameters should be (Map("sort" -> "asc", "page" -> "2"))
  }

}

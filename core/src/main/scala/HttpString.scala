package lol.http

/** An case insensitive string.
  *
  * {{{
  * val contentType: HttpString = h"text/plain"
  * }}}
  *
  * HTTP strings are case insensitive. The `h` string interpolation allows to create [[HttpString]]
  * easily.
  *
  * @param str the underlying string.
  */
case class HttpString(str: String) extends Ordered[HttpString] {
  val self: String = str.toLowerCase

  /** Compare with another [[HttpString]].
    * @param that another string.
    * @return true if both string are equals in a case-insensitive manner.
    */
  def compare(that: HttpString) = self.compareTo(that.self)
  override def hashCode = self.hashCode
  override def equals(that: Any) = that match {
    case null => false
    case HttpString(str) => str.equalsIgnoreCase(self)
    case str: String => str.equalsIgnoreCase(self)
    case _ =>
      val x = that.asInstanceOf[AnyRef]
      (x eq this.asInstanceOf[AnyRef]) || (x eq self.asInstanceOf[AnyRef]) || (x equals self)
  }
  override def toString = str
}

/** [[HttpString]] builders. */
object HttpString {

  /** Create an HTTP string from a number.
    * @param n a number.
    * @return an [[HttpString]] created by applying `toString` to the number.
    */
  def apply(n: Int): HttpString = apply(n.toString)

  /** Create an HTTP string from a number.
    * @param n a number.
    * @return an [[HttpString]] created by applying `toString` to the number.
    */
  def apply(n: Long): HttpString = apply(n.toString)
}

package lol.http

case class HttpString(str: String) extends Ordered[HttpString] {
  val self: String = str.toLowerCase
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

object HttpString {
  def apply(n: Int): HttpString = apply(n.toString)
  def apply(n: Long): HttpString = apply(n.toString)
}

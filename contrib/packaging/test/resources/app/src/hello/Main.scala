package hello

object Main extends App {

  def getMessage(args: Array[String]): String = Core.msg() + " " + args(0)

   override def main(args: Array[String]): Unit = {
    println(getMessage(args))
  }
}

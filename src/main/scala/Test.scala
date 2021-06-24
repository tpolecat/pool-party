import cats.effect.{ IO, IOApp }
import cats.effect.std.PQueue
import cats.syntax.all._

object Test extends IOApp.Simple {

  def run: IO[Unit] =
    for {
      pq <- PQueue.unbounded[IO, Int]
      _  <- List(3,1,7,7,0,7,6).traverse(pq.offer)
      ns <- pq.take.replicateA(7)
      _  <- IO.println(ns.toString())
    } yield ()

}
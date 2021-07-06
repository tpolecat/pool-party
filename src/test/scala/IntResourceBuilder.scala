package test

import cats.effect._

class IntResourceBuilder private (
  allocator: Int => IO[Int],
  finalizer: Int => IO[Unit],
) {

  def identity: this.type = this

  def withAllocator(allocator: Int => IO[Int]): IntResourceBuilder =
    new IntResourceBuilder(allocator, finalizer)

  def withFinalizer(finalizer: Int => IO[Unit]): IntResourceBuilder =
    new IntResourceBuilder(allocator, finalizer)

  def build: IO[Resource[IO, Int]] =
    Ref[IO].of(0).map { ref =>
      Resource.make(ref.getAndUpdate(_ + 1).flatMap(allocator))(finalizer)
    }

}

object IntResourceBuilder
  extends IntResourceBuilder(IO.pure, _ => IO.unit)


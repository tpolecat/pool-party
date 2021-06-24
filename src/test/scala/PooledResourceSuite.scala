// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package test

import cats.effect._
import cats.syntax.all._
import org.tpolecat.pooled._
import munit.FunSuite
import munit.CatsEffectSuite
import scala.concurrent.duration._

class PooledResourceSuite extends CatsEffectSuite {

  // Some failures we provoke in our tests.
  object AllocationFailure  extends Exception("Allocation Failure")
  object FreeFailure        extends Exception("Free Failure")
  object HealthCheckFailure extends Exception("HealthCheck Failure")

  // Constants we use below.
  val PoolSize   = 10
  val FiberCount = 100

  // A resource that provides unique integers (which will be non-unique after pooling)
  def trivialResource(
    ref: Ref[IO, Int],
    free: Int => IO[Unit] = _ => IO.unit
  ): Resource[IO, Int] =
    Resource.make(ref.updateAndGet(_ + 1))(free)

  // Use a resource many times, concurrently
  def useResource(rsrc: Resource[IO, _]): IO[Unit] =
    (1 to FiberCount).toList.parTraverse(n => rsrc.use(_ => IO.cede)).void

  test("negative pool size fails with NonPositivePoolSizeException") {
    val rsrc = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    interceptIO[IllegalArgumentException] {
      PooledResource[IO].of(rsrc, -1).use(_ => IO.never)
    }
  }

  test("zero pool size fails with NonPositivePoolSizeException") {
    val rsrc = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    interceptIO[IllegalArgumentException] {
      PooledResource[IO].of(rsrc, 0).use(_ => IO.never)
    }
  }

  test("normal usage") {
    for {
      ref   <- Ref[IO].of(0)
      rsrc   = trivialResource(ref)
      poolR  = PooledResource[IO].of(rsrc, PoolSize)
      _     <- poolR.use(useResource)
      _     <- assertIOBoolean(ref.get.map(_ <= PoolSize))
    } yield ()
  }

  test("normal usage when a health check returns false") {
    for {
      ref   <- Ref[IO].of(0)
      rsrc   = trivialResource(ref)
      poolR  = PooledResource[IO].of(rsrc, PoolSize, (n: Int) => (n != 3).pure[IO])
      _     <- poolR.use(useResource)
      _     <- assertIOBoolean(ref.get.map(_ <= PoolSize + 1))
    } yield ()
  }

  test("normal usage when a health check returns false and free fails") {
    for {
      ref   <- Ref[IO].of(0)
      rsrc   = trivialResource(ref, (n: Int) => IO.raiseError(FreeFailure).whenA(n == 3))
      poolR  = PooledResource[IO].of(rsrc, PoolSize, (n: Int) => (n != 3).pure[IO])
      _     <- poolR.use(useResource)
      _     <- assertIOBoolean(ref.get.map(_ <= PoolSize + 1))
    } yield ()
  }

  test("normal usage when a health check fails") {
    for {
      ref   <- Ref[IO].of(0)
      rsrc   = trivialResource(ref)
      poolR  = PooledResource[IO].of(rsrc, PoolSize, (n: Int) => IO.raiseError[Boolean](AllocationFailure).whenA(n == 3).as(true))
      _     <- poolR.use(useResource)
      _     <- assertIOBoolean(ref.get.map(_ <= PoolSize + 1))
    } yield ()
  }

  test("normal usage when a health check fails and free fails") {
    for {
      ref   <- Ref[IO].of(0)
      rsrc   = trivialResource(ref, (n: Int) => IO.raiseError[Boolean](FreeFailure).whenA(n == 3))
      poolR  = PooledResource[IO].of(rsrc, PoolSize, (n: Int) => IO.raiseError[Boolean](AllocationFailure).whenA(n == 3).as(true))
      _     <- poolR.use(useResource)
      _     <- assertIOBoolean(ref.get.map(_ <= PoolSize + 1))
    } yield ()
  }

  test("allocation failure is thrown to the caller") {
    val rsrc = Resource.make[IO, Int](AllocationFailure.raiseError[IO, Int])(_ => IO.unit)
    PooledResource[IO].of(rsrc, 1).use { pool =>
      interceptIO[AllocationFailure.type] {
        pool.use(_ => IO.never)
      }
    }
  }

  test("allocation should fail immediately when pool is closed") {
    val rsrc   = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    val pooled = PooledResource[IO].of(rsrc, 1)
    (1 to FiberCount).toList.parTraverse { _ =>
      interceptIO[PooledResource.PoolClosedException.type] {
        pooled.use(_.pure[IO]).flatMap { leakedPool =>
          leakedPool.use(_ => IO.never)
        }
      }
    }
  }


}
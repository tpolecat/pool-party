// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package test

import cats.effect._
import cats.syntax.all._
import munit.CatsEffectSuite
import munit.FunSuite
import org.tpolecat.poolparty.PoolEvent._
import org.tpolecat.poolparty._

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._

class PooledResourceSuite extends CatsEffectSuite {

  // Some failures we provoke in our tests.
  object AllocationFailure  extends Exception("Allocation Failure")
  object FreeFailure        extends Exception("Free Failure")
  object HealthCheckException extends Exception("HealthCheck Failure")

  // Constants we use below. We want FiberCount to be much larger than PoolSize to ensure that
  // we predictably end up allocating every slot in the pool.
  val PoolSize   = 10
  val FiberCount = 1000

  // Use a resource many times, concurrently
  def useResource(rsrc: Resource[IO, _]): IO[Unit] =
    (1 to FiberCount).toList.parTraverse(n => rsrc.use(_ => IO.sleep(1.milli))).void

  // A counter for pool events, so we can check to see that it's done what we expect.
  case class Stats(
    requests: Int = 0,
    allocations: Int = 0,
    completions: Int = 0,
    releases: Int = 0,
    recycles: Int = 0,
    healthcheckfailures: Int = 0,
    finalizations: Int = 0,
    finalizationfailures: Int = 0,
  ) {
    def update(e: PoolEvent[Any]): Stats =
      e match {
        case Request(_, _, _) => copy(requests = requests + 1)
        case Allocation(_, _, _, _) => copy(allocations = allocations + 1)
        case Completion(_, _, _, _, _) => copy(completions = completions + 1)
        case Release(_, _, _) => copy(releases = releases + 1)
        case Recycle(_, _, _) => copy(recycles = recycles + 1)
        case HealthCheckFailure(_, _, _, _) => copy(healthcheckfailures = healthcheckfailures + 1)
        case Finalize(_, _, _) => copy(finalizations = finalizations + 1)
        case FinalizerFailure(_, _, _, _) => copy(finalizationfailures = finalizationfailures + 1)
      }
  }
  object Stats {
    lazy val Initial = Stats(0, 0, 0, 0, 0, 0, 0)
  }

  // A reporter that updates stats.
  def statsReporter(ref: Ref[IO, Stats]): PoolEvent[Any] => IO[Unit] = e =>
    ref.modify { stats =>
      (stats.update(e), IO.unit)
    } .flatten

  // Helper for constructing a test that uses a pool many times, accumulating stats on what it's
  // doing. The caller can update the default builders to introduce failures.
  def statsTest(
    name: String,
    rsrcBuilder: IntResourceBuilder => IntResourceBuilder,
    poolBuilder: PooledResourceBuilder[IO, Int] => PooledResourceBuilder[IO, Int],
  )(check: Stats => Unit) =
    test(name) {
      for {
        stats <- Ref[IO].of(Stats.Initial)
        rsrc  <- rsrcBuilder(IntResourceBuilder).build
        poolR  = poolBuilder(PooledResourceBuilder.of(rsrc, PoolSize).withReporter(statsReporter(stats))).build
        _     <- poolR.use(useResource)
        stats <- stats.get
      } yield check(stats)
    }

  test("negative pool size raises NonPositivePoolSizeException") {
    val rsrc = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    interceptIO[IllegalArgumentException] {
      PooledResource[IO].of(rsrc, -1).use(_ => IO.never)
    }
  }

  test("zero pool size raises NonPositivePoolSizeException") {
    val rsrc = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    interceptIO[IllegalArgumentException] {
      PooledResource[IO].of(rsrc, 0).use(_ => IO.never)
    }
  }

  test("allocation raises (to the caller)") {
    val rsrc = Resource.make[IO, Int](AllocationFailure.raiseError[IO, Int])(_ => IO.unit)
    PooledResource[IO].of(rsrc, 1).use { pool =>
      interceptIO[AllocationFailure.type] {
        pool.use(_ => IO.never)
      }
    }
  }

  test("shutdown timeout") {
    val rsrc = Resource.make[IO, Int](1.pure[IO])(_ => IO.never)
    interceptMessageIO[TimeoutException]("Pool shutdown timed out after 1 millisecond with 1 resource(s) outstanding and unfinalized.") {
      PooledResourceBuilder
        .of(rsrc, 1)
        .withShutdownTimeout(1.milli)
        .build
        .use(pool => pool.use(_ => IO.unit))
    }
  }

  test("closed pool throws PoolClosedException") {
    val rsrc   = Resource.make[IO, Int](IO.never)(_ => IO.unit)
    val poolparty = PooledResource[IO].of(rsrc, 1)
    (1 to FiberCount).toList.parTraverse { _ =>
      interceptIO[PooledResource.PoolClosedException.type] {
        poolparty.use(_.pure[IO]).flatMap { leakedPool =>
          leakedPool.use(_ => IO.never)
        }
      }
    }
  }

  statsTest(
    "normal usage",
    _.identity,
    _.identity
  ) { stats =>
    // Sanity checks
    assertEquals(stats.allocations, PoolSize)
    assertEquals(stats.requests, FiberCount)
    assertEquals(stats.completions, FiberCount)
    assertEquals(stats.finalizationfailures, 0)
    assertEquals(stats.healthcheckfailures, 0)
    assertEquals(stats.recycles, FiberCount)
    assertEquals(stats.finalizations, stats.allocations)
  }

  statsTest(
    "normal usage when health check raises",
    _.identity,
    _.withHealthCheck(n => IO.raiseError[Boolean](HealthCheckException).whenA(n == 3).as(true))
  ) { stats =>
    // Sanity checks
    assertEquals(stats.requests, FiberCount)
    assertEquals(stats.completions, FiberCount)
    assertEquals(stats.finalizationfailures, 0)
    assertEquals(stats.finalizations, stats.allocations)
    // Actual Test
    assertEquals(stats.healthcheckfailures, 1)    // one health check failure ...
    assertEquals(stats.recycles, FiberCount - 1)  // precluding one recycle ...
    assertEquals(stats.allocations, PoolSize + 1) // and prompting an extra allocation
  }

  statsTest(
    "normal usage when health check fails",
    _.identity,
    _.withHealthCheck(n => (n != 3).pure[IO])
  ) { stats =>
    // Sanity checks
    assertEquals(stats.requests, FiberCount)
    assertEquals(stats.completions, FiberCount)
    assertEquals(stats.finalizationfailures, 0)
    assertEquals(stats.healthcheckfailures, 0)
    assertEquals(stats.finalizations, stats.allocations)
    // Actual Test
    assertEquals(stats.recycles, FiberCount - 1)  // one recycle was precluded ...
    assertEquals(stats.allocations, PoolSize + 1) // and prompting an extra allocation
  }

  statsTest(
    "normal usage when health check fails and finalizer raises",
    _.withFinalizer(n => IO.raiseError(FreeFailure).whenA(n == 3)),
    _.withHealthCheck(n => (n != 3).pure[IO])
  ) { stats =>
    // Sanity checks
    assertEquals(stats.requests, FiberCount)
    assertEquals(stats.completions, FiberCount)
    assertEquals(stats.healthcheckfailures, 0)
    // Actual Test
    assertEquals(stats.recycles, FiberCount - 1)  // one recycle was precluded ...
    assertEquals(stats.finalizationfailures, 1)      // and finalization failed ...
    assertEquals(stats.allocations, PoolSize + 1) // and prompting an extra allocation
    assertEquals(stats.finalizations, PoolSize)
  }

  statsTest(
    "normal usage when health check raises and finalizer raises",
    _.withFinalizer(n => IO.raiseError(FreeFailure).whenA(n == 3)),
    _.withHealthCheck(n => IO.raiseError[Boolean](HealthCheckException).whenA(n == 3).as(true))
  ) { stats =>
    // Sanity checks
    assertEquals(stats.requests, FiberCount)
    assertEquals(stats.completions, FiberCount)
    // Actual Test
    assertEquals(stats.healthcheckfailures, 1)    // one health check failure ...
    assertEquals(stats.finalizationfailures, 1)   // and a finalization failed ...
    assertEquals(stats.recycles, FiberCount - 1)  // precluded one recycle ...
    assertEquals(stats.allocations, PoolSize + 1) // and prompting an extra allocation
    assertEquals(stats.finalizations, PoolSize)
  }

}
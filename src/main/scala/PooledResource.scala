// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.pooled

import cats.effect._
import cats.effect.std.{ Console, Queue, PQueue }
import cats.effect.syntax.all._
import cats.syntax.all._
import scala.concurrent.duration._
import cats.effect.kernel.Outcome.Canceled
import cats.effect.kernel.Outcome.Errored
import cats.effect.kernel.Outcome.Succeeded
import cats.effect.std.Supervisor
import scala.io.AnsiColor
import cats.kernel.Order

/**
 * A pooled resource that allocates at most `poolSize` elements simultaneously before semantically
 * blocking callers of `use`. Elements are allocated lazily.
 * @param resource The underlying resource.
 * @param poolSize The maximum number of simultaneous allocations allowed for `resource`.
 * @param healthCheck A program that peforms a health check and possibly cleanup, yielding true if
 *   the element should be reused, or false if it should be finalized.
 * @param shutdownTimeout Maximum time to await finalization of all outstanding elements when the
 *   pool goes out of scope. If the timeout is reached an exception will be raised and finalization
 *   will be cancelled, leaking any outstanding elements.
 */
object PooledResource {

  object PoolClosedException
    extends IllegalStateException("The resource pool is closed.")

  def apply[F[_]: Temporal: Console: Clock] =
    new ApplyPartial[F]

  class ApplyPartial[F[_]: Temporal: Clock] {

    def of[A](
      resource: Resource[F, A],
      poolSize: Int,
      healthCheck: A => F[Boolean] = (_: A) => true.pure[F],
      reporter: PoolEvent[A] => F[Unit] = (e: PoolEvent[A]) => { println(e); Temporal[F].unit },
      shutdownTimeout: FiniteDuration = Long.MaxValue.nanos,
    ): Resource[F, Resource[F, A]] =
      for {
        _            <- Resource.eval(Temporal[F].raiseError(new IllegalArgumentException(s"Expected positive pool size, got $poolSize")).whenA(poolSize < 1))
        supervisor   <- Supervisor[F] // A supervisor for our child fibers
        running      <- Resource.eval(Ref[F].of(true)) // A flag indicating whether we're running or not
        requests     <- Resource.eval(Queue.unbounded[F, (PoolEvent.Request, Deferred[F, Either[Throwable, Allocated[F, A]]])])// Requests become deferrals completed with an error or an Allocated[F, A]
        allocations  <- Resource.eval(PQueue.unbounded[F, Option[Allocated[F, A]]](implicitly, Order.by(_.fold(1)(_ => 0)))) // Reponses is a queue of `poolSize` slots, initially all None
        _            <- List.fill(poolSize)(None).traverse(a => Resource.eval(allocations.offer(a)))
        counter      <- Resource.eval(Counter.zero[F])
        pool         <- { implicit val c = counter; make(resource, poolSize, healthCheck, shutdownTimeout, supervisor, running, requests, allocations, reporter) }
      } yield pool

  }

  private def make[F[_]: Temporal: Clock: Counter, A](
    resource: Resource[F, A],
    poolSize: Int,
    healthCheck: A => F[Boolean],
    shutdownTimeout: FiniteDuration,
    supervisor: Supervisor[F],
    running: Ref[F, Boolean],
    requests: Queue[F, (PoolEvent.Request, Deferred[F, Either[Throwable, Allocated[F, A]]])],
    allocations: PQueue[F, Option[Allocated[F, A]]],
    reporter: PoolEvent[A] => F[Unit],
  ): Resource[F, Resource[F, A]] = {

    def publish(e: PoolEvent[A]): F[Unit] =
      reporter(e) // TODO: queue up, make async

    // Acquisitions become deferrals that go into the request queue, then we wait. This
    // action becomes uncancelable when we pass it to `Resource.make`. Once we're shut
    // down nobody is watching the queue so we need to reject requests eagerly.
    val acquire: F[Allocated[F, A]] =
      PoolEvent.Request[F].flatTap(publish).flatMap { e =>
        running.get.flatMap {
          case true =>
            Deferred[F, Either[Throwable, Allocated[F, A]]]
              .flatTap(d => requests.offer((e, d)))
              .flatMap(_.get.flatMap(_.liftTo[F]))
          case false =>
            PoolClosedException.raiseError[F, Allocated[F, A]]
        }
      }

    // Releases are processed asynchronously. No waiting. This computation is a resource allocator
    // and cannot be canceled.
    val release: (Allocated[F, A]) => F[Unit] = {a =>
      publish(PoolEvent.Release(a.identifier, a.value)) >>
      supervisor.supervise {
        healthCheck(a.value)
          .handleErrorWith { e =>

            // Health check failed with an error. Report it and yield `false` to trigger disposal.
            publish(PoolEvent.HealthCheckFailure(a.identifier, a.value, e))
              .as(false)

          }
          .flatMap {
            case true  =>

              // Resource is ok. Report and add it to the response queue.
              publish(PoolEvent.Recycle(a.identifier, a.value)) >>
              allocations.offer(Some(a))

            case false =>

              // Health check failed with an error or returned false. So finalize the resource and
              // add an empty slot to the response queue.
              a.finalizer
                .handleErrorWith { e =>

                  // Finalizer failed with an error. Report it, that's all we can do!
                  publish(PoolEvent.FinalizerFailure(a.identifier, a.value, e))

                }
                .guarantee(allocations.offer(None))
          }
          .uncancelable // We don't want supervisor shutdown to interrupt this operation.
        } .void         // Fire and forget.
    }

    // Our main loop awaits a request, awaits a response, completes the request, and loops. The
    // fiber running `main` will be canceled before `shutdown` is run.
    val main: F[Unit] = {
      requests.take.flatMap { case (e, req) =>
        allocations.take.flatMap {
          case Some(a) =>
            e.completion[F, A](a.identifier, a.value).flatMap(publish) >>
            req.complete(Right(a)).void
          case None    =>
            // allocate on another fiber
            // Console[F].println("response requires allocation") >>
            supervisor.supervise {
              Allocated(resource).attempt.flatMap {
                case Right(a) =>
                  e.completion[F, A](a.identifier, a.value).flatMap(publish) >>
                  req.complete(Right(a)).void
                case Left(t)  =>
                  // allocation failed
                  // Console[F].println("allocation failed") >>
                  allocations.offer(None) >> req.complete(Left(t)).void
              } .uncancelable // we don't want shutdown to interrupt this
            } .void
        }
      }
     } .foreverM

    // This is a resource finalizer and cannot be canceled.
    val shutdown: F[Unit] = {

      // Reject all remaining requests.
      lazy val reject: F[Unit] =
        requests
          .take
          .flatMap(_._2.complete(Left(PoolClosedException)))
          .whileM_(requests.size.map(_ > 0))

      // Remove `remaining` slots from the response queue, finalizing non-empty ones.
      def dispose(remaining: Int): F[Unit] =
        if (remaining == 0) {
          // done finalizing
          // Console[F].println(s"shutdown: complete") >>
          Concurrent[F].unit
        } else {
          // Console[F].println(s"shutdown: remaining = $remaining") >>
          allocations.take.flatMap {
            case None =>
              // Console[F].println(s"shutdown: empty slot") >>
              Concurrent[F].unit >> dispose(remaining - 1)
            case Some(a) =>
              // Console[F].println(s"shutdown: finalizing $a") >>
              a.finalizer.handleErrorWith { _ =>
                // Console[F].println(s"shutdown: finalization failed") >>
                // finalization failed, log and continue
                Concurrent[F].unit
              } >> dispose(remaining - 1)
          }
        }

      // Ensure that no more requests will be enqueued, then reject all outstanding requests
      // and dispose of any allocations, expecting exactly `poolSize`. On timeout we leak!
      // Console[F].println(s"shutdown: shutdown starting now.") >>
      (running.set(false) >> reject >> dispose(poolSize))
        .timeoutTo(
          shutdownTimeout,
          new RuntimeException("shutdown timed out").raiseError[F, Unit]
        )

    }

    // Our final resource. Note that we do `f.cancel >> shutdown` rather than `main.onCancel(shutdown)`
    // because fiber finalizers don't seem to run if the fiber has never been scheduled.
    Resource.make(supervisor.supervise(main))(f => f.cancel >> shutdown) as
    Resource.make(acquire)(release).map(_.value)

  }

}
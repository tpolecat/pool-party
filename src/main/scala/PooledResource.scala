// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats.Order
import cats.effect.{ Deferred, Ref, Resource, Temporal }
import cats.effect.std.{ PQueue, Queue, Supervisor }
import cats.effect.syntax.all._
import cats.syntax.all._
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.io.AnsiColor

/** Module of constructors for pooled `Resource`s. */
object PooledResource {

  object PoolClosedException
    extends IllegalStateException("The resource pool is closed.")

  def apply[F[_]: Temporal] =
    new ApplyPartial[F]

  final class ApplyPartial[F[_]: Temporal] {

    /**
     * @param resource The underlying resource.
     * @param poolSize The maximum number of simultaneous allocations allowed for `resource`.
     * @param healthCheck A program that peforms a health check and possibly cleanup, yielding true if
     *   the element should be reused, or false if it should be finalized.
     * @param reporter a callback to be invoked _synchronously_ when events of interest happen in
     *   the pool. A well-behaved reporter would typically add the event to a queue for asynchronous
     *   processing.
     * @param shutdownTimeout Maximum time to await finalization of all outstanding elements when the
     *   pool goes out of scope. If the timeout is reached an exception will be raised and finalization
     *   will be cancelled, leaking any outstanding elements.
     */
    def of[A](
      resource: Resource[F, A],
      poolSize: Int,
      healthCheck: A => F[Boolean] = (_: A) => true.pure[F],
      reporter: PoolEvent[A] => F[Unit] = (_: PoolEvent[A]) => Temporal[F].unit,
      shutdownTimeout: FiniteDuration = Long.MaxValue.nanos,
    ): Resource[F, Resource[F, A]] =
      for {
        _            <- Resource.eval(Temporal[F].raiseError(new IllegalArgumentException(s"Expected positive pool size, got $poolSize")).whenA(poolSize < 1))
        supervisor   <- Supervisor[F] // A supervisor for our child fibers
        running      <- Resource.eval(Ref[F].of(true)) // A flag indicating whether we're running or not
        requests     <- Resource.eval(Queue.unbounded[F, (PoolEvent.Request, Deferred[F, Either[Throwable, Allocated[F, A]]])]) // Requests become deferrals completed with an error or an Allocated[F, A]
        allocations  <- Resource.eval(PQueue.unbounded[F, Option[Allocated[F, A]]](implicitly, Order.by(_.fold(1)(_ => 0)))) // Reponses is a queue of `poolSize` slots, initially all None
        _            <- List.fill(poolSize)(Option.empty[Allocated[F, A]]).traverse(a => Resource.eval(allocations.offer(a)))
        counter      <- Resource.eval(Counter.zero[F])
        pool         <- { implicit val c = counter; make(resource, poolSize, healthCheck, shutdownTimeout, supervisor, running, requests, allocations, reporter) }
      } yield pool

  }

  private def make[F[_]: Temporal: Counter, A](
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

    // We publish events synchronously. The user gets can queue them up or whatever if it's
    // important, but it's much easier to externalize than try to manage clean shutdown while
    // ensuring that all events have been observed.
    def publish(e: PoolEvent[A]): F[Unit] =
      reporter(e).handleError { e =>
        // If this happens it's really not our problem, but we don't want to break the pool. Let's
        // dump stack as a side-effect and hope someone sees it and keep on moving.
        new Exception("Pool reporter raised an exception.", e).printStackTrace().pure[F]
      }

    // Acquisitions become deferrals that go into the request queue, then we wait. This action
    // becomes uncancelable when we pass it to `Resource.make`. Once we're shut down nobody is
    // watching the queue so we need to reject requests eagerly (this can only happen if someone is
    // naughty and leaks a reference to the pool).
    val acquire: F[Allocated[F, A]] =
      PoolEvent.Request[F].flatTap(publish).flatMap { e =>
        running.get.flatMap {
          case true =>
            Deferred[F, Either[Throwable, Allocated[F, A]]]
              .flatTap(d => requests.offer((e, d)))
              .flatMap(_.get.flatMap(_.liftTo[F]))
          case false =>
            PoolClosedException.raiseError
        }
      }

    // Releases are processed asynchronously. No waiting. This computation is a resource finalizer
    // and cannot be canceled.
    val release: (Allocated[F, A]) => F[Unit] = a =>
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
              // Health check returned false (possibly due to an error, see above). Finalize the
              // resource and add an empty slot to the response queue.
              (a.finalizer *> publish(PoolEvent.Finalize(a.identifier, a.value)))
                .handleErrorWith { e =>
                  // Finalizer failed with an error. Report it, that's all we can do!
                  publish(PoolEvent.FinalizerFailure(a.identifier, a.value, e))
                }
                .guarantee(allocations.offer(None))
          }
          .uncancelable // We don't want supervisor shutdown to interrupt this operation.
      } .void  // Fire and forget.

    // Our main loop awaits a request, awaits a response, completes the request, and loops. The
    // fiber running `main` will be canceled before `shutdown` is run.
    val main: F[Unit] = {
      requests.take.flatMap { case (e, req) =>
        allocations.take.flatMap {
          case Some(a) =>
            // A resource is available in the queue, so report it and complete the request.
            e.completion[F, A](a.identifier, a.value).flatMap(publish) >>
            req.complete(Right(a)).void
          case None    =>
            // No active resources are available, but we have an empty slot to allocate a new on,
            // so do it now on another fiber. This will prevent us from blocking access to other
            // resources that may be returned to the queue while we're doing the allocation.
            // TODO: report
            supervisor.supervise {
              Allocated(resource).attempt.flatMap {
                case Right(a) =>
                  // Allocation succeeded. Report it and complete the request.
                  publish(PoolEvent.Allocation(e.requestId, a.identifier, a.value)) >>
                  e.completion[F, A](a.identifier, a.value).flatMap(publish) >>
                  req.complete(Right(a)).void
                case Left(t)  =>
                  // Allocation failed with an exception. Return the empty slot to the queue and
                  // complete the request with an error.
                  // TODO: report
                  allocations.offer(None) >> req.complete(Left(t)).void
              } .uncancelable // We don't want shutdown to interrupt this operation.
            } .void           // Fire and forget.
        }
      }
     } .foreverM // Do this until we're cancelled.

    // This is a resource finalizer and it cannot be canceled. The `main` fiber will be cancelled
    // by the time we get here.
    val shutdown: F[Unit] = {

      // Reject all remaining requests. The `running` flag will be set to false before we get here,
      // so we know that no new requests will be arriving and we can drain the queue.
      lazy val reject: F[Unit] =
        requests
          .take
          .flatMap(_._2.complete(Left(PoolClosedException)))
          .whileM_(requests.size.map(_ > 0))

      // Remove `remaining` slots from the response queue, finalizing non-empty ones. We wait for
      // all the allocations to be returned to the queue.
      def dispose(remaining: Ref[F, Int]): F[Unit] = {
        lazy val go: F[Unit] =
          remaining.get.flatMap {
            case 0 =>
              // All allocations have been accounted for. Shutdown is complete!
              // TODO: report
              Temporal[F].unit
            case n =>
              // Get an allocation from the queue, waiting if necessary.
              allocations.take.flatMap {
                case None =>
                  // The slot was empty so there's nothing to finalizee. Loop!
                  // TODO: report
                  remaining.update(_ - 1) >> go
                case Some(a) =>
                  // Finalize the resource.
                  (a.finalizer >> publish(PoolEvent.Finalize(a.identifier, a.value))).handleErrorWith { e =>
                    publish(PoolEvent.FinalizerFailure(a.identifier, a.value, e)) // Nothing else we can do
                  } >> remaining.update(_ - 1) >> go
              }
          }
        go
      }

      // Ensure that no more requests will be enqueued, then reject all outstanding requests
      // and dispose of any allocations, expecting exactly `poolSize`. On timeout we leak!
      Ref[F].of(poolSize).flatMap { remaining =>
        (running.set(false) >> reject >> dispose(remaining))
          .timeoutTo(
            shutdownTimeout,
            remaining.get.flatMap { n =>
              new TimeoutException(s"Pool shutdown timed out after $shutdownTimeout with $n resource(s) outstanding and unfinalized.").raiseError
            }
          )
      }

    }

    // Our final resource. Note that we do `f.cancel >> shutdown` rather than `main.onCancel(shutdown)`
    // because fiber finalizers don't seem to run if the fiber has never been scheduled.
    Resource.make(supervisor.supervise(main))(f => f.cancel >> shutdown) as
    Resource.make(acquire)(release).map(_.value)

  }

}
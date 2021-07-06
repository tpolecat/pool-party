// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats._
import cats.effect.{ Clock, Resource }
import cats.syntax.all._

import java.time.Instant
import scala.concurrent.duration._

/** Pool lifecycle events, for pools that yield values of type `A`. */
sealed trait PoolEvent[+A]
object PoolEvent {

  /**
   * Event indicating that an instance has been requested (typicallly via a call to `.use`).
   * @param requestId unique identifier associated with this request
   * @param created point in time when the request was made
   */
  case class Request(requestId: Long, created: Instant) extends PoolEvent[Nothing] {
    private[poolparty] def completion[F[_]: Functor: Clock, A](instanceId: Long, instance: A): F[Completion[A]] =
      Clock[F].realTime.map(t => Completion(requestId, instanceId, instance, t - created.toEpochMilli.millis))
  }
  object Request {
    private[poolparty] def apply[F[_]: Apply: Counter: Clock]: F[Request] =
      (Counter[F].next, Clock[F].realTime.map(d => Instant.ofEpochMilli(d.length))).mapN(Request(_, _))
  }

  /**
   * Event indicating that an instance has been allocated from the underlying resource.
   * @param requestId unique identifier associated with this request
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   */
  case class Allocation[A](requestId: Long, instanceId: Long, instance: A) extends PoolEvent[A]

  /**
   * Event indicating that a request has been completed with the given instance. The `elapsedTime`
   * member indicates how long the requesting fiber was blocked awaiting availability.
   * @param requestId unique identifier associated with this request
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   * @param elapsedTime elapsed time between the request and its completion
   */
  case class Completion[A](requestId: Long, instanceId: Long, instance: A, elapsedTime: FiniteDuration) extends PoolEvent[A]

  /**
   * Event indicating that an instance has been released (typically via a call to `.use`
   * completing).
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   */
  case class Release[A](instanceId: Long, instance: A) extends PoolEvent[Nothing]

  /**
   * Event indicating that a released instance is still valid and has been placed back in the pool.
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   */
  case class Recycle[A](instanceId: Long, instance: A) extends PoolEvent[Nothing]

  /**
   * Event indicating that the health check for a released instance has failed with an exception.
   * The associated instance will be finalized if possible.
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   * @param exception the associated failure
   */
  case class HealthCheckFailure[A](instanceId: Long, instance: A, exception: Throwable) extends PoolEvent[A]

  /**
   * Event indicating that a released instance has been successfully finalized, either because the
   * health check yieled `false` or because the pool itself is being shut down.
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   */
  case class Finalize[A](instanceId: Long, instance: A) extends PoolEvent[A]

  /**
   * Event indicating that finalization for a released instance has failed with an exception, which
   * may or may not be a problem, depending on the underlying resource. The associated instance will
   * be discarded.
   * @param instanceId unique identifier associated with this instance
   * @param instance the pooled instance
   * @param exception the associated failure
   */
  case class FinalizerFailure[A](instanceId: Long, instance: A, exception: Throwable) extends PoolEvent[A]

}
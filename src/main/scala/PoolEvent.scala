package org.tpolecat.pooled

import cats.effect.kernel.Resource
import scala.concurrent.duration._
import cats._
import cats.effect.kernel.Clock
import cats.syntax.all._
import java.time.Instant

sealed trait PoolEvent[+A]

object PoolEvent {

  case class Request(requestId: Long, created: Instant) extends PoolEvent[Nothing] {
    def completion[F[_]: Functor: Clock, A](resourceId: Long, resource: A): F[Completion[A]] =
      Clock[F].realTime.map(t => Completion(requestId, resourceId, resource, t - created.toEpochMilli.millis))
  }
  object Request {
    def apply[F[_]: Apply: Counter: Clock]: F[Request] =
      (Counter[F].next, Clock[F].realTime.map(d => Instant.ofEpochMilli(d.length))).mapN(Request(_, _))
  }

  case class Completion[A](requestId: Long, resourceId: Long, resource: A, elapsedTime: FiniteDuration) extends PoolEvent[A]

  case class Release[A](resourceId: Long, resource: A) extends PoolEvent[Nothing]

  case class Recycle[A](resourceId: Long, resource: A) extends PoolEvent[Nothing]

  case class HealthCheckFailure[A](resourceId: Long, resource: A, exception: Throwable) extends PoolEvent[A]

  case class FinalizerFailure[A](resourceId: Long, resource: A, exception: Throwable) extends PoolEvent[A]

}
package org.tpolecat.pooled

import cats.effect._
import cats.syntax.all._

sealed abstract case class Allocated[F[_], A](
  value: A,
  finalizer: F[Unit],
  identifier: Long,
)

object Allocated {

  def apply[F[_]: MonadCancelThrow: Counter, A](resource: Resource[F, A]): F[Allocated[F, A]] =
    resource.allocated.flatMap { case (a, f) =>
      Counter[F].next.map { id =>
        new Allocated(a, f, id) {}
      }
    }

}
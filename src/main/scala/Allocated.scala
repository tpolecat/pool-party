// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats.effect._
import cats.syntax.all._

private[poolparty] sealed abstract case class Allocated[F[_], A](
  value: A,
  finalizer: F[Unit],
  identifier: Long,
)

private[poolparty] object Allocated {

  def apply[F[_]: MonadCancelThrow: Counter, A](resource: Resource[F, A]): F[Allocated[F, A]] =
    resource.allocated.flatMap { case (a, f) =>
      Counter[F].next.map { id =>
        new Allocated(a, f, id) {}
      }
    }

}
// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats.Functor
import cats.effect._
import cats.syntax.all._

private[poolparty] trait Counter[F[_]] {
  def next: F[Long]
}

private[poolparty] object Counter {

  def apply[F[_]](implicit ev: Counter[F]): ev.type = ev

  def zero[F[_]: Temporal]: F[Counter[F]] =
    Ref[F].of(0L).map { ref =>
      new Counter[F] {
        val next = ref.getAndUpdate(_ + 1L)
      }
    }

}
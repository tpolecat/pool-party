// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats.Functor
import cats.effect._
import cats.syntax.all._

trait Counter[F[_]] {
  def next(key: String): F[Long]
}

object Counter {

  def apply[F[_]](implicit ev: Counter[F]): ev.type = ev

  def zero[F[_]: Functor: Ref.Make]: F[Counter[F]] =
    Ref[F].of(Map.empty[String, Long]).map { ref =>
      new Counter[F] {
        def next(key: String) = ref.modify { m =>
          m.get(key) match {
            case Some(n) => (m + (key -> (n + 1L)), n)
            case None    => (m + (key -> 1L), 0L)
          }
        }
      }
    }

}
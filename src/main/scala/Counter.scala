package org.tpolecat.pooled

import cats.Functor
import cats.effect.Ref
import cats.syntax.all._

/** Capability for effects that can count. */
trait Counter[F[_]] {
  def next: F[Long]
}

object Counter {

  def apply[F[_]](implicit ev: Counter[F]): ev.type = ev

  def zero[F[_]: Functor: Ref.Make]: F[Counter[F]] =
    Ref[F].of(0L).map { ref =>
      new Counter[F] {
        val next = ref.getAndUpdate(_ + 1L)
      }
    }

}
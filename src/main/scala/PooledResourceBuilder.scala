// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package org.tpolecat.poolparty

import cats.effect.{ Resource, Temporal }
import scala.concurrent.duration._

/**
 * A builder for pooled resources. Use `PooledResourceBuilder.of(rsrc, size)` to construct an
 * instance.
 */
final case class PooledResourceBuilder[F[_]: Temporal, A] private (
  resource: Resource[F, A],
  poolSize: Int,
  healthCheck: A => F[Boolean],
  reporter: PoolEvent[A] => F[Unit],
  shutdownTimeout: FiniteDuration,
) {

  /** This `PooledResourceBuilder`. */
  def identity: this.type =
    this

  /**
   * A new `PooledResourceBuilder` with the given health check (the default health check always
   * yields `true`).
   */
  def withHealthCheck(check: A => F[Boolean]): PooledResourceBuilder[F, A] =
    copy(healthCheck = check)

  /**
   * A new `PooledResourceBuilder` with the given shutdown timeout (the default shutdown timeout is
   * the largest `FiniteDuration` that will work on Scala-JS, which is about 24 days).
   */
  def withShutdownTimeout(timeout: FiniteDuration):  PooledResourceBuilder[F, A] =
    copy(shutdownTimeout = timeout)

  /**
   * A new `PooledResourceBuilder` with the given event reporter (the default reporter does nothing
   * and yields the `Unit` value).
   */
  def withReporter(reporter: PoolEvent[A] => F[Unit]):  PooledResourceBuilder[F, A] =
    copy(reporter = reporter)

  /**
   * Eliminate this `PooledResourceBuilder` and construct the specified pooled resource.
   */
  def build: Resource[F, Resource[F, A]] =
    PooledResource[F].of(resource, poolSize, healthCheck, reporter, shutdownTimeout)

}

object PooledResourceBuilder {

  /**
   * Construct a `PooledResourceBuilder` for the given resource, with a given size (which must be
   * positive, otherwise an `IllegalArgumentException` will be raised in `F` when the resulting
   * pool is first used). See the various `with...` methods for information on configurable
   * properties.
   * @param resource the underlying resource
   * @param poolSize maximum number of instances of type `A` that will be in use concurrently
   */
  def of[F[_]: Temporal, A](resource: Resource[F, A], poolSize: Int): PooledResourceBuilder[F, A] =
    PooledResourceBuilder(
      resource,
      poolSize,
      (_: A) => Temporal[F].pure(true),
      (_: PoolEvent[A]) => Temporal[F].unit,
      Int.MaxValue.millis
    )

}
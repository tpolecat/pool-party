# Pool Party!

[![Discord](https://img.shields.io/discord/632277896739946517.svg?label=&logo=discord&logoColor=ffffff&color=404244&labelColor=6A7EC2)](https://sca.la/typeleveldiscord)
[![Maven Central](https://img.shields.io/maven-central/v/org.tpolecat/pool-party_2.13.svg)](https://maven-badges.herokuapp.com/maven-central/org.tpolecat/pool-party_2.13)
[![Javadocs](https://javadoc.io/badge/org.tpolecat/pool-party_2.13.svg)](https://javadoc.io/doc/org.tpolecat/pool-party_2.13)


![](pool.jpg)


> Photo by [James Lee](https://unsplash.com/@picsbyjameslee) on [Unsplash](https://unsplash.com).

**Pool Party** is a Scala micro-library that turns a regular `cats.effect.Resource` into a
non-blocking, fixed-size pooled resource with optional health checking and event reporting.


```scala
val r: Resource[F, Woozle] = ... // An limited resource

PooledResourceBuilder.of(r, 10).build.use { pool =>
  // `pool` has the same apparent behavior as `r`, but will never allow more than
  // 10 concurrent `Woozle`s, which will be recycled and reused on return.
}
```

In most cases you will create when your program starts, and it will not be finalized until the program shuts down. In the common case for a web app your `IOApp.run` will look something like:

```scala
// A server is usually a resource you hang onto until someone hits ^C.
def run(args: List[String]): IO[ExitCode] =
  runResource[IO](args).use(_ => IO.never)

// That resource is the composition of many things, incluing our pool.
def runResource[F[_]: Monad: OtherStuff](args: List[String]): Resource[F, ExitCode] =
  for {
    ...
    pool <- PoolBuilder.of(...).withHealthCheck(...).withReporter(...).build
    ...  <- httpServerResource(pool, etc) // request handler can say pool.use
  } yield ExitCode.Success
```

What you **don't** want to do is create a new pool for each request. It's a common error that
defeats the purpose of pooling.

### Configuration

In addition to pool size (required), `PooledResourceBuilder` provides the following configurable properties:

| Property | Type | Explanation |
|---|---|--|
|`HealthCheck` | `A => F[Boolean]` | Check the health of a pooled instance after it's returned to the pool, and perform any necessary cleanup. Yield `false` if the instance should be finalized and discarded. Default behavior always yields `true`. |
|`ShutdownTimeout` | `FiniteDuration` | Amount of time to wait for pooled instances to be finalized when the pool shuts down by leaving `.use` scope. Default is `Long.MaxValue.ns` (~292 years). |
|`Reporter` | `PoolEvent[A] => F[Unit]` | Callback that will receive events about the pool's lifecycle, including error conditions that you probably want to know about. So it's usually a good idea to specify this. The default behavior does nothing. |

### Getting Help

First check out the [Scaladoc](https://javadoc.io/doc/org.tpolecat/pool-party_2.13), and if you're
still in trouble please open an issue of find me on the [Typelevel Discord](https://sca.la/typeleveldiscord).

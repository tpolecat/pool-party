// Copyright (c) 2021 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package tests

import cats.effect.IO
import munit.CatsEffectSuite
import org.tpolecat.poolparty.Counter

class CounterSuite extends CatsEffectSuite {

  test("counter behavior") {
    for {
      c <- Counter.zero[IO]
      _ <- assertIO(c.next("foo"), 0L)
      _ <- assertIO(c.next("foo"), 1L)
      _ <- assertIO(c.next("bar"), 0L)
    } yield ()
  }

}
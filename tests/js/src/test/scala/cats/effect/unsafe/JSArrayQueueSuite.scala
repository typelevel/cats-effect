/*
 * Copyright 2020-2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect
package unsafe

import org.scalacheck.Prop.forAll

import scala.collection.mutable.{ListBuffer, Queue}

class JSArrayQueueSuite extends BaseScalaCheckSuite {

  property("be fifo") {
    forAll { (stuff: List[Option[Int]]) =>
      val queue = new JSArrayQueue[Int]
      val taken = new ListBuffer[Int]

      stuff.foreach {
        case Some(i) => queue.offer(i)
        case None =>
          if (!queue.isEmpty()) taken += queue.take()
      }

      while (!queue.isEmpty()) taken += queue.take()

      assertEquals(taken.toList, stuff.flatten)
    }
  }

  property("iterate over contents in foreach") {
    forAll { (stuff: List[Option[Int]]) =>
      val queue = new JSArrayQueue[Int]
      val shadow = new Queue[Int]

      def checkContents() = {
        val builder = List.newBuilder[Int]
        queue.foreach(builder += _)
        assertEquals(builder.result(), shadow.toList)
      }

      checkContents()

      stuff.foreach {
        case Some(i) =>
          queue.offer(i)
          shadow.enqueue(i)
          checkContents()
        case None =>
          if (!shadow.isEmpty) {
            val got = queue.take()
            val expected = shadow.dequeue()
            assertEquals(got, expected)
            checkContents()
          } else {
            ()
          }
      }
    }
  }

}

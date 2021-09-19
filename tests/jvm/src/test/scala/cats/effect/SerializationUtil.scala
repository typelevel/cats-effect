/*
 * Copyright 2020-2021 Typelevel
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

import scala.util.Try

object SerializationUtil {
  import java.io._
  import java.util.Base64

  def serialize(`object`: Serializable): Try[String] = {
    val baos = new ByteArrayOutputStream
    val oos = new ObjectOutputStream(baos)

    val result = Try {
      oos.writeObject(`object`)
      Base64.getEncoder.encodeToString(baos.toByteArray)
    }

    baos.close()
    oos.close()

    result
  }

  def deserialize[T <: Serializable](objectAsString: String): Try[T] = {
    val data = Base64.getDecoder.decode(objectAsString)
    val ois = new ObjectInputStream(new ByteArrayInputStream(data))

    val result = Try(ois.readObject.asInstanceOf[T])

    ois.close()

    result
  }
}

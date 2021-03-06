/*
 * Copyright (c) 2017 by Andrew Charneski.
 *
 * The author licenses this file to you under the
 * Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy
 * of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package storage.cold

import storage.Restm._

import scala.collection.concurrent.TrieMap

class HeapColdStorage extends ColdStorage {
  val mem = new TrieMap[PointerType, TrieMap[TimeStamp, ValueType]]()

  def store(id: PointerType, data: Map[TimeStamp, ValueType]): Unit = {
    mem.getOrElseUpdate(id, new TrieMap[TimeStamp, ValueType]) ++= data
  }

  def read(id: PointerType): Map[TimeStamp, ValueType] = {
    mem.getOrElseUpdate(id, new TrieMap[TimeStamp, ValueType]).toMap
  }

  override def clear(): Unit = mem.clear()
}

/*-
 * Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

 */
package org.apache.griffin.measure.connector

import org.apache.spark.rdd.RDD

import scala.util.Try


trait BatchDataConnector extends DataConnector {

  def metaData(): Try[Iterable[(String, String)]]

  def data(): Try[RDD[(Product, Map[String, Any])]]

}

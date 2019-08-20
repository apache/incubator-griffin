/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package org.apache.griffin.measure.sink

import com.holdenkarau.spark.testing.DataFrameSuiteBase
import org.apache.spark.sql.DataFrame
import org.apache.spark.sql.Row
import org.apache.spark.sql.types._
import org.scalatest.FlatSpec
import org.scalatest.Matchers

import org.apache.griffin.measure.Loggable
import org.apache.griffin.measure.configuration.dqdefinition.SinkParam
import org.apache.griffin.measure.configuration.enums.BatchProcessType
import org.apache.griffin.measure.context.{ContextId, DQContext}

trait SinkTestBase extends FlatSpec with Matchers with DataFrameSuiteBase with Loggable {

  var sinkParams: Seq[SinkParam]

  def getDqContext(name: String = "test-context"): DQContext = {
    DQContext(
      ContextId(System.currentTimeMillis),
      name,
      Nil,
      sinkParams,
      BatchProcessType
    )(spark)
  }


  def createDataFrame(arr: Seq[Int]): DataFrame = {
    val schema = StructType(Array(
      StructField("id", LongType),
      StructField("name", StringType),
      StructField("sex", StringType),
      StructField("age", IntegerType)
    ))
    val rows = arr.map { i =>
      Row(i.toLong, s"name_$i", if (i % 2 == 0) "man" else "women", i + 15)
    }
    val rowRdd = sqlContext.sparkContext.parallelize(rows)
    sqlContext.createDataFrame(rowRdd, schema)
  }
}

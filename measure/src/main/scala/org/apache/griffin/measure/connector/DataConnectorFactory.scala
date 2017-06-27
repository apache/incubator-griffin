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

import kafka.serializer.StringDecoder
import org.apache.griffin.measure.config.params.user._
import org.apache.griffin.measure.rule.RuleExprs
import org.apache.griffin.measure.rule.expr._
import org.apache.spark.sql.SQLContext
import org.apache.spark.streaming.StreamingContext
import org.apache.spark.streaming.dstream.InputDStream
import org.apache.spark.streaming.kafka.KafkaUtils

import scala.reflect.ClassTag
import scala.util.Try

object DataConnectorFactory {

  val HiveRegex = """^(?i)hive$""".r
  val AvroRegex = """^(?i)avro$""".r

  def getBatchDataConnector(sqlContext: SQLContext,
                            dataConnectorParam: DataConnectorParam,
                            ruleExprs: RuleExprs,
                            globalFinalCacheMap: Map[String, Any]
                           ): Try[BatchDataConnector] = {
    val conType = dataConnectorParam.conType
    val version = dataConnectorParam.version
    val config = dataConnectorParam.config
    Try {
      conType match {
        case HiveRegex() => HiveBatchDataConnector(sqlContext, config, ruleExprs, globalFinalCacheMap)
        case AvroRegex() => AvroBatchDataConnector(sqlContext, config, ruleExprs, globalFinalCacheMap)
        case _ => throw new Exception("connector creation error!")
      }
    }
  }

  def getStreamingDataConnector(ssc: StreamingContext,
                                dataConnectorParam: DataConnectorParam,
                                ruleExprs: RuleExprs,
                                globalFinalCacheMap: Map[String, Any]
                               ): Try[StreamingDataConnector] = {
    val conType = dataConnectorParam.conType
    val version = dataConnectorParam.version
    val config = dataConnectorParam.config
    Try {
      conType match {
        case HiveRegex() => {
          val KeyType = "key.type"
          val ValueType = "value.type"
          val keyType = dataConnectorParam.config.getOrElse(KeyType, "java.lang.String").toString
          val valueType = dataConnectorParam.config.getOrElse(ValueType, "java.lang.String").toString
          (getClassTag(keyType), getClassTag(valueType)) match {
            case (ClassTag(k: Class[String]), ClassTag(v: Class[String])) => {
              new KafkaDataConnector(ssc, config) {
                type K = String
                type KD = StringDecoder
                type V = String
                type VD = StringDecoder

                def stream(): Try[InputDStream[(K, V)]] = Try {
                  val topicSet = topics.split(",").toSet
                  KafkaUtils.createDirectStream[K, V, KD, VD](
                    ssc,
                    kafkaConfig,
                    topicSet
                  )
                }
              }
            }
          }
        }
        case _ => throw new Exception("connector creation error!")
      }
    }
  }

  private def getClassTag(tp: String): ClassTag[_] = {
    try {
      val clazz = Class.forName(tp)
      ClassTag(clazz)
    } catch {
      case e: Throwable => throw e
    }
  }

}

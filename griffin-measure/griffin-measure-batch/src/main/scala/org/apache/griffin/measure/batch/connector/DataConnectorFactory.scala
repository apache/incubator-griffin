package org.apache.griffin.measure.batch.connector

import org.apache.griffin.measure.batch.config.params.user._
import org.apache.griffin.measure.batch.rule.expr._
import org.apache.spark.sql.SQLContext

import scala.util.Try

object DataConnectorFactory {

  val HiveRegex = """^(?i)hive$""".r
  val AvroRegex = """^(?i)avro$""".r

  def getDataConnector(sqlContext: SQLContext,
                       dataConnectorParam: DataConnectorParam,
                       keyExprs: Seq[DataExpr],
                       dataExprs: Iterable[DataExpr]
                      ): Try[DataConnector] = {
    val conType = dataConnectorParam.conType
    val version = dataConnectorParam.version
    Try {
      conType match {
        case HiveRegex() => HiveDataConnector(sqlContext, dataConnectorParam.config, keyExprs, dataExprs)
        case AvroRegex() => AvroDataConnector(sqlContext, dataConnectorParam.config, keyExprs, dataExprs)
        case _ => throw new Exception("connector creation error!")
      }
    }
  }

}

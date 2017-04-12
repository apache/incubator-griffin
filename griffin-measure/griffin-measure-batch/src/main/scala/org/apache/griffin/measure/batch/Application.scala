package org.apache.griffin.measure.batch

import org.apache.griffin.measure.batch.algo.{Algo, BatchAccuracyAlgo}
import org.apache.griffin.measure.batch.config.params._
import org.apache.griffin.measure.batch.config.params.env._
import org.apache.griffin.measure.batch.config.params.user._
import org.apache.griffin.measure.batch.config.reader._
import org.apache.griffin.measure.batch.config.validator.AllParamValidator
import org.apache.griffin.measure.batch.log.Loggable

import scala.util.{Failure, Success, Try}

object Application extends Loggable {

  def main(args: Array[String]): Unit = {
    if (args.length < 2) {
      error("Usage: class <env-param-file> <user-param-file> [file-system-type: local or hdfs(default)]")
      sys.exit(-1)
    }

    val paramFiles: Array[String] = args.take(2)
    val fsType = if (args.length > 2) args(2) else "hdfs"

    // read param files
    val allParam: AllParam = readParamFiles(paramFiles, fsType) match {
      case Some(a) => a
      case _ => {
        error("param file read error!")
        sys.exit(-2)
      }
    }

    // validate param files
    validateParams(allParam) match {
      case Failure(ex) => {
        error(ex.getMessage)
        sys.exit(-3)
      }
      case _ => {
        info("params validation pass")
      }
    }

    // choose algorithm   // fixme: not done, need to choose algorithm by param
    val dqType = allParam.userParam.dqType
    val algo: Algo = BatchAccuracyAlgo(allParam)

    algo.run match {
      case Failure(ex) => {
        error(ex.getMessage)
        sys.exit(-4)
      }
      case _ => {
        info("calculation finished")
      }
    }
  }

  private def readParamFiles(files: Array[String], fsType: String): Option[AllParam] = {
    val Array(envParamFile, userParamFile) = files

    // read config files
    val envParamReader = ParamReaderFactory.getParamReader(envParamFile, fsType)
    val envParamTry = envParamReader.readConfig[EnvParam]
    val userParamReader = ParamReaderFactory.getParamReader(userParamFile, fsType)
    val userParamTry = userParamReader.readConfig[UserParam]

    (envParamTry, userParamTry) match {
      case (Success(e), Success(u)) => Some(AllParam(e, u))
      case _ => None
    }
  }

  private def validateParams(allParam: AllParam): Try[Boolean] = {
    val allParamValidator = AllParamValidator()
    allParamValidator.validate(allParam)
  }

}

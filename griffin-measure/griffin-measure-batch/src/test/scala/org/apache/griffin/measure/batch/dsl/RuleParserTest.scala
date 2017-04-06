package org.apache.griffin.measure.batch.dsl

import org.junit.runner.RunWith
import org.scalatest.{BeforeAndAfter, FunSuite, Matchers}
import org.scalatest.junit.JUnitRunner

import scala.util.{Success, Failure}

import org.apache.griffin.measure.batch.log.Loggable


@RunWith(classOf[JUnitRunner])
class RuleParserTest extends FunSuite with Matchers with BeforeAndAfter with Loggable {

  test("test rule parser") {
    val ruleParser = RuleParser()

//    val rules = "outTime = 24h; @Invalid ${source}['__time'] + ${outTime} > ${target}['__time']"
    val rules = "@Key ${source}.json()['seeds'][*].json()['metadata'].json()['tracker']['crawlRequestCreateTS'] === ${target}.json()['groups'][0]['attrsList']['name'='CRAWLMETADATA']['values'][0].json()['tracker']['crawlRequestCreateTS']"
//    val rules = "${source}['__time'] + ${outTime} > ${target}['__time']"
//    val rules = "${source}['__time'] > ${target}['__time']"
//    val rules = "432"
//    val rules = "${target}.json()['groups'][0]['attrsList']['name'='URL']['values'][0]"

    val result = ruleParser.parseAll(ruleParser.statementsExpr, rules)

    println(result)
  }

}

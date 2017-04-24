package org.apache.griffin.measure.batch.rule.expr

import scala.util.{Success, Try}

trait FieldDescOnly extends Describable {

}

case class IndexDesc(expr: String) extends FieldDescOnly {
  val index: Int = {
    Try(expr.toInt) match {
      case Success(v) => v
      case _ => throw new Exception(s"${expr} is invalid index")
    }
  }
  val desc: String = describe(index)
}

case class FieldDesc(expr: String) extends FieldDescOnly {
  val field: String = expr
  val desc: String = describe(field)
}

case class AllFieldsDesc(expr: String) extends FieldDescOnly {
  val allFields: String = expr
  val desc: String = allFields
}

case class FieldRangeDesc(startField: FieldDescOnly, endField: FieldDescOnly) extends FieldDescOnly {
  val desc: String = {
    (startField, endField) match {
      case (f1: IndexDesc, f2: IndexDesc) => s"(${f1.desc}, ${f2.desc})"
      case _ => throw new Exception("invalid field range description")
    }
  }
}
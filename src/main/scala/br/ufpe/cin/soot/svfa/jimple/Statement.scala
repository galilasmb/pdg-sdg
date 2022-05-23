package br.ufpe.cin.soot.svfa.jimple

import soot._

abstract class Statement(val base: Unit)

case class AssignStmt(b: Unit) extends Statement(b) {
  val stmt = base.asInstanceOf[soot.jimple.AssignStmt]
}
case class InvokeStmt(b: Unit) extends Statement(b) {
  val stmt = base.asInstanceOf[soot.jimple.InvokeStmt]
}

case class IfStmt(b: Unit) extends Statement(b) {
  val stmt = base.asInstanceOf[soot.jimple.IfStmt]
}

case class ReturnStmt(b: Unit) extends Statement(b) {
  val stmt = base.asInstanceOf[soot.jimple.ReturnStmt]
}

case class InvalidStmt(b: Unit) extends Statement(b)

object Statement {
  def convert(base: Unit): Statement =
    if(base.isInstanceOf[soot.jimple.AssignStmt]) {
      AssignStmt(base)
    }
    else if(base.isInstanceOf[soot.jimple.InvokeStmt]) {
      InvokeStmt(base)
    }else if(base.isInstanceOf[soot.jimple.IfStmt]) {
      IfStmt(base)
    }else if(base.isInstanceOf[soot.jimple.ReturnStmt]) {
      ReturnStmt(base)
    }
    else InvalidStmt(base)
}
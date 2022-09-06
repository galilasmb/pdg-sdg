package br.ufpe.cin.soot


import br.unb.cic.soot.graph.{NodeType, SimpleNode, SinkNode, SourceNode}

class PDGTest(leftchangedlines: Array[Int], rightchangedlines: Array[Int], className: String, mainMethod: String) extends JPDGTest{
  override def getClassName(): String = className
  override def getMainMethod(): String = mainMethod

  def this(){
    this(Array.empty[Int], Array.empty[Int], "", "")
  }

  override def analyze(unit: soot.Unit): NodeType = {

    if (!leftchangedlines.isEmpty && !rightchangedlines.isEmpty){
      if (leftchangedlines.contains(unit.getJavaSourceStartLineNumber)){
        return SourceNode
      } else if (rightchangedlines.contains(unit.getJavaSourceStartLineNumber)){
        return SinkNode
      }
    }

    return SimpleNode
  }

}


package br.ufpe.cin.soot.svfa.jimple

import br.ufpe.cin.soot.graph.{CallSiteLabel, CallSiteOpenLabel, DefLabel, DefLabelType, EdgeLabel, FalseLabelType, Graph, GraphNode, SinkNode, SourceNode, StatementNode, TrueLabelType}
import br.ufpe.cin.soot.svfa.SourceSinkDef
import soot.jimple._
import soot.options.Options
import soot.toolkits.graph.ExceptionalBlockGraph
import soot.{PackManager, Scene, SceneTransformer, SootMethod, Transform}

import java.util


/**
 * A Jimple based implementation of
 * Control Dependence Analysis.
 */
abstract class JPDG extends SootConfiguration with SourceSinkDef  {
  val traversedMethodsPDG = scala.collection.mutable.Set.empty[SootMethod]
  var listDef : List[(AssignStmt, StatementNode, Int)] = List()
  var cd: Graph = _
  var svg: Graph = _
  var pdg = new Graph()
  var hashSetUnit = new util.HashSet[(StatementNode, StatementNode, EdgeLabel)]
  var methods: Integer


  def buildPDG(jcd: JCD, jdfp: JDFP) {

    jcd.buildCD()
    jdfp.buildDFP()

    cd = jcd.cd
    svg = jdfp.svg

    mergeDFPAndCD() //pdg

    Options.v().setPhaseOption("jb", "use-original-names:true")

    val (pack, t) = createSceneTransformPDG()
    PackManager.v().getPack(pack).add(t)
    configurePackages().foreach(p => PackManager.v().getPack(p).apply())

    afterGraphConstruction()
  }

  def mergeDFPAndCD(): Unit = {

    //Add df+ edges in pdg

    for (e <- svg.edges()) {
      val from = e.from
      val label = e.label
      val to = e.to
      addNodeAndEdgePDG(from.asInstanceOf[StatementNode], to.asInstanceOf[StatementNode], label)
    }

    //Add cd edges in pdg

    for (e <- cd.edges()) {
      val from = e.from
      val label = e.label
      val to = e.to
      addNodeAndEdgePDG(from.asInstanceOf[StatementNode], to.asInstanceOf[StatementNode], label)
    }

  }

  def addNodeAndEdgePDG(from: StatementNode, to: StatementNode, label: EdgeLabel): Unit = {
    var auxNodeFrom = containsNodePDG(from)
    var auxNodeTo = containsNodePDG(to)
    if (auxNodeFrom != null){
      if (auxNodeTo != null){
        pdg.addEdge(auxNodeFrom, auxNodeTo, label)
      }else{
        pdg.addEdge(auxNodeFrom, to, label)
      }
    }else{
      if (auxNodeTo != null) {
        pdg.addEdge(from, auxNodeTo, label)
      }else{
        pdg.addEdge(from, to, label)
      }
    }
  }

  def containsNodePDG(node: StatementNode): StatementNode = {
    for (n <- pdg.edges()){
      var nodeFrom = n.from.asInstanceOf[StatementNode]
      var nodeTo = n.to.asInstanceOf[StatementNode]
      if (nodeFrom.equals(node)) return n.from.asInstanceOf[StatementNode]
      if (nodeTo.equals(node)) return n.to.asInstanceOf[StatementNode]
    }
    return null
  }

  def createSceneTransformPDG(): (String, Transform) = ("wjtp", new Transform("wjtp.pdg", new TransformerPDG()))

  class TransformerPDG extends SceneTransformer {
    override def internalTransform(phaseName: String, options: util.Map[String, String]): scala.Unit = {
      pointsToAnalysis = Scene.v().getPointsToAnalysis
      Scene.v().getEntryPoints.forEach(method => {
        traversePDG(method)
        methods = methods + 1
      })
    }
  }


  def traversePDG(method: SootMethod, forceNewTraversal: Boolean = false) : scala.Unit = {
    if((!forceNewTraversal) && (method.isPhantom || traversedMethodsPDG.contains(method))) {
      return
    }

    traversedMethodsPDG.add(method)

    val body  = method.retrieveActiveBody()

    val graphBlock = new ExceptionalBlockGraph(body)

    body.getUnits.forEach(unit => {
      try{
        val v = Statement.convert(unit)

        v match {
          case AssignStmt(base) => traverseDef(AssignStmt(base), unit, method, graphBlock)
          case _ =>
        }
      }catch {
        case e: Exception => return
      }
    })

    if (listDef.length>0){
      for (i <- 0 until listDef.length) {
        for (j <- i+1 until listDef.length){
          var op1 = listDef(i)._1.stmt.getLeftOp
          var op2 = listDef(j)._1.stmt.getLeftOp
          if (op1.toString().equals(op2.toString())){

            try {
              var auxNodeFrom = containsNodePDG(listDef(i)._2.asInstanceOf[StatementNode])
              var auxNodeTo = containsNodePDG(listDef(j)._2.asInstanceOf[StatementNode])

              val nextI = pdg.getAdjacentNodes(auxNodeFrom).get
              val nextJ = pdg.getAdjacentNodes(auxNodeTo).get

              for (n <- nextI){
                for (m <- nextJ){
                  if (n.equals(m)){
                    val label = createDefEdgeLabel(listDef(i)._1.stmt, listDef(j)._1.stmt, method)

                    if (auxNodeFrom != null){
                      if (auxNodeTo != null){
                        pdg.addEdge(auxNodeFrom, auxNodeTo, label)
                      }else{
                        pdg.addEdge(auxNodeFrom, nextJ.asInstanceOf[StatementNode], label)
                      }
                    }else {
                      if (auxNodeTo != null) {
                        pdg.addEdge(nextI.asInstanceOf[StatementNode], auxNodeTo, label)
                      } else {
                        pdg.addEdge(nextI.asInstanceOf[StatementNode], nextJ.asInstanceOf[StatementNode], label)
                      }
                    }
                  }
                }
              }
            }catch{
              case e => print(e)
            }
          }
        }
      }
    }

  }

  def traverseDef(assignStmt: AssignStmt, unit: soot.Unit, method: SootMethod, graph: ExceptionalBlockGraph) : Unit = {
    val node = pdg.createNode(method, unit, analyze)
    var branch = -1

    graph.forEach(block => {
      block.forEach(u =>{
        if (u.equals(unit)){
          branch = block.getIndexInMethod
        }
      })
    })
    listDef = listDef:+ (assignStmt, node, branch)
  }

  def addDefEdge(source: GraphNode, target: GraphNode, label: EdgeLabel): Unit = {
    addNodeAndEdgePDG(source.asInstanceOf[StatementNode], target.asInstanceOf[StatementNode], label)
  }


  def createDefEdgeLabel(source: soot.Unit, target: soot.Unit, method: SootMethod): DefLabelType = {
    val statement = br.ufpe.cin.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, source.toString, source.getJavaSourceStartLineNumber)
    DefLabelType(DefLabel)
  }
}

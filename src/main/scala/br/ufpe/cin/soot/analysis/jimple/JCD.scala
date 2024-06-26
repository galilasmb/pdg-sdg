package br.ufpe.cin.soot.analysis.jimple

import br.ufpe.cin.soot.graph._
import br.unb.cic.soot.graph.{EdgeLabel, FalseLabel, FalseLabelType, GraphNode, SimpleNode, StatementNode, TrueLabel, TrueLabelType}
import br.unb.cic.soot.svfa.{SootConfiguration, SourceSinkDef}
import br.unb.cic.soot.svfa.jimple.{Analysis, FieldSensitive}
import com.typesafe.scalalogging.LazyLogging
import soot.jimple._
import soot.toolkits.graph.MHGPostDominatorsFinder
import soot.{PackManager, Scene, SceneTransformer, SootMethod, Transform}

import java.util

/**
 * A Jimple based implementation of
 * Control Dependence Analysis.
 */
abstract class JCD extends SootConfiguration with FieldSensitive with Analysis with SourceSinkDef with LazyLogging{

  var cd = new br.unb.cic.soot.graph.Graph()
  val traversedMethodsCD = scala.collection.mutable.Set.empty[SootMethod]
  var methods = 0
  var omitExceptingUnitEdges = true
  def runInFullSparsenessMode() = true

  def buildCD() {
    beforeGraphConstruction()
    val (pack2, t2) = createSceneTransform()
    PackManager.v().getPack(pack2).add(t2)
    configurePackages().foreach(p => PackManager.v().getPack(p).apply())

    afterGraphConstruction()
  }

  override def createSceneTransform(): (String, Transform) = ("wjtp", new Transform("wjtp.cd", new TransformerCD()))

  class TransformerCD extends SceneTransformer {
    override def internalTransform(phaseName: String, options: util.Map[String, String]): Unit = {
      pointsToAnalysis = Scene.v().getPointsToAnalysis
      Scene.v().getEntryPoints.forEach(method => {
        traverseCD(method)
        methods = methods + 1
      })
    }
  }

//  Loop through all the statements in jimple from a method and create the CD graph
  def traverseCD(method: SootMethod, forceNewTraversal: Boolean = false) : Unit = {
    if((!forceNewTraversal) && (method.isPhantom || traversedMethodsCD.contains(method))) {
      return
    }

    traversedMethodsCD.add(method)

    val body  = method.retrieveActiveBody()

    try {
      //Generate a unit graph from a method body
      val unitGraph= new UnitGraphNodes(body, omitExceptingUnitEdges)

      //Finder a post-dominator from a unit graph
      val analysis = new MHGPostDominatorsFinder(unitGraph)

      unitGraph.forEach(unit => {
        var edges = unitGraph.getSuccsOf(unit)
        var ADominators = analysis.getDominators(unit)

        //        println(unit, unit.getJavaSourceStartLineNumber())
        //Find a path with from unit to edges, using the post-dominator tree, excluding the LCA node
        //Add True and False edge
        var typeEd = true
        var count = 0
        edges.forEach(unitAux =>{
          var BDominators = analysis.getDominators(unitAux)
          var dItB = BDominators.iterator
          while (dItB.hasNext()) {
            val dsB = dItB.next()
            if (!ADominators.contains(dsB)){
              if (count > 0){
                typeEd = false
              } else {
                typeEd = true //The first time is true
              }
              addControlDependenceEdge(unit, dsB, typeEd, method)
            }
          }
          count = count + 1
        })
      })
    } catch {
      case e: NullPointerException => {
        println ("Error creating node, an invalid statement.")
      }
      case e: Exception => {
        println ("An invalid statement.")
      }
    }

  }

  def setOmitExceptingUnitEdges(op: Boolean): Unit = {
    omitExceptingUnitEdges = op
  }

//  Add a control dependence edge from a node s to a node t with an edge type (True or False)
  def addControlDependenceEdge(s: soot.Unit, t: soot.Unit, typeEdge: Boolean, method: SootMethod): Unit = {
    if (s.isInstanceOf[GotoStmt] || t.isInstanceOf[GotoStmt]) return
    val label = if (typeEdge) (createTrueEdgeLabel(s, t, method)) else (createFalseEdgeLabel(s, t, method))

    if (s.isInstanceOf[UnitDummy]) {
      if (t.isInstanceOf[UnitDummy]) {
        addEdgeControlDependence(createDummyNode(s, method), createDummyNode(t, method), label)
      }else{
        addEdgeControlDependence(createDummyNode(s, method), createNode(method, t), label)
      }
    }else{
      if (t.isInstanceOf[UnitDummy]) {
        addEdgeControlDependence(createNode(method, s),  createDummyNode(t, method), label)
      }else{
        addEdgeControlDependence(createNode(method, s), createNode(method, t), label)

      }
    }

  }

//  Create a node for a statment from a method to add to the graph cd
  def createNode(method: SootMethod, stmt: soot.Unit): StatementNode =
    cd.createNode(method, stmt, analyze, null)

//  Checks if the graph already contains the node, before creating it
  def containsNodeCD(node: StatementNode): StatementNode = {
    for (n <- cd.edges()){
      var xx = n.from.asInstanceOf[StatementNode]
      var yy = n.to.asInstanceOf[StatementNode]
      if (xx.equals(node)) return n.from.asInstanceOf[StatementNode]
      if (yy.equals(node)) return n.to.asInstanceOf[StatementNode]
    }
    return null
  }

//  Update the graph
  def addEdgeControlDependence(source: GraphNode, target: GraphNode, label: EdgeLabel): Boolean = {
    var res = false
    if(!runInFullSparsenessMode() || true) {
      var xy = containsNodeCD(source.asInstanceOf[StatementNode])
      var xx = containsNodeCD(target.asInstanceOf[StatementNode])
      if (xy != null){
        if (xx != null){
          cd.addEdge(xy, xx, label)
        }else{
          cd.addEdge(xy, target.asInstanceOf[StatementNode], label)
        }

      }else{
        if (xx != null) {
          cd.addEdge(source, xx, label)
        }else{
          cd.addEdge(source, target, label)
        }
      }
      res = true
    }
    return res
  }


  def createDummyNode(unit: soot.Unit, method: SootMethod): StatementNode = {
    var node = createNodeCD(method, unit)

    if (unit.toString().contains("EntryPoint")) {
      node = createEntryPointNode(method)
    } else if (unit.toString().contains("Start")) {
      node = createStartNode(method)
    } else if (unit.toString().contains("Stop")) {
      node = createStopNode(method)
    }
    return node
  }

  def createEntryPointNode(method: SootMethod): StatementNode = {
    try {
      return new StatementNode(br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, "Entry Point", 0), SimpleNode, null)
    } catch {
      case e: NullPointerException => {
        println ("Error creating node, an invalid statement.")
        return null
      }
    }
  }

  def createStartNode(method: SootMethod): StatementNode = {
    try {
      return new StatementNode(br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, "Start", 0), SimpleNode, null)
    } catch {
      case e: NullPointerException => {
        println ("Error creating node, an invalid statement.")
        return null
      }
    }
  }

  def createStopNode(method: SootMethod): StatementNode = {
    try {
      return new StatementNode(br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, "Stop", 0), SimpleNode, null)
    } catch {
      case e: NullPointerException => {
        println ("Error creating node, an invalid statement.")
        return null
      }
    }
  }

  def createTrueEdgeLabel(source: soot.Unit, target: soot.Unit, method: SootMethod): EdgeLabel = {
    val statement = br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, source.toString, source.getJavaSourceStartLineNumber)
    TrueLabelType(TrueLabel)
  }

  def createFalseEdgeLabel(source: soot.Unit, target: soot.Unit, method: SootMethod): EdgeLabel = {
    val statement = br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, source.toString, source.getJavaSourceStartLineNumber)
    FalseLabelType(FalseLabel)
  }

  def createNodeCD(method: SootMethod, stmt: soot.Unit): StatementNode =
    StatementNode(br.unb.cic.soot.graph.Statement(method.getDeclaringClass.toString, method.getSignature, stmt.toString, stmt.getJavaSourceStartLineNumber), analyze(stmt), null)

  def cdToDotModel(): String = {
    cd.toDotModel()
  }

  def reportConflictsCD() = {
    cd.reportConflicts()
  }
}

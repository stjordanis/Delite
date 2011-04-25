package ppl.delite.runtime.graph.ops

import ppl.delite.runtime.graph.targets.Targets
import ppl.delite.runtime.graph.DeliteTaskGraph

/**
 * Author: Kevin J. Brown
 * Date: Nov 14, 2010
 * Time: 10:04:13 PM
 * 
 * Pervasive Parallelism Laboratory (PPL)
 * Stanford University
 */

class OP_Zip(val id: String, func: String, private[graph] val outputTypesMap: Map[Targets.Value,Map[String,String]]) extends OP_Executable {

  final def isDataParallel = true

  def task = kernelName

  private var kernelName: String = ""

  def setKernelName(name: String) {
    kernelName = name
  }

  def function = func

  /**
   * Since the semantics of the zip are to mutate the elements in a collection all consumer (true) dependency edges already exist in graph
   * Chunking needs to add additional anti-dependency edges for each chunk to ensure all chunks are complete
   * Chunks require same dependency & input lists
   */
  def chunk(i: Int): OP_Zip = {
    val r = new OP_Zip(id+"_"+i, function, Targets.unitTypes(id+"_"+i, outputTypesMap)) //chunks all return Unit
    r.dependencies = dependencies //lists are immutable so can be shared
    r.inputList = inputList
    r.consumers = consumers
    for (dep <- getDependencies) dep.addConsumer(r)
    for (c <- getConsumers) c.addDependency(r)
    r
  }

  def header(kernel: String, graph: DeliteTaskGraph): OP_Single = {
    val h = new OP_Single(id+"_h", kernel, Map(Targets.Scala->Map(id+"_h"->kernel,"functionReturn"->kernel)))
    //header assumes all inputs of map
    h.dependencies = dependencies
    h.inputList = inputList
    h.addConsumer(this)
    for (dep <- getDependencies) dep.replaceConsumer(this, h)
    //map consumes header, map's consumers remain unchanged
    dependencies = Set(h)
    inputList = List((h,h.id))
    graph.registerOp(h)
    h
  }

  def cost = 0
  def size = 0

}

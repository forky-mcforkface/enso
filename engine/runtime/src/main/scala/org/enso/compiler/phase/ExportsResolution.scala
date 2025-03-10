package org.enso.compiler.phase

import org.enso.compiler.data.BindingsMap
import org.enso.compiler.data.BindingsMap.{
  ExportedModule,
  ModuleReference,
  ResolvedConstructor,
  ResolvedMethod,
  ResolvedModule,
  ResolvedPolyglotSymbol,
  ResolvedType,
  SymbolRestriction
}
import org.enso.compiler.pass.analyse.BindingAnalysis
import org.enso.interpreter.runtime.Module

import scala.collection.mutable

/** An exception signaling a loop in the export statements.
  * @param modules the modules forming the cycle.
  */
case class ExportCycleException(modules: List[Module])
    extends Exception(
      "Compilation aborted due to a cycle in export statements."
    )

class ExportsResolution {

  private case class Edge(
    exporter: Node,
    symbols: SymbolRestriction,
    exportsAs: Option[String],
    exportee: Node
  )

  private case class Node(
    module: Module
  ) {
    var exports: List[Edge]    = List()
    var exportedBy: List[Edge] = List()
  }

  private def getBindings(module: Module): BindingsMap =
    module.getIr.unsafeGetMetadata(
      BindingAnalysis,
      "module without binding analysis in Exports Resolution"
    )

  private def buildGraph(modules: List[Module]): List[Node] = {
    val nodes = mutable.Map[Module, Node](
      modules.map(mod => (mod, Node(mod))): _*
    )
    modules.foreach { module =>
      val exports = getBindings(module).getDirectlyExportedModules
      val node    = nodes(module)
      node.exports = exports.map {
        case ExportedModule(mod, rename, restriction) =>
          Edge(node, restriction, rename, nodes(mod.unsafeAsModule()))
      }
      node.exports.foreach { edge => edge.exportee.exportedBy ::= edge }
    }
    nodes.values.toList
  }

  private def findCycles(nodes: List[Node]): List[List[Node]] = {
    val visited: mutable.Set[Node]    = mutable.Set()
    val inProgress: mutable.Set[Node] = mutable.Set()
    var foundCycles: List[List[Node]] = List()
    def go(node: Node): Option[(Node, List[Node])] = {
      if (inProgress.contains(node)) {
        Some((node, List()))
      } else if (visited.contains(node)) {
        None
      } else {
        inProgress.add(node)
        val children        = node.exports.map(_.exportee)
        val childrenResults = children.flatMap(go)
        inProgress.remove(node)
        visited.add(node)
        childrenResults match {
          case List() => None
          case (mod, path) :: _ =>
            if (mod == node) {
              foundCycles = (mod :: path) :: foundCycles
              None
            } else {
              Some((mod, node :: path))
            }
        }

      }
    }
    nodes.foreach(go)
    foundCycles
  }

  private def topsort(nodes: List[Node]): List[Node] = {
    val degrees            = mutable.Map[Node, Int]()
    var result: List[Node] = List()
    nodes.foreach { node =>
      degrees(node) = node.exports.length
    }
    while (degrees.nonEmpty) {
      val q     = mutable.Queue[Node]()
      val entry = degrees.find { case (_, deg) => deg == 0 }.get._1
      q.enqueue(entry)
      while (q.nonEmpty) {
        val item = q.dequeue()
        degrees -= item
        item.exportedBy.foreach { edge =>
          degrees(edge.exporter) -= 1
          if (degrees(edge.exporter) == 0) {
            q.enqueue(edge.exporter)
          }
        }
        result ::= item
      }
    }
    result.reverse
  }

  private def resolveExports(nodes: List[Node]): Unit = {
    val exports = mutable.Map[Module, List[ExportedModule]]()
    nodes.foreach { node =>
      val explicitlyExported =
        node.exports.map(edge =>
          ExportedModule(
            ModuleReference.Concrete(edge.exportee.module),
            edge.exportsAs,
            edge.symbols
          )
        )
      val transitivelyExported: List[ExportedModule] =
        explicitlyExported.flatMap {
          case ExportedModule(module, _, restriction) =>
            exports(module.unsafeAsModule()).map {
              case ExportedModule(export, _, parentRestriction) =>
                ExportedModule(
                  export,
                  None,
                  SymbolRestriction.Intersect(
                    List(restriction, parentRestriction)
                  )
                )
            }
        }
      val allExported = explicitlyExported ++ transitivelyExported
      val unified = allExported
        .groupBy(_.module)
        .map { case (mod, items) =>
          val name = items.collectFirst { case ExportedModule(_, Some(n), _) =>
            n
          }
          val itemsUnion = SymbolRestriction.Union(items.map(_.symbols))
          ExportedModule(mod, name, itemsUnion)
        }
        .toList
      exports(node.module) = unified
    }
    exports.foreach { case (module, exports) =>
      getBindings(module).resolvedExports =
        exports.map(ex => ex.copy(symbols = ex.symbols.optimize))
    }
  }

  private def resolveExportedSymbols(modules: List[Module]): Unit = {
    modules.foreach { module =>
      val bindings = getBindings(module)
      val ownTypes = bindings.types.map { tp =>
        val sym = List(ResolvedType(ModuleReference.Concrete(module), tp))
        (tp.name, sym)
      }
      val ownMethods = bindings.moduleMethods.map { method =>
        val name = method.name
        val syms =
          List(ResolvedMethod(ModuleReference.Concrete(module), method))
        (name, syms)
      }
      val ownConstructors = bindings.constructors.map { tp =>
        val name = tp.name
        val types =
          List(ResolvedConstructor(ModuleReference.Concrete(module), tp))
        (name, types)
      }
      val ownPolyglotBindings = bindings.polyglotSymbols.map { poly =>
        val name = poly.name
        val syms =
          List(ResolvedPolyglotSymbol(ModuleReference.Concrete(module), poly))
        (name, syms)
      }
      val exportedModules = bindings.resolvedExports.collect {
        case ExportedModule(mod, Some(name), _) =>
          (name, List(ResolvedModule(mod)))
      }
      val reExportedSymbols = bindings.resolvedExports.flatMap { export =>
        getBindings(export.module.unsafeAsModule()).exportedSymbols.toList
          .flatMap { case (sym, resolutions) =>
            val allowedResolutions =
              resolutions.filter(res => export.symbols.canAccess(sym, res))
            if (allowedResolutions.isEmpty) None
            else Some((sym, allowedResolutions))
          }
      }
      bindings.exportedSymbols = List(
        ownTypes,
        ownMethods,
        ownConstructors,
        ownPolyglotBindings,
        exportedModules,
        reExportedSymbols
      ).flatten.groupBy(_._1).map { case (m, names) =>
        (m, names.flatMap(_._2).distinct)
      }
    }
  }

  /** Performs exports resolution on a selected set of modules.
    *
    * The exports graph is validated and stored in the individual modules,
    * allowing further use.
    *
    * The method returns a list containing the original modules, in
    * a topological order, such that any module exported by a given module
    * comes before it in the list.
    *
    * @param modules the modules to process.
    * @return the original modules, sorted topologically.
    * @throws ExportCycleException when the export statements form a cycle.
    */
  @throws[ExportCycleException]
  def run(modules: List[Module]): List[Module] = {
    val graph  = buildGraph(modules)
    val cycles = findCycles(graph)
    if (cycles.nonEmpty) {
      throw ExportCycleException(cycles.head.map(_.module))
    }
    val tops = topsort(graph)
    resolveExports(tops)
    val topModules = tops.map(_.module)
    resolveExportedSymbols(tops.map(_.module))
    topModules
  }
}

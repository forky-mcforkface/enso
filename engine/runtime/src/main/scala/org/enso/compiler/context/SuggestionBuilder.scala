package org.enso.compiler.context

import org.enso.compiler.core.IR
import org.enso.compiler.data.BindingsMap
import org.enso.compiler.pass.resolve.{
  DocumentationComments,
  MethodDefinitions,
  TypeNames,
  TypeSignatures
}
import org.enso.pkg.QualifiedName
import org.enso.polyglot.Suggestion
import org.enso.polyglot.data.Tree
import org.enso.syntax.text.Location
import org.enso.text.editing.IndexedSource

import scala.collection.mutable

/** Module that extracts [[Suggestion]] entries from the [[IR]].
  *
  * @param source the text source
  * @tparam A the type of the text source
  */
final class SuggestionBuilder[A: IndexedSource](val source: A) {

  import SuggestionBuilder._

  /** Build suggestions from the given `ir`.
    *
    * @param module the module name
    * @param ir the input `IR`
    * @return the tree of suggestion entries extracted from the given `IR`
    */
  def build(module: QualifiedName, ir: IR): Tree.Root[Suggestion] = {
    type TreeBuilder =
      mutable.Builder[Tree.Node[Suggestion], Vector[Tree.Node[Suggestion]]]

    def go(tree: TreeBuilder, scope: Scope): Vector[Tree.Node[Suggestion]] = {
      if (scope.queue.isEmpty) {
        tree.result()
      } else {
        val ir  = scope.queue.dequeue()
        val doc = ir.getMetadata(DocumentationComments).map(_.documentation)
        ir match {
          case IR.Module.Scope.Definition.Type(tpName, _, List(), _, _, _) =>
            val cons =
              buildAtomConstructor(module, tpName.name, tpName.name, Seq(), doc)
            go(tree ++= Vector(Tree.Node(cons, Vector())), scope)

          case IR.Module.Scope.Definition.Type(tpName, _, members, _, _, _) =>
            val conses = members.map {
              case data @ IR.Module.Scope.Definition.Data(
                    name,
                    arguments,
                    _,
                    _,
                    _
                  ) =>
                buildAtomConstructor(
                  module,
                  tpName.name,
                  name.name,
                  arguments,
                  data.getMetadata(DocumentationComments).map(_.documentation)
                )
            }
            val getters = members
              .flatMap(_.arguments)
              .map(_.name.name)
              .distinct
              .map(buildGetter(module, tpName.name, _))

            val tpSuggestions = conses ++ getters

            go(tree ++= tpSuggestions.map(Tree.Node(_, Vector())), scope)

          case IR.Module.Scope.Definition.Method
                .Explicit(
                  IR.Name.MethodReference(typePtr, methodName, _, _, _),
                  IR.Function.Lambda(args, body, _, _, _, _),
                  _,
                  _,
                  _
                ) =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            val selfTypeOpt = typePtr match {
              case Some(typePtr) =>
                typePtr
                  .getMetadata(MethodDefinitions)
                  .map(_.target.qualifiedName)
              case None =>
                Some(module)
            }
            val methodOpt = selfTypeOpt.map { selfType =>
              buildMethod(
                body.getExternalId,
                module,
                methodName.name,
                selfType,
                args,
                doc,
                typeSignature
              )
            }
            val subforest = go(
              Vector.newBuilder,
              Scope(body.children, body.location)
            )
            go(tree ++= methodOpt.map(Tree.Node(_, subforest)), scope)

          case IR.Module.Scope.Definition.Method
                .Conversion(
                  IR.Name.MethodReference(_, _, _, _, _),
                  IR.Name.Literal(sourceTypeName, _, _, _, _),
                  IR.Function.Lambda(args, body, _, _, _, _),
                  _,
                  _,
                  _
                ) if ConversionsEnabled =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            val conversion = buildConversion(
              body.getExternalId,
              module,
              args,
              sourceTypeName,
              doc,
              typeSignature
            )
            go(tree += Tree.Node(conversion, Vector()), scope)

          case IR.Expression.Binding(
                name,
                IR.Function.Lambda(args, body, _, _, _, _),
                _,
                _,
                _
              ) if name.location.isDefined =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            val function = buildFunction(
              body.getExternalId,
              module,
              name,
              args,
              scope.location.get,
              typeSignature
            )
            val subforest = go(
              Vector.newBuilder,
              Scope(body.children, body.location)
            )
            go(tree += Tree.Node(function, subforest), scope)

          case IR.Expression.Binding(name, expr, _, _, _)
              if name.location.isDefined =>
            val typeSignature = ir.getMetadata(TypeSignatures)
            val local = buildLocal(
              expr.getExternalId,
              module,
              name.name,
              scope.location.get,
              typeSignature
            )
            val subforest = go(
              Vector.newBuilder,
              Scope(expr.children, expr.location)
            )
            go(tree += Tree.Node(local, subforest), scope)

          case _ =>
            go(tree, scope)
        }
      }
    }

    val builder: TreeBuilder = Vector.newBuilder
    builder += Tree.Node(
      buildModule(
        module,
        ir.getMetadata(DocumentationComments).map(_.documentation)
      ),
      Vector()
    )

    Tree.Root(
      go(builder, Scope(ir.children, ir.location))
    )
  }

  /** Build a method suggestion. */
  private def buildMethod(
    externalId: Option[IR.ExternalId],
    module: QualifiedName,
    name: String,
    selfType: QualifiedName,
    args: Seq[IR.DefinitionArgument],
    doc: Option[String],
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Method = {
    val typeSig = buildTypeSignatureFromMetadata(typeSignature)
    val (methodArgs, returnTypeDef) =
      buildMethodArguments(args, typeSig, selfType)
    Suggestion.Method(
      externalId    = externalId,
      module        = module.toString,
      name          = name,
      arguments     = methodArgs,
      selfType      = selfType.toString,
      returnType    = buildReturnType(returnTypeDef),
      documentation = doc
    )
  }

  /** Build a conversion suggestion. */
  private def buildConversion(
    externalId: Option[IR.ExternalId],
    module: QualifiedName,
    args: Seq[IR.DefinitionArgument],
    sourceTypeName: String,
    doc: Option[String],
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Conversion = {
    val typeSig = buildTypeSignatureFromMetadata(typeSignature)
    val (methodArgs, returnTypeDef) =
      buildFunctionArguments(args, typeSig)
    Suggestion.Conversion(
      externalId    = externalId,
      module        = module.toString,
      arguments     = methodArgs,
      sourceType    = sourceTypeName,
      returnType    = buildReturnType(returnTypeDef),
      documentation = doc
    )
  }

  /** Build a function suggestion. */
  private def buildFunction(
    externalId: Option[IR.ExternalId],
    module: QualifiedName,
    name: IR.Name,
    args: Seq[IR.DefinitionArgument],
    location: Location,
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Function = {
    val typeSig = buildTypeSignatureFromMetadata(typeSignature)
    val (methodArgs, returnTypeDef) =
      buildFunctionArguments(args, typeSig)
    Suggestion.Function(
      externalId = externalId,
      module     = module.toString,
      name       = name.name,
      arguments  = methodArgs,
      returnType = buildReturnType(returnTypeDef),
      scope      = buildScope(location)
    )
  }

  /** Build a local suggestion. */
  private def buildLocal(
    externalId: Option[IR.ExternalId],
    module: QualifiedName,
    name: String,
    location: Location,
    typeSignature: Option[TypeSignatures.Metadata]
  ): Suggestion.Local = {
    val typeSig            = buildTypeSignatureFromMetadata(typeSignature)
    val (_, returnTypeDef) = buildFunctionArguments(Seq(), typeSig)
    Suggestion.Local(
      externalId,
      module.toString,
      name,
      buildReturnType(returnTypeDef),
      buildScope(location)
    )
  }

  /** Build an atom suggestion representing a module. */
  private def buildModule(
    module: QualifiedName,
    doc: Option[String]
  ): Suggestion =
    Suggestion.Module(
      module        = module.toString,
      documentation = doc
    )

  /** Build an atom constructor. */
  private def buildAtomConstructor(
    module: QualifiedName,
    tp: String,
    name: String,
    arguments: Seq[IR.DefinitionArgument],
    doc: Option[String]
  ): Suggestion.Atom =
    Suggestion.Atom(
      externalId    = None,
      module        = module.toString,
      name          = name,
      arguments     = arguments.map(buildArgument),
      returnType    = module.createChild(tp).toString,
      documentation = doc
    )

  /** Build getter methods from atom arguments. */
  private def buildGetter(
    module: QualifiedName,
    typeName: String,
    getterName: String
  ): Suggestion = {
    val thisArg = IR.DefinitionArgument.Specified(
      name         = IR.Name.Self(None),
      ascribedType = None,
      defaultValue = None,
      suspended    = false,
      location     = None
    )
    buildMethod(
      externalId    = None,
      module        = module,
      name          = getterName,
      selfType      = module.createChild(typeName),
      args          = Seq(thisArg),
      doc           = None,
      typeSignature = None
    )
  }

  private def buildResolvedUnionTypeName(
    resolvedName: BindingsMap.ResolvedName
  ): TypeArg = resolvedName match {
    case tp: BindingsMap.ResolvedType =>
      TypeArg.Sum(
        Some(tp.qualifiedName),
        tp.getVariants.map(r => TypeArg.Value(r.qualifiedName))
      )
    case _: BindingsMap.ResolvedName =>
      TypeArg.Value(resolvedName.qualifiedName)
  }

  /** Build type signature from the ir metadata.
    *
    * @param typeSignature the type signature metadata
    * @param bindings the binding analysis metadata
    * @return the list of type arguments
    */
  private def buildTypeSignatureFromMetadata(
    typeSignature: Option[TypeSignatures.Metadata]
  ): Vector[TypeArg] =
    typeSignature match {
      case Some(TypeSignatures.Signature(typeExpr)) =>
        buildTypeSignature(typeExpr)
      case _ =>
        Vector()
    }

  /** Build type signature from the type expression.
    *
    * @param bindings the binding analysis metadata
    * @param typeExpr the type signature expression
    * @return the list of type arguments
    */
  private def buildTypeSignature(
    typeExpr: IR.Expression
  ): Vector[TypeArg] = {
    def go(expr: IR.Expression): TypeArg = expr match {
      case fn: IR.Type.Function =>
        TypeArg.Function(fn.args.map(go).toVector, go(fn.result))
      case union: IR.Type.Set.Union =>
        TypeArg.Sum(None, union.operands.map(go))
      case app: IR.Application.Prefix =>
        TypeArg.Application(
          go(app.function),
          app.arguments.map(c => go(c.value)).toVector
        )
      case bin: IR.Application.Operator.Binary =>
        TypeArg.Binary(
          go(bin.left.value),
          go(bin.right.value),
          bin.operator.name
        )
      case tname: IR.Name =>
        tname
          .getMetadata(TypeNames)
          .map(t => buildResolvedUnionTypeName(t.target))
          .getOrElse(TypeArg.Value(QualifiedName.simpleName(tname.name)))

      case _ =>
        TypeArg.Value(QualifiedName.fromString(Any))
    }
    val r = go(typeExpr)
    r match {
      case fn: TypeArg.Function => fn.arguments :+ fn.result
      case _                    => Vector(r)
    }
  }

  /** Build arguments of a method.
    *
    * @param vargs the list of value arguments
    * @param targs the list of type arguments
    * @param selfType the self type of a method
    * @return the list of arguments with a method return type
    */
  private def buildMethodArguments(
    vargs: Seq[IR.DefinitionArgument],
    targs: Seq[TypeArg],
    selfType: QualifiedName
  ): (Seq[Suggestion.Argument], Option[TypeArg]) = {
    @scala.annotation.tailrec
    def go(
      vargs: Seq[IR.DefinitionArgument],
      targs: Seq[TypeArg],
      acc: Vector[Suggestion.Argument]
    ): (Vector[Suggestion.Argument], Option[TypeArg]) =
      if (vargs.isEmpty) {
        (acc, targs.lastOption)
      } else {
        vargs match {
          case IR.DefinitionArgument.Specified(
                name: IR.Name.Self,
                _,
                defaultValue,
                suspended,
                _,
                _,
                _
              ) +: vtail =>
            val thisArg = Suggestion.Argument(
              name         = name.name,
              reprType     = selfType.toString,
              isSuspended  = suspended,
              hasDefault   = defaultValue.isDefined,
              defaultValue = defaultValue.flatMap(buildDefaultValue)
            )
            go(vtail, targs, acc :+ thisArg)
          case varg +: vtail =>
            targs match {
              case targ +: ttail =>
                go(vtail, ttail, acc :+ buildTypedArgument(varg, targ))
              case _ =>
                go(vtail, targs, acc :+ buildArgument(varg))
            }
        }
      }

    go(vargs, targs, Vector())
  }

  /** Build arguments of a function.
    *
    * @param vargs the list of value arguments
    * @param targs the list of type arguments
    * @return the list of arguments with a function return type
    */
  private def buildFunctionArguments(
    vargs: Seq[IR.DefinitionArgument],
    targs: Seq[TypeArg]
  ): (Seq[Suggestion.Argument], Option[TypeArg]) = {
    @scala.annotation.tailrec
    def go(
      vargs: Seq[IR.DefinitionArgument],
      targs: Seq[TypeArg],
      acc: Vector[Suggestion.Argument]
    ): (Seq[Suggestion.Argument], Option[TypeArg]) =
      if (vargs.isEmpty) {
        (acc, targs.lastOption)
      } else {
        vargs match {
          case varg +: vtail =>
            targs match {
              case targ +: ttail =>
                go(vtail, ttail, acc :+ buildTypedArgument(varg, targ))
              case _ =>
                go(vtail, targs, acc :+ buildArgument(varg))
            }
        }
      }

    go(vargs, targs, Vector())
  }

  /** Build suggestion argument from a typed definition.
    *
    * @param varg the value argument
    * @param targ the type argument
    * @return the suggestion argument
    */
  private def buildTypedArgument(
    varg: IR.DefinitionArgument,
    targ: TypeArg
  ): Suggestion.Argument =
    Suggestion.Argument(
      name         = varg.name.name,
      reprType     = buildTypeArgumentName(targ),
      isSuspended  = varg.suspended,
      hasDefault   = varg.defaultValue.isDefined,
      defaultValue = varg.defaultValue.flatMap(buildDefaultValue),
      tagValues = targ match {
        case s: TypeArg.Sum => Some(pluckVariants(s))
        case _              => None
      }
    )

  private def pluckVariants(arg: TypeArg): Seq[String] = arg match {
    case TypeArg.Sum(Some(n), List()) => Seq(n.toString)
    case TypeArg.Sum(_, variants)     => variants.flatMap(pluckVariants)
    case TypeArg.Value(n)             => Seq(n.toString)
    case _                            => Seq()
  }

  /** Build the name of type argument.
    *
    * @param targ the type argument
    * @return the name of type argument
    */
  private def buildTypeArgumentName(targ: TypeArg): String = {
    def go(targ: TypeArg, level: Int): String =
      targ match {
        case TypeArg.Value(name) => name.toString
        case TypeArg.Function(args, ret) =>
          val types    = args :+ ret
          val typeList = types.map(go(_, level + 1))
          if (level > 0) typeList.mkString("(", " -> ", ")")
          else typeList.mkString(" -> ")
        case TypeArg.Binary(l, r, op) =>
          val left  = go(l, level + 1)
          val right = go(r, level + 1)
          s"$left $op $right"
        case TypeArg.Application(fun, args) =>
          val funText  = go(fun, level)
          val argsList = args.map(go(_, level + 1)).mkString(" ")
          val typeName = s"$funText $argsList"
          if (level > 0) s"($typeName)" else typeName
        case TypeArg.Sum(Some(n), _) => n.toString
        case TypeArg.Sum(None, variants) =>
          variants.map(go(_, level + 1)).mkString(" | ")
      }

    go(targ, 0)
  }

  /** Build suggestion argument from an untyped definition.
    *
    * @param arg the value argument
    * @return the suggestion argument
    */
  private def buildArgument(arg: IR.DefinitionArgument): Suggestion.Argument =
    Suggestion.Argument(
      name         = arg.name.name,
      reprType     = Any,
      isSuspended  = arg.suspended,
      hasDefault   = arg.defaultValue.isDefined,
      defaultValue = arg.defaultValue.flatMap(buildDefaultValue)
    )

  /** Build return type from the type definition.
    *
    * @param typeDef the type definition
    * @return the type name
    */
  private def buildReturnType(typeDef: Option[TypeArg]): String =
    typeDef.map(buildTypeArgumentName).getOrElse(Any)

  /** Build argument default value from the expression.
    *
    * @param expr the argument expression
    * @return the argument default value
    */
  private def buildDefaultValue(expr: IR): Option[String] =
    expr match {
      case IR.Literal.Number(_, value, _, _, _) => Some(value)
      case IR.Literal.Text(text, _, _, _)       => Some(text)
      case _                                    => None
    }

  /** Build scope from the location. */
  private def buildScope(location: Location): Suggestion.Scope =
    Suggestion.Scope(toPosition(location.start), toPosition(location.end))

  /** Convert absolute position index to the relative position of a suggestion.
    *
    * @param index the absolute position in the source
    * @return the relative position
    */
  private def toPosition(index: Int): Suggestion.Position = {
    val pos = IndexedSource[A].toPosition(index, source)
    Suggestion.Position(pos.line, pos.character)
  }
}

object SuggestionBuilder {

  /** TODO[DB] enable conversions when they get the runtime support. */
  private val ConversionsEnabled: Boolean = false

  /** Create the suggestion builder.
    *
    * @param source the text source
    * @tparam A the type of the text source
    */
  def apply[A: IndexedSource](source: A): SuggestionBuilder[A] =
    new SuggestionBuilder[A](source)

  /** A single level of an `IR`.
    *
    * @param queue the nodes in the scope
    * @param location the scope location
    */
  private case class Scope(queue: mutable.Queue[IR], location: Option[Location])

  private object Scope {

    /** Create new scope from the list of items.
      *
      * @param items the list of IR nodes
      * @param location the identified IR location
      * @return new scope
      */
    def apply(items: Seq[IR], location: Option[IR.IdentifiedLocation]): Scope =
      new Scope(mutable.Queue(items: _*), location.map(_.location))
  }

  /** The base trait for argument types. */
  sealed private trait TypeArg
  private object TypeArg {

    /** A sum type – one of many possible options.
      * @param name the qualified name of the type.
      * @param variants the qualified names of constituent atoms.
      */
    case class Sum(name: Option[QualifiedName], variants: Seq[TypeArg])
        extends TypeArg

    /** Type with the name, like `A`.
      *
      * @param name the name of the type
      */
    case class Value(name: QualifiedName) extends TypeArg

    /** Function type, like `A -> A`.
      *
      * @param signature the list of types defining the function
      */
    case class Function(arguments: Vector[TypeArg], result: TypeArg)
        extends TypeArg

    /** Binary operator, like `A | B`
      *
      * @param left the left hand side of a binary operator
      * @param right the right hand side of a binary operator
      * @param operator the binary operator
      */
    case class Binary(left: TypeArg, right: TypeArg, operator: String)
        extends TypeArg

    /** Function application, like `Either A B`.
      *
      * @param function the function type
      * @param arguments the list of argument types
      */
    case class Application(
      function: TypeArg,
      arguments: Vector[TypeArg]
    ) extends TypeArg

  }

  val Any: String = "Standard.Base.Any.Any"

}

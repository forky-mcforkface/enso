package org.enso.compiler.test.semantic

import org.enso.compiler.core.IR
import org.enso.compiler.pass.resolve.{TypeNames, TypeSignatures}
import org.enso.interpreter.runtime
import org.enso.interpreter.runtime.Context
import org.enso.interpreter.test.InterpreterContext
import org.enso.pkg.QualifiedName
import org.enso.polyglot.{LanguageInfo, MethodNames}
import org.scalatest.matchers.{MatchResult, Matcher}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

trait TypeMatchers {
  sealed trait Sig {
    def ->:(that: Sig): Sig = this match {
      case Fn(args, r) => Fn(that :: args, r)
      case _           => Fn(List(that), this)
    }

    def |(that: Sig): Sig = {
      def toUnion(sig: Sig): List[Sig] = sig match {
        case Union(items) => items
        case other        => List(other)
      }
      Union(toUnion(this) ++ toUnion(that))
    }
  }
  case class Name(name: String)               extends Sig
  case class AnyQualName(name: QualifiedName) extends Sig
  case class Fn(args: List[Sig], result: Sig) extends Sig
  case class Union(items: List[Sig])          extends Sig

  implicit def fromString(str: String): Sig = {
    if (str.contains(".")) {
      AnyQualName(QualifiedName.fromString(str))
    } else { Name(str) }
  }

  def typeAs(sig: Sig): TypeMatcher = TypeMatcher(sig)

  case class TypeMatcher(sig: Sig) extends Matcher[IR.Expression] {
    private def findInequalityWitness(
      sig: Sig,
      expr: IR.Expression
    ): Option[(Sig, IR.Expression, String)] = (sig, expr) match {
      case (Name(n), t: IR.Name.Literal) if n == t.name =>
        if (n == t.name) {
          None
        } else {
          Some((sig, expr, "names do not match"))
        }
      case (AnyQualName(n), _) =>
        val meta = expr.getMetadata(TypeNames)
        if (meta.isEmpty) {
          return Some((sig, expr, "the expression does not have a resolution"))
        }
        meta match {
          case None =>
            Some((sig, expr, "the expression does not have a resolution"))
          case Some(resolution) =>
            if (resolution.target.qualifiedName == n) {
              None
            } else {
              Some(
                (
                  sig,
                  expr,
                  s"The resolution is ${resolution.target.qualifiedName}, but expected ${n}"
                )
              )
            }
        }
      case (Fn(args, res), t: IR.Type.Function) =>
        if (args.length != t.args.length) {
          return Some((sig, expr, "arity does not match"))
        }
        args
          .lazyZip(t.args)
          .flatMap(findInequalityWitness)
          .headOption
          .orElse(findInequalityWitness(res, t.result))
      case (Union(items), t: IR.Type.Set.Union) =>
        if (items.length != t.operands.length) {
          return Some((sig, expr, "number of items does not match"))
        }
        items.lazyZip(t.operands).flatMap(findInequalityWitness).headOption
      case _ => Some((sig, expr, "constructors are incompatible"))
    }

    override def apply(left: IR.Expression): MatchResult = {
      findInequalityWitness(sig, left) match {
        case Some((s, t, r)) =>
          MatchResult(
            matches = false,
            s"""
               |${left.showCode()}
               |($left)
               |does not match
               |$sig.
               |Analysis:
               |  sub-expression
               |    ${t.showCode()}
               |    ($t)
               |  did not match fragment $s
               |  because $r.
               |
               |""".stripMargin,
            "The type matched the matcher, but it should not."
          )
        case _ => MatchResult(matches = true, "", "")
      }
    }
  }
}

class TypeSignaturesTest
    extends AnyWordSpecLike
    with Matchers
    with TypeMatchers {

  private val ctx = new InterpreterContext()
  private val langCtx = ctx.ctx
    .getBindings(LanguageInfo.ID)
    .invokeMember(MethodNames.TopScope.LEAK_CONTEXT)
    .asHostObject[Context]()

  private val Module = QualifiedName.fromString("Unnamed.Test")

  langCtx.getPackageRepository.registerSyntheticPackage("my_pkg", "My_Lib")
  langCtx.getTopScope.createModule(
    QualifiedName.fromString("my_pkg.My_Lib.Util"),
    null,
    s"""
       |type Util_1
       |type Util_2
       |
       |type Util_Sum
       |    Util_Sum_1
       |    Util_Sum_2
       |""".stripMargin
  )

  implicit private class PreprocessModule(code: String) {
    def preprocessModule: IR.Module = {
      val module = new runtime.Module(Module, null, code)
      langCtx.getCompiler.run(module)
      module.getIr
    }
  }

  private def getSignature(
    module: IR.Module,
    methodName: String
  ): IR.Expression = {
    val m = module.bindings.find {
      case m: IR.Module.Scope.Definition.Method =>
        m.methodName.name == methodName
      case _ => false
    }.get
    m.unsafeGetMetadata(
      TypeSignatures,
      s"expected a type signature on method $methodName"
    ).signature
  }

  "Type Signatures" should {
    "be parsed in a simple scenario" in {
      val code =
        """
          |foo : Text -> Number
          |foo a = 42""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs("Text" ->: "Number")
    }

    "resolve locally defined names" in {
      val code =
        """
          |type A
          |type B
          |
          |type C
          |    X
          |    D
          |
          |foo : A -> B -> C -> X -> D
          |foo a = 42""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "Unnamed.Test.A" ->: "Unnamed.Test.B" ->: "Unnamed.Test.C" ->: "Unnamed.Test.X" ->: "Unnamed.Test.D"
      )
    }

    "resolve imported names" in {
      val code =
        """
          |from my_pkg.My_Lib.Util import all
          |
          |foo : Util_1 -> Util_2
          |foo a = 23
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "my_pkg.My_Lib.Util.Util_1" ->: "my_pkg.My_Lib.Util.Util_2"
      )
    }

    "resolve imported union type names" in {
      val code =
        """
          |from my_pkg.My_Lib.Util import all
          |
          |foo : Util_Sum -> Util_2
          |foo a = 23
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "foo") should typeAs(
        "my_pkg.My_Lib.Util.Util_Sum" ->: "my_pkg.My_Lib.Util.Util_2"
      )
    }

    "resolve anonymous sum types" in {
      val code =
        """from my_pkg.My_Lib.Util import all
          |
          |type Foo
          |
          |baz : Foo | Util_2 | Util_Sum -> Foo
          |baz a = 123
          |""".stripMargin
      val module = code.preprocessModule
      getSignature(module, "baz") should typeAs(
        ("Unnamed.Test.Foo" | "my_pkg.My_Lib.Util.Util_2" | "my_pkg.My_Lib.Util.Util_Sum") ->: "Unnamed.Test.Foo"
      )
    }
  }

}

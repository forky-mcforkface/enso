package org.enso.interpreter.node.expression.builtin.error.displaytext;

import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.UnexpectedResultException;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.runtime.callable.atom.Atom;
import org.enso.interpreter.runtime.callable.atom.AtomConstructor;
import org.enso.interpreter.runtime.data.text.Text;
import org.enso.interpreter.runtime.type.TypesGen;

@BuiltinMethod(type = "Arithmetic_Error", name = "to_display_text")
public abstract class ArithmeticErrorToDisplayTextNode extends Node {
  static ArithmeticErrorToDisplayTextNode build() {
    return ArithmeticErrorToDisplayTextNodeGen.create();
  }

  abstract Text execute(Object self);

  @Specialization
  Text doAtom(Atom self) {
    try {
      return Text.create("Arithmetic error: ", TypesGen.expectText(self.getFields()[0]));
    } catch (UnexpectedResultException e) {
      return Text.create("Arithmetic error.");
    }
  }

  @Specialization
  Text doConstructor(AtomConstructor self) {
    return Text.create("Arithmetic error.");
  }
}

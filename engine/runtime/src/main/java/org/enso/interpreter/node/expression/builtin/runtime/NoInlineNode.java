package org.enso.interpreter.node.expression.builtin.runtime;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.nodes.Node;
import org.enso.interpreter.dsl.BuiltinMethod;
import org.enso.interpreter.dsl.MonadicState;
import org.enso.interpreter.dsl.Suspend;
import org.enso.interpreter.node.BaseNode;
import org.enso.interpreter.node.callable.thunk.ThunkExecutorNode;
import org.enso.interpreter.runtime.state.Stateful;

@BuiltinMethod(
    type = "Runtime",
    name = "no_inline",
    description = "Runs its argument without the possibility of getting inlined.")
public class NoInlineNode extends Node {
  private @Child ThunkExecutorNode thunkExecutorNode = ThunkExecutorNode.build();

  @CompilerDirectives.TruffleBoundary
  Stateful execute(@MonadicState Object state, @Suspend Object action) {
    return thunkExecutorNode.executeThunk(action, state, BaseNode.TailStatus.NOT_TAIL);
  }
}

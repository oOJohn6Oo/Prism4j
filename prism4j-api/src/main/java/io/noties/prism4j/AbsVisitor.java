package io.noties.prism4j;

import org.jetbrains.annotations.NotNull;

import java.util.List;

public abstract class AbsVisitor implements Prism4j.Visitor {

    @Override
    public void visit(@NotNull List<? extends Prism4j.Node> nodes) {
        for (Prism4j.Node node : nodes) {
            if (node.isSyntax()) {
                visitSyntax((Prism4j.Syntax) node);
            } else {
                visitText((Prism4j.Text) node);
            }
        }
    }

    protected abstract void visitText(@NotNull Prism4j.Text text);

    // do not forget to call visit(syntax.children()) inside
    protected abstract void visitSyntax(@NotNull Prism4j.Syntax syntax);
}

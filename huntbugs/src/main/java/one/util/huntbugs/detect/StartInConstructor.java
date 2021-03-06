/*
 * Copyright 2016 HuntBugs contributors
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package one.util.huntbugs.detect;

import java.util.List;

import com.strobel.assembler.metadata.MethodDefinition;
import com.strobel.assembler.metadata.MethodReference;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.decompiler.ast.AstCode;
import com.strobel.decompiler.ast.Expression;
import com.strobel.decompiler.ast.Node;

import one.util.huntbugs.db.Hierarchy.TypeHierarchy;
import one.util.huntbugs.registry.MethodContext;
import one.util.huntbugs.registry.anno.AstNodes;
import one.util.huntbugs.registry.anno.AstVisitor;
import one.util.huntbugs.registry.anno.MethodVisitor;
import one.util.huntbugs.registry.anno.WarningDefinition;
import one.util.huntbugs.util.NodeChain;
import one.util.huntbugs.util.Types;

/**
 * @author Tagir Valeev
 *
 */
@WarningDefinition(category = "Multithreading", name = "StartInConstructor", maxScore = 50)
public class StartInConstructor {
    @MethodVisitor
    public boolean checkMethod(MethodDefinition md, TypeDefinition td) {
        return td.isPublic() && !td.isFinal() && !md.isPrivate() && !md.isPackagePrivate();
    }

    @AstVisitor(nodes = AstNodes.EXPRESSIONS, methodName = "<init>")
    public boolean visit(Expression expr, NodeChain nc, MethodContext mc, TypeHierarchy th) {
        if (expr.getCode() == AstCode.InvokeVirtual) {
            MethodReference mr = (MethodReference) expr.getOperand();
            if (mr.getName().equals("start") && mr.getSignature().equals("()V")) {
                if (Types.isInstance(mr.getDeclaringType(), "java/lang/Thread")) {
                    int priority = 0;
                    if (th != null) {
                        if (!th.hasSubClasses())
                            priority += 10;
                        else if (!th.hasSubClassesOutOfPackage())
                            priority += 5;
                    }
                    List<Node> body = nc.getRoot().getBody();
                    if (body.get(body.size() - 1) == expr)
                        priority += 10;
                    mc.report("StartInConstructor", priority, expr);
                }
            }
        }
        return true;
    }
}

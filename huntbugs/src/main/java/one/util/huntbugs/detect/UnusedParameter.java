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
import java.util.Set;

import com.strobel.assembler.metadata.MethodDefinition;
import com.strobel.assembler.metadata.MethodReference;
import com.strobel.assembler.metadata.ParameterDefinition;
import com.strobel.assembler.metadata.TypeDefinition;
import com.strobel.decompiler.ast.AstCode;
import com.strobel.decompiler.ast.Block;
import com.strobel.decompiler.ast.Expression;
import com.strobel.decompiler.ast.Node;
import com.strobel.decompiler.ast.Variable;

import one.util.huntbugs.db.Hierarchy;
import one.util.huntbugs.registry.MethodContext;
import one.util.huntbugs.registry.anno.AstNodes;
import one.util.huntbugs.registry.anno.AstVisitor;
import one.util.huntbugs.registry.anno.WarningDefinition;
import one.util.huntbugs.util.AccessLevel;
import one.util.huntbugs.util.Annotations;
import one.util.huntbugs.util.Methods;
import one.util.huntbugs.util.Nodes;
import one.util.huntbugs.util.Types;
import one.util.huntbugs.warning.Roles;

/**
 * @author shustkost
 *
 */
@WarningDefinition(category = "Correctness", name = "ConstructorParameterIsNotPassed", maxScore = 65)
@WarningDefinition(category = "Correctness", name = "MethodParameterIsNotPassed", maxScore = 65)
@WarningDefinition(category = "Correctness", name = "ParameterOverwritten", maxScore = 60)
@WarningDefinition(category = "RedundantCode", name = "MethodParameterIsNotUsed", maxScore = 35)
public class UnusedParameter {
    @AstVisitor(nodes = AstNodes.ROOT)
    public void visitBody(Block block, MethodContext mc, MethodDefinition md, TypeDefinition td, Hierarchy h) {
        if (md.isSynthetic() || !mc.isAnnotated() || Methods.isSerializationMethod(md) || Methods.isMain(md)
                || isEmpty(block.getBody()) || Nodes.isThrow(block))
            return;
        for (ParameterDefinition pd : md.getParameters()) {
            if (!pd.hasName())
                continue;
            Set<Expression> usages = mc.getParameterUsages(pd);
            if (usages != null && usages.isEmpty()) {
                // looks like autogenerated by jay
                if (pd.getName().equals("yyVal") && Types.isObject(pd.getParameterType()))
                    continue;
                // looks like autogenerated by protobuf
                if (Types.isInstance(td, "com/google/protobuf/GeneratedMessage"))
                    return;
                Expression overloadCall = findOverloadCall(md, block);
                if (overloadCall != null) {
                    MethodDefinition overloadMethod = ((MethodReference) overloadCall.getOperand()).resolve();
                    if (overloadMethod != null) {
                        ParameterDefinition anotherParam = overloadMethod.getParameters().stream().filter(apd -> apd
                                .getName().equals(pd.getName()) && apd.getParameterType().isEquivalentTo(pd
                                        .getParameterType())).findFirst().orElse(null);
                        if (anotherParam != null) {
                            int priority = 0;
                            Expression arg = overloadCall.getArguments().get(overloadMethod.getParameters().indexOf(
                                anotherParam) + (md.isStatic() ? 0 : 1));
                            if (Nodes.getConstant(arg) == null && arg.getCode() != AstCode.AConstNull) {
                                priority += 10;
                            }
                            mc.report(md.isConstructor() ? "ConstructorParameterIsNotPassed" : "MethodParameterIsNotPassed",
                                priority, overloadCall, Roles.VARIABLE.create(pd.getName()));
                            continue;
                        }
                    }
                }
                Node overwrite = Nodes.find(block, n -> {
                    if (!(n instanceof Expression))
                        return false;
                    Expression expr = (Expression) n;
                    return expr.getCode() == AstCode.Store && ((Variable) expr.getOperand())
                            .getOriginalParameter() == pd;
                });
                if (overwrite != null) {
                    mc.report("ParameterOverwritten", Methods.findSuperMethod(md) == null ? 0 : 20, overwrite);
                    continue;
                }
                if (Annotations.hasAnnotation(pd, false) || Methods.findSuperMethod(md) != null || h.isOverridden(md)) {
                    continue;
                }
                int priority = md.isConstructor() || md.isStatic() ? AccessLevel.of(md).select(10, 7, 5, 0)
                        : AccessLevel.of(md).select(20, 15, 5, 0);
                if(md.isDeprecated())
                    priority += 10;
                if(block.getBody().size() == 1) {
                    Node stmt = block.getBody().get(0);
                    if(Nodes.isOp(stmt, AstCode.Return)) {
                        List<Expression> args = ((Expression)stmt).getArguments();
                        if(args.size() == 1 && Nodes.getConstant(args.get(0)) != null) {
                            priority += 10;
                        }
                    }
                }
                mc.report("MethodParameterIsNotUsed", priority, Roles.VARIABLE.create(pd.getName()));
            }
        }
    }

    private boolean isEmpty(List<Node> body) {
        if(body.isEmpty())
            return true;
        if(body.size() == 1) {
            Node n = body.get(0);
            if(Nodes.isOp(n, AstCode.Return)) {
                Expression e = (Expression) n;
                if(e.getArguments().size() == 1) {
                    Expression arg = e.getArguments().get(0);
                    if(arg.getCode() == AstCode.AConstNull)
                        return true;
                    if(arg.getCode() == AstCode.LdC) {
                        Object value = arg.getOperand();
                        if(value != null && (value.equals(Boolean.FALSE) || value instanceof Number && ((Number)value).doubleValue() == 0.0))
                            return true;
                    }
                }
            }
        }
        return false;
    }

    private Expression findOverloadCall(MethodDefinition md, Block block) {
        return (Expression)Nodes.find(block, node -> {
            if (Nodes.isOp(node, md.isConstructor() ? AstCode.InvokeSpecial
                    : md.isStatic() ? AstCode.InvokeStatic : AstCode.InvokeVirtual)) {
                Expression expr = (Expression) node;
                MethodReference mr = (MethodReference) expr.getOperand();
                if (mr.getDeclaringType().isEquivalentTo(md.getDeclaringType()) && mr.getName().equals(md.getName())
                    && !mr.getSignature().equals(md.getSignature())) {
                    return true;
                }
            }
            return false;
        });
    }
}

/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.painless.node;

import org.elasticsearch.painless.Location;
import org.elasticsearch.painless.phase.UserTreeVisitor;

import java.util.Objects;

/**
 * Represents a function reference.
 */
public class EFunctionRef extends AExpression {

    private final String symbol;
    private final String methodName;

    public EFunctionRef(int identifier, Location location, String symbol, String methodName) {
        super(identifier, location);

        this.symbol = Objects.requireNonNull(symbol);
        this.methodName = Objects.requireNonNull(methodName);
    }

    public String getSymbol() {
        return symbol;
    }

    public String getMethodName() {
        return methodName;
    }

    @Override
    public <Scope> void visit(UserTreeVisitor<Scope> userTreeVisitor, Scope scope) {
        userTreeVisitor.visitFunctionRef(this, scope);
    }

    @Override
    public <Scope> void visitChildren(UserTreeVisitor<Scope> userTreeVisitor, Scope scope) {
        // terminal node; no children
    }
}

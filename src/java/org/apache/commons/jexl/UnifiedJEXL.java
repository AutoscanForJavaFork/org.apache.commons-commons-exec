/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.commons.jexl;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import org.apache.commons.jexl.parser.SimpleNode;
import org.apache.commons.jexl.parser.ParseException;
import org.apache.commons.jexl.parser.StringParser;

/**
 * An evaluator similar to the unified EL evaluator used in JSP/JSF based on JEXL.
 * It is intended to be used in configuration modules, XML based frameworks or JSP taglibs
 * and facilitate the implementation of expression evaluation.
 * <p>
 * An expression can mix immediate, deferred and nested sub-expressions as well as string constants;
 * <ul>
 * <li>The "immediate" syntax is of the form "...${jexl-expr}..."</li>
 * <li>The "deferred" syntax is of the form "...#{jexl-expr}..."</li>
 * <li>The "nested" syntax is of the form "...#{...${jexl-expr0}...}..."</li>
 * <li>The "composite" syntax is of the form "...${jexl-expr0}... #{jexl-expr1}..."</li>
 * </ul>
 * </p>
 * <p>
 * Deferred & immediate expression carry different intentions:
 * <ul>
 * <li>An immediate expression indicate that evaluation is intended to be performed close to
 * the definition/parsing point.</li>
 * <li>A deferred expression indicate that evaluation is intended to occur at a later stage.</li>
 * </ul>
 * </p>
 * <p>
 * For instance: "Hello ${name}, now is #{time}" is a composite "deferred" expression since one
 * of its subexpressions is deferred. Furthermore, this (composite) expression intent is
 * to perform two evaluations; one close to its definition and another one in a later
 * phase.
 * </p>
 * <p>
 * The API reflects this feature in 2 methods, prepare and evaluate. The prepare method
 * will evaluate the immediate subexpression and return an expression that contains only
 * the deferred subexpressions (& constants), a prepared expression. Such a prepared expression
 * is suitable for a later phase evaluation that may occur with a different JexlContext.
 * Note that it is valid to call evaluate without prepare in which case the same JexlContext
 * is used for the 2 evaluation phases.
 * </p>
 * <p>
 * In the most common use-case where deferred expressions are to be kept around as properties of objects,
 * one should parse & prepare an expression before storing it and evaluate it each time
 * the property storing it is accessed.
 * </p>
 * <p>
 * Note that nested expression use the JEXL syntax as in:
 * <code>"#{${bar}+'.charAt(2)'}"</code>
 * The most common mistake leading to an invalid expression being the following:
 * <code>"#{${bar}charAt(2)}"</code>
 * </p>
 * <p>Also note that methods that parse evaluate expressions may throw <em>unchecked</em> ecxeptions;
 * The {@link UnifiedJEXL.Exception} are thrown when the engine instance is in "non-silent" mode
 * but since these are RuntimeException, user-code <em>should</em> catch them where appropriate.
 * </p>
 * @since 2.0
 */
public final class UnifiedJEXL {
    /** The JEXL engine instance. */
    private final JexlEngine jexl;
    /** The expression cache. */
    private final Map<String, Expression> cache;
    /** The default cache size. */
    private static final int CACHE_SIZE = 512;
    /** The default cache load-factor. */
    private static final float LOAD_FACTOR = 0.75f;
    /**
     * Creates a new instance of UnifiedJEXL with a default size cache.
     * @param aJexl the JexlEngine to use.
     */
    public UnifiedJEXL(JexlEngine aJexl) {
        this(aJexl, CACHE_SIZE);
    }

    /**
     * Creates a new instance of UnifiedJEXL creating a local cache.
     * @param aJexl the JexlEngine to use.
     * @param cacheSize the number of expressions in this cache
     */
    public UnifiedJEXL(JexlEngine aJexl, int cacheSize) {
        this.jexl = aJexl;
        this.cache = cacheSize > 0 ? createCache(cacheSize) : null;
    }

    /**
     * Creates an expression cache.
     * @param cacheSize the cache size, must be > 0
     * @return a LinkedHashMap
     */
    private static Map<String, Expression> createCache(final int cacheSize) {
        return new LinkedHashMap<String, Expression>(cacheSize, LOAD_FACTOR, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry eldest) {
                return size() > cacheSize;
            }
        };
    }

    /**
     * Types of expressions.
     * Each instance carries a counter index per (composite sub-) expression type.
     * @see ExpressionBuilder
     */
    private static enum ExpressionType {
        CONSTANT(0), // constant count at index 0
        IMMEDIATE(1), // immediate count at index 1
        DEFERRED(2), // deferred count at index 2
        NESTED(2), // nested are counted as deferred thus count at index 2
        COMPOSITE(-1); // composite are not counted
        /** the index in arrays of expression counters for composite expressions. */
        private final int index;
        /**
         * Creates an ExpressionType.
         * @param index the index for this type in counters arrays.
         */
        ExpressionType(int index) {
            this.index = index;
        }
    }

    /**
     * A helper class to build expressions.
     * Keeps count of sub-expressions by type.
     */
    private static class ExpressionBuilder {
        private final int[] counts;
        private final ArrayList<Expression> expressions;

        /**
         * Creates a builder.
         * @param size the initial expression array size
         */
        ExpressionBuilder(int size) {
            counts = new int[]{0, 0, 0};
            expressions = new ArrayList<Expression>(size <= 0 ? 3 : size);
        }

        /**
         * Adds an expression to the list of expressions, maintain per-type counts.
         * @param expr the expression to add
         */
        void add(Expression expr) {
            counts[expr.getType().index] += 1;
            expressions.add(expr);
        }

        /**
         * Builds an expression from a source, performs checks.
         * @param el the unified el instance
         * @param source the source expression
         * @return an expression
         */
        Expression build(UnifiedJEXL el, Expression source) {
            int sum = 0;
            for (int count : counts) {
                sum += count;
            }
            if (expressions.size() != sum) {
                StringBuilder error = new StringBuilder("parsing algorithm error, exprs: ");
                error.append(expressions.size());
                error.append(", constant:");
                error.append(counts[ExpressionType.CONSTANT.index]);
                error.append(", immediate:");
                error.append(counts[ExpressionType.IMMEDIATE.index]);
                error.append(", deferred:");
                error.append(counts[ExpressionType.DEFERRED.index]);
                throw new IllegalStateException(error.toString());
            }
            // if only one sub-expr, no need to create a composite
            if (expressions.size() == 1) {
                return expressions.get(0);
            } else {
                return el.new CompositeExpression(counts, expressions, source);
            }
        }
    }

    /**
     * Gets the JexlEngine underlying the UnifiedJEXL.
     * @return the JexlEngine
     */
    public JexlEngine getEngine() {
        return jexl;
    }

    /**
     * The sole type of (runtime) exception the UnifiedJEXL can throw.
     */
    public class Exception extends RuntimeException {
        /** {@inheritDoc} */
        public Exception(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    /**
     * The abstract base class for all expressions, immediate '${...}' and deferred '#{...}'.
     */
    public abstract class Expression {
        protected final Expression source;
        /**
         * Creates an expression.
         * @param source the source expression if any
         */
        Expression(Expression source) {
            this.source = source != null ? source : this;
        }

        @Override
        public String toString() {
            StringBuilder strb = new StringBuilder();
            if (source != this) {
                strb.append(source.toString());
                strb.append(" /*= ");
            }
            asString(strb);
            if (source != this) {
                strb.append(" */");
            }
            return strb.toString();
        }

        /**
         * Generates this expression's string representation.
         * @return the string representation
         */
        public String asString() {
            StringBuilder strb = new StringBuilder();
            asString(strb);
            return strb.toString();
        }

        /**
         * Adds this expression's string representation to a StringBuilder.
         */
        abstract void asString(StringBuilder strb);

        /**
         * When the expression is dependant upon immediate and deferred sub-expressions,
         * evaluates the immediate sub-expressions with the context passed as parameter
         * and returns this expression deferred form.
         * <p>
         * In effect, this binds the result of the immediate sub-expressions evaluation in the
         * context, allowing to differ evaluation of the remaining (deferred) expression within another context.
         * This only has an effect to nested & composite expressions that contain differed & immediate sub-expressions.
         * </p>
         * <p>
         * If the underlying JEXL engine is silent, errors will be logged through its logger as warning.
         * </p>
         * @param context the context to use for immediate expression evaluations
         * @return  an expression or null if an error occurs and the {@link JexlEngine} is silent
         * @throws {@link UnifiedJEXL.Exception} if an error occurs and the {@link JexlEngine} is not silent
         */
        public abstract Expression prepare(JexlContext context);

        /**
         * Evaluates this expression.
         * <p>
         * If the underlying JEXL engine is silent, errors will be logged through its logger as warning.
         * </p>
         * @param context the variable context
         * @return the result of this expression evaluation or null if an error occurs and the {@link JexlEngine} is
         * silent
         * @throws {@link UnifiedJEXL.Exception} if an error occurs and the {@link JexlEngine} is not silent
         */
        public abstract Object evaluate(JexlContext context);

        /**
         * Checks whether this expression is immediate.
         * @return true if immediate, false otherwise
         */
        public boolean isImmediate() {
            return true;
        }

        /**
         * Checks whether this expression is deferred.
         * @return true if deferred, false otherwise
         */
        public final boolean isDeferred() {
            return !isImmediate();
        }

        /**
         * Retrieves this expression's source expression.
         * If this expression was prepared, this allows to retrieve the
         * original expression that lead to it.
         * Other expressions return themselves.
         * @return the source expression
         */
        public final Expression getSource() {
            return source;
        }

        /**
         * Gets this expression type.
         * @return its type
         */
        abstract ExpressionType getType();

        /**
         * Prepares a sub-expression for interpretation.
         * @param interpreter a JEXL interpreter
         * @return a prepared expression
         * @throws ParseException (only for nested & composite)
         */
        abstract Expression prepare(Interpreter interpreter) throws ParseException;

        /**
         * Intreprets a sub-expression.
         * @param interpreter a JEXL interpreter
         * @return the result of interpretation
         * @throws ParseException (only for nested & composite)
         */
        abstract Object evaluate(Interpreter interpreter) throws ParseException;
    }


    /** A constant expression. */
    private class ConstantExpression extends Expression {
        private final Object value;
        /**
         * Creates a constant expression.
         * @param value the constant value
         * @param source the source expression if any
         */
        ConstantExpression(Object value, Expression source) {
            super(source);
            if (value == null) {
                throw new NullPointerException("constant can not be null");
            }
            if (value instanceof String) {
                value = StringParser.buildString((String) value, false);
            }
            this.value = value;
        }

        @Override
        public String asString() {
            StringBuilder strb = new StringBuilder();
            strb.append('"');
            asString(strb);
            strb.append('"');
            return strb.toString();
        }

        @Override
        ExpressionType getType() {
            return ExpressionType.CONSTANT;
        }

        @Override
        void asString(StringBuilder strb) {
            String str = value.toString();
            if (value instanceof String || value instanceof CharSequence) {
                for (int i = 0, size = str.length(); i < size; ++i) {
                    char c = str.charAt(i);
                    if (c == '"' || c == '\\') {
                        strb.append('\\');
                    }
                    strb.append(c);
                }
            } else {
                strb.append(str);
            }
        }

        @Override
        public Expression prepare(JexlContext context) {
            return this;
        }

        @Override
        Expression prepare(Interpreter interpreter) throws ParseException {
            return this;
        }

        @Override
        public Object evaluate(JexlContext context) {
            return value;
        }

        @Override
        Object evaluate(Interpreter interpreter) throws ParseException {
            return value;
        }
    }


    /** The base for Jexl based expressions. */
    private abstract class JexlBasedExpression extends Expression {
        protected final CharSequence expr;
        protected final SimpleNode node;
        /**
         * Creates a JEXL interpretable expression.
         * @param expr the expression as a string
         * @param node the expression as an AST
         * @param source the source expression if any
         */
        protected JexlBasedExpression(CharSequence expr, SimpleNode node, Expression source) {
            super(source);
            this.expr = expr;
            this.node = node;
        }

        @Override
        public String toString() {
            StringBuilder strb = new StringBuilder(expr.length() + 3);
            if (source != this) {
                strb.append(source.toString());
                strb.append(" /*= ");
            }
            strb.append(isImmediate() ? '$' : '#');
            strb.append("{");
            strb.append(expr);
            strb.append("}");
            if (source != this) {
                strb.append(" */");
            }
            return strb.toString();
        }

        @Override
        public void asString(StringBuilder strb) {
            strb.append(isImmediate() ? '$' : '#');
            strb.append("{");
            strb.append(expr);
            strb.append("}");
        }

        @Override
        public Expression prepare(JexlContext context) {
            return this;
        }

        @Override
        Expression prepare(Interpreter interpreter) throws ParseException {
            return this;
        }

        @Override
        public Object evaluate(JexlContext context) {
            return UnifiedJEXL.this.evaluate(context, this);
        }

        @Override
        Object evaluate(Interpreter interpreter) throws ParseException {
            return interpreter.interpret(node);
        }
    }


    /** An immediate expression: ${jexl}. */
    private class ImmediateExpression extends JexlBasedExpression {
        /**
         * Creates an immediate expression.
         * @param expr the expression as a string
         * @param node the expression as an AST
         * @param source the source expression if any
         */
        ImmediateExpression(CharSequence expr, SimpleNode node, Expression source) {
            super(expr, node, source);
        }

        @Override
        ExpressionType getType() {
            return ExpressionType.IMMEDIATE;
        }

        @Override
        public boolean isImmediate() {
            return true;
        }
    }

    /** An immediate expression: ${jexl}. */
    private class DeferredExpression extends JexlBasedExpression {
        /**
         * Creates a deferred expression.
         * @param expr the expression as a string
         * @param node the expression as an AST
         * @param source the source expression if any
         */
        DeferredExpression(CharSequence expr, SimpleNode node, Expression source) {
            super(expr, node, source);
        }

        @Override
        ExpressionType getType() {
            return ExpressionType.DEFERRED;
        }

        @Override
        public boolean isImmediate() {
            return false;
        }
    }

    /**
     * A deferred expression that nests an immediate expression.
     * #{...${jexl}...}
     * Note that the deferred syntax is JEXL's, not UnifiedJEXL.
     */
    private class NestedExpression extends DeferredExpression {
        /**
         * Creates a nested expression.
         * @param expr the expression as a string
         * @param node the expression as an AST
         * @param source the source expression if any
         */
        NestedExpression(CharSequence expr, SimpleNode node, Expression source) {
            super(expr, node, source);
            if (this.source != this) {
                throw new IllegalArgumentException("Nested expression can not have a source");
            }
        }

        @Override
        ExpressionType getType() {
            return ExpressionType.NESTED;
        }

        @Override
        public String toString() {
            return expr.toString();
        }

        @Override
        public Expression prepare(JexlContext context) {
            return UnifiedJEXL.this.prepare(context, this);
        }

        @Override
        public Expression prepare(Interpreter interpreter) throws ParseException {
            String value = interpreter.interpret(node).toString();
            SimpleNode dnode = toNode(value);
            return new DeferredExpression(value, dnode, this);
        }

        @Override
        public Object evaluate(Interpreter interpreter) throws ParseException {
            return prepare(interpreter).evaluate(interpreter);
        }
    }


    /** A composite expression: "... ${...} ... #{...} ...". */
    private class CompositeExpression extends Expression {
        // bit encoded (deferred count > 0) bit 1, (immediate count > 0) bit 0
        private final int meta;
        // the list of expression resulting from parsing
        private final Expression[] exprs;
        /**
         * Creates a composite expression.
         * @param counts counters of expression per type
         * @param exprs the sub-expressions
         * @param source the source for this expresion if any
         */
        CompositeExpression(int[] counts, ArrayList<Expression> exprs, Expression source) {
            super(source);
            this.exprs = exprs.toArray(new Expression[exprs.size()]);
            this.meta = (counts[ExpressionType.DEFERRED.index] > 0 ? 2 : 0) |
                        (counts[ExpressionType.IMMEDIATE.index] > 0 ? 1 : 0);
        }

        @Override
        ExpressionType getType() {
            return ExpressionType.COMPOSITE;
        }

        @Override
        public boolean isImmediate() {
            // immediate if no deferred
            return (meta & 2) == 0;
        }

        @Override
        void asString(StringBuilder strb) {
            for (Expression e : exprs) {
                e.asString(strb);
            }
        }

        @Override
        public Expression prepare(JexlContext context) {
            return UnifiedJEXL.this.prepare(context, this);
        }

        @Override
        Expression prepare(Interpreter interpreter) throws ParseException {
            // if this composite is not its own source, it is already prepared
            if (source != this) {
                return this;
            }
            // we need to eval immediate expressions if there are some deferred/nested
            // ie both immediate & deferred counts > 0, bits 1 & 0 set, (1 << 1) & 1 == 3
            final boolean evalImmediate = meta == 3;
            final int size = exprs.length;
            final ExpressionBuilder builder = new ExpressionBuilder(size);
            // tracking whether prepare will return a different expression
            boolean eq = true;
            for (int e = 0; e < size; ++e) {
                Expression expr = exprs[e];
                Expression prepared = expr.prepare(interpreter);
                if (evalImmediate && prepared instanceof ImmediateExpression) {
                    // evaluate immediate as constant
                    Object value = prepared.evaluate(interpreter);
                    prepared = value == null ? null : new ConstantExpression(value, prepared);
                }
                // add it if not null
                if (prepared != null) {
                    builder.add(prepared);
                }
                // keep track of expression equivalence
                eq &= expr == prepared;
            }
            Expression ready = eq ? this : builder.build(UnifiedJEXL.this, this);
            return ready;
        }

        @Override
        public Object evaluate(JexlContext context) {
            return UnifiedJEXL.this.evaluate(context, this);
        }

        @Override
        Object evaluate(Interpreter interpreter) throws ParseException {
            final int size = exprs.length;
            Object value = null;
            // common case: evaluate all expressions & concatenate them as a string
            StringBuilder strb = new StringBuilder();
            for (int e = 0; e < size; ++e) {
                value = exprs[e].evaluate(interpreter);
                if (value != null) {
                    strb.append(value.toString());
                }
            }
            value = strb.toString();
            return value;
        }
    }

    /** Creates a a {@link UnifiedJEXL.Expression} from an expression string.
     *  Uses & fills up the expression cache if any.
     * <p>
     * If the underlying JEXL engine is silent, errors will be logged through its logger as warnings.
     * </p>
     * @param expression the UnifiedJEXL string expression
     * @return the UnifiedJEXL object expression, null if silent and an error occured
     * @throws {@link UnifiedJEXL.Exception} if an error occurs and the {@link JexlEngine} is not silent
     */
    public Expression parse(String expression) {
        try {
            if (cache == null) {
                return parseExpression(expression);
            } else synchronized (cache) {
                Expression stmt = cache.get(expression);
                if (stmt == null) {
                    stmt = parseExpression(expression);
                    cache.put(expression, stmt);
                }
                return stmt;
            }
        } catch (JexlException xjexl) {
            Exception xuel = new Exception("failed to parse '" + expression + "'", xjexl);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        } catch (ParseException xparse) {
            Exception xuel = new Exception("failed to parse '" + expression + "'", xparse);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        }
    }

    /**
     * Prepares an expression (nested & composites), handles exception reporting.
     *
     * @param context the JEXL context to use
     * @param expr the expression to prepare
     * @return a prepared expression
     * @throws {@link UnifiedJEXL.Exception} if an error occurs and the {@link JexlEngine} is not silent
     */
    Expression prepare(JexlContext context, Expression expr) {
        try {
            Interpreter interpreter = jexl.createInterpreter(context);
            interpreter.setSilent(false);
            return expr.prepare(interpreter);
        } catch (JexlException xjexl) {
            Exception xuel = createException("prepare", expr, xjexl);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        } catch (ParseException xparse) {
            Exception xuel = createException("prepare", expr, xparse);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        }
    }

    /**
     * Evaluates an expression (nested & composites), handles exception reporting.
     *
     * @param context the JEXL context to use
     * @param expr the expression to prepare
     * @return the result of the evaluation
     * @throws {@link UnifiedJEXL.Exception} if an error occurs and the {@link JexlEngine} is not silent
     */
    Object evaluate(JexlContext context, Expression expr) {
        try {
            Interpreter interpreter = jexl.createInterpreter(context);
            interpreter.setSilent(false);
            return expr.evaluate(interpreter);
        } catch (JexlException xjexl) {
            Exception xuel = createException("evaluate", expr, xjexl);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        } catch (ParseException xparse) {
            Exception xuel = createException("evaluate", expr, xparse);
            if (jexl.isSilent()) {
                jexl.logger.warn(xuel.getMessage(), xuel.getCause());
                return null;
            }
            throw xuel;
        }
    }

    /**
     * Use the JEXL parser to create the AST for an expression.
     * @param expression the expression to parse
     * @return the AST
     */
    private SimpleNode toNode(CharSequence expression) throws ParseException {
        return (SimpleNode) jexl.parse(expression).jjtGetChild(0);
    }

    /**
     * Creates a UnifiedJEXL.Exception from a JexlException.
     * @param action parse, prepare, evaluate
     * @param expr the expression
     * @param xany the exception
     * @return an exception contained an explicit error message
     */
    private Exception createException(String action, Expression expr, java.lang.Exception xany) {
        StringBuilder strb = new StringBuilder("failed to ");
        strb.append(action);
        strb.append(" '");
        strb.append(expr.toString());
        strb.append("'");
        Throwable cause = xany.getCause();
        if (cause != null) {
            String causeMsg = cause.getMessage();
            if (causeMsg != null) {
                strb.append(", ");
                strb.append(causeMsg);
            }
        }
        return new Exception(strb.toString(), xany);
    }


    /** The different parsing states. */
    private static enum ParseState {
        CONST, // parsing a constant string
        IMMEDIATE0, // seen $
        DEFERRED0, // seen #
        IMMEDIATE1, // seen ${
        DEFERRED1, // seen #{
        ESCAPE // seen \
    }

    /**
     * Parses a unified expression.
     * @param expr the expression
     * @param counts the expression type counters
     * @return the list of expressions
     * @throws Exception
     */
    private Expression parseExpression(String expr) throws ParseException {
        final int size = expr.length();
        ExpressionBuilder builder = new ExpressionBuilder(0);
        StringBuilder strb = new StringBuilder(size);
        ParseState state = ParseState.CONST;
        int inner = 0;
        boolean nested = false;
        int inested = -1;
        for (int i = 0; i < size; ++i) {
            char c = expr.charAt(i);
            switch (state) {
                default: // in case we ever add new expression type
                    throw new UnsupportedOperationException("unexpected expression type");
                case CONST:
                    if (c == '$') {
                        state = ParseState.IMMEDIATE0;
                    } else if (c == '#') {
                        inested = i;
                        state = ParseState.DEFERRED0;
                    } else if (c == '\\') {
                        state = ParseState.ESCAPE;
                    } else {
                        // do buildup expr
                        strb.append(c);
                    }
                    break;
                case IMMEDIATE0: // $
                    if (c == '{') {
                        state = ParseState.IMMEDIATE1;
                        // if chars in buffer, create constant
                        if (strb.length() > 0) {
                            Expression cexpr = new ConstantExpression(strb.toString(), null);
                            builder.add(cexpr);
                            strb.delete(0, Integer.MAX_VALUE);
                        }
                    } else {
                        // revert to CONST
                        strb.append('$');
                        strb.append(c);
                        state = ParseState.CONST;
                    }
                    break;
                case DEFERRED0: // #
                    if (c == '{') {
                        state = ParseState.DEFERRED1;
                        // if chars in buffer, create constant
                        if (strb.length() > 0) {
                            Expression cexpr = new ConstantExpression(strb.toString(), null);
                            builder.add(cexpr);
                            strb.delete(0, Integer.MAX_VALUE);
                        }
                    } else {
                        // revert to CONST
                        strb.append('#');
                        strb.append(c);
                        state = ParseState.CONST;
                    }
                    break;
                case IMMEDIATE1: // ${...
                    if (c == '}') {
                        // materialize the immediate expr
                        //Expression iexpr = createExpression(ExpressionType.IMMEDIATE, strb, null);
                        Expression iexpr = new ImmediateExpression(strb.toString(), toNode(strb), null);
                        builder.add(iexpr);
                        strb.delete(0, Integer.MAX_VALUE);
                        state = ParseState.CONST;
                    } else {
                        // do buildup expr
                        strb.append(c);
                    }
                    break;
                case DEFERRED1: // #{...
                    // skip inner strings (for '}')
                    if (c == '"' || c == '\'') {
                        strb.append(c);
                        i = StringParser.readString(strb, expr, i + 1, c);
                        continue;
                    }
                    // nested immediate in deferred; need to balance count of '{' & '}'
                    if (c == '{') {
                        if (expr.charAt(i - 1) == '$') {
                            inner += 1;
                            strb.deleteCharAt(strb.length() - 1);
                            nested = true;
                        }
                        continue;
                    }
                    // closing '}'
                    if (c == '}') {
                        // balance nested immediate
                        if (inner > 0) {
                            inner -= 1;
                        } else {
                            // materialize the nested/deferred expr
                            Expression dexpr = null;
                            if (nested) {
                                dexpr = new NestedExpression(expr.substring(inested, i + 1), toNode(strb), null);
                            } else {
                                dexpr = new DeferredExpression(strb.toString(), toNode(strb), null);
                            }
                            builder.add(dexpr);
                            strb.delete(0, Integer.MAX_VALUE);
                            nested = false;
                            state = ParseState.CONST;
                        }
                    } else {
                        // do buildup expr
                        strb.append(c);
                    }
                    break;
                case ESCAPE:
                    if (c == '#') {
                        strb.append('#');
                    } else {
                        strb.append('\\');
                        strb.append(c);
                    }
                    state = ParseState.CONST;
            }
        }
        // we should be in that state
        if (state != ParseState.CONST) {
            throw new ParseException("malformed expression: " + expr);
        }
        // if any chars were buffered, add them as a constant
        if (strb.length() > 0) {
            Expression cexpr = new ConstantExpression(strb.toString(), null);
            builder.add(cexpr);
        }
        return builder.build(this, null);
    }
}
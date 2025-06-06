/*
 * Copyright 1999-2017 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.druid.sql.visitor;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.*;
import com.alibaba.druid.sql.ast.expr.*;
import com.alibaba.druid.sql.ast.statement.*;
import com.alibaba.druid.sql.dialect.hive.ast.HiveInsert;
import com.alibaba.druid.sql.dialect.hive.ast.HiveMultiInsertStatement;
import com.alibaba.druid.sql.dialect.hive.stmt.HiveCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.expr.MySqlExpr;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.oracle.ast.expr.OracleExpr;
import com.alibaba.druid.sql.dialect.oracle.visitor.OracleASTVisitorAdapter;
import com.alibaba.druid.sql.dialect.postgresql.visitor.PGASTVisitorAdapter;
import com.alibaba.druid.sql.repository.SchemaObject;
import com.alibaba.druid.sql.repository.SchemaRepository;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.stat.TableStat.Column;
import com.alibaba.druid.stat.TableStat.Condition;
import com.alibaba.druid.stat.TableStat.Mode;
import com.alibaba.druid.stat.TableStat.Relationship;
import com.alibaba.druid.util.FnvHash;

import java.util.*;

public class SchemaStatVisitor extends SQLASTVisitorAdapter {
    protected SchemaRepository repository;

    protected final List<SQLName> originalTables = new ArrayList<SQLName>();

    protected final HashMap<TableStat.Name, TableStat> tableStats = new LinkedHashMap<TableStat.Name, TableStat>();
    protected final Map<Long, Column> columns = new LinkedHashMap<Long, Column>();
    protected final List<Condition> conditions = new ArrayList<Condition>();
    protected final Set<Relationship> relationships = new LinkedHashSet<Relationship>();
    protected final List<Column> orderByColumns = new ArrayList<Column>();
    protected final Set<Column> groupByColumns = new LinkedHashSet<Column>();
    protected final List<SQLAggregateExpr> aggregateFunctions = new ArrayList<SQLAggregateExpr>();
    protected final List<SQLMethodInvokeExpr> functions = new ArrayList<SQLMethodInvokeExpr>(2);

    private List<Object> parameters;

    private Mode mode;

    protected DbType dbType;

    public SchemaStatVisitor() {
        this((DbType) null);
    }

    public SchemaStatVisitor(SchemaRepository repository) {
        if (repository != null) {
            this.dbType = repository.getDbType();
        }
        this.repository = repository;
    }

    public SchemaStatVisitor(DbType dbType) {
        this(new SchemaRepository(dbType), new ArrayList<Object>());
        this.dbType = dbType;
    }

    public SchemaStatVisitor(List<Object> parameters) {
        this((DbType) null, parameters);
    }

    public SchemaStatVisitor(DbType dbType, List<Object> parameters) {
        this(new SchemaRepository(dbType), parameters);
        this.parameters = parameters;
    }

    public SchemaStatVisitor(SchemaRepository repository, List<Object> parameters) {
        this.repository = repository;
        this.parameters = parameters;
        if (repository != null) {
            DbType dbType = repository.getDbType();
            if (dbType != null && this.dbType == null) {
                this.dbType = dbType;
            }
        }
    }

    public SchemaRepository getRepository() {
        return repository;
    }

    public void setRepository(SchemaRepository repository) {
        this.repository = repository;
    }

    public List<Object> getParameters() {
        return parameters;
    }

    public void setParameters(List<Object> parameters) {
        this.parameters = parameters;
    }

    public TableStat getTableStat(String tableName) {
        tableName = handleName(tableName);

        TableStat.Name tableNameObj = new TableStat.Name(tableName);
        TableStat stat = tableStats.get(tableNameObj);
        if (stat == null) {
            stat = new TableStat();
            tableStats.put(new TableStat.Name(tableName), stat);
        }
        return stat;
    }

    public TableStat getTableStat(SQLName tableName) {
        String strName;
        if (tableName instanceof SQLIdentifierExpr) {
            strName = ((SQLIdentifierExpr) tableName).normalizedName();
        } else if (tableName instanceof SQLPropertyExpr) {
            strName = ((SQLPropertyExpr) tableName).normalizedName();
        } else {
            strName = tableName.toString();
        }

        long hashCode64 = tableName.hashCode64();

        if (hashCode64 == FnvHash.Constants.DUAL) {
            return null;
        }

        originalTables.add(tableName);

        TableStat.Name tableNameObj = new TableStat.Name(strName, hashCode64);
        TableStat stat = tableStats.get(tableNameObj);
        if (stat == null) {
            stat = new TableStat();
            tableStats.put(new TableStat.Name(strName, hashCode64), stat);
        }
        return stat;
    }

    protected Column addColumn(String tableName, String columnName) {
        Column c = new Column(tableName, columnName, dbType);

        Column column = this.columns.get(c.hashCode64());
        if (column == null && columnName != null) {
            column = c;
            columns.put(c.hashCode64(), c);
        }
        return column;
    }

    protected Column addColumn(SQLName table, String columnName) {
        String tableName;
        if (table instanceof SQLIdentifierExpr) {
            tableName = ((SQLIdentifierExpr) table).normalizedName();
        } else if (table instanceof SQLPropertyExpr) {
            tableName = ((SQLPropertyExpr) table).normalizedName();
        } else {
            tableName = table.toString();
        }

        long tableHashCode64 = table.hashCode64();

        long basic = tableHashCode64;
        basic ^= '.';
        basic *= FnvHash.PRIME;
        long columnHashCode64 = FnvHash.hashCode64(basic, columnName);

        Column column = this.columns.get(columnHashCode64);
        if (column == null && columnName != null) {
            column = new Column(tableName, columnName, columnHashCode64);
            columns.put(columnHashCode64, column);
        }
        return column;
    }

    private String handleName(String ident) {
        int len = ident.length();
        if (ident.charAt(0) == '[' && ident.charAt(len - 1) == ']') {
            ident = ident.substring(1, len - 1);
        } else {
            boolean flag0 = false;
            boolean flag1 = false;
            boolean flag2 = false;
            boolean flag3 = false;
            for (int i = 0; i < len; ++i) {
                final char ch = ident.charAt(i);
                if (ch == '\"') {
                    flag0 = true;
                } else if (ch == '`') {
                    flag1 = true;
                } else if (ch == ' ') {
                    flag2 = true;
                } else if (ch == '\'') {
                    flag3 = true;
                }
            }
            if (flag0) {
                ident = ident.replaceAll("\"", "");
            }

            if (flag1) {
                ident = ident.replaceAll("`", "");
            }

            if (flag2) {
                ident = ident.replaceAll(" ", "");
            }

            if (flag3) {
                ident = ident.replaceAll("'", "");
            }
        }
        return ident;
    }

    protected Mode getMode() {
        return mode;
    }

    protected void setModeOrigin(SQLObject x) {
        Mode originalMode = (Mode) x.getAttribute("_original_use_mode");
        mode = originalMode;
    }

    protected Mode setMode(SQLObject x, Mode mode) {
        Mode oldMode = this.mode;
        x.putAttribute("_original_use_mode", oldMode);
        this.mode = mode;
        return oldMode;
    }

    private boolean visitOrderBy(SQLIdentifierExpr x) {
        SQLTableSource tableSource = x.getResolvedTableSource();

        if (tableSource == null
                && x.getParent() instanceof SQLSelectOrderByItem
                && x.getParent().getParent() instanceof SQLOrderBy
                && x.getParent().getParent().getParent() instanceof SQLSelectQueryBlock) {
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) x.getParent().getParent().getParent();
            SQLSelectItem selectItem = queryBlock.findSelectItem(x.nameHashCode64());
            if (selectItem != null) {
                SQLExpr selectItemExpr = selectItem.getExpr();
                if (selectItemExpr instanceof SQLIdentifierExpr) {
                    x = (SQLIdentifierExpr) selectItemExpr;
                } else if (selectItemExpr instanceof SQLPropertyExpr) {
                    return visitOrderBy((SQLPropertyExpr) selectItemExpr);
                } else {
                    return false;
                }
            }
        }

        String tableName = null;
        if (tableSource instanceof SQLExprTableSource) {
            SQLExpr expr = ((SQLExprTableSource) tableSource).getExpr();
            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr table = (SQLIdentifierExpr) expr;
                tableName = table.getName();
            } else if (expr instanceof SQLPropertyExpr) {
                SQLPropertyExpr table = (SQLPropertyExpr) expr;
                tableName = table.toString();
            } else if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
                if ("table".equalsIgnoreCase(methodInvokeExpr.getMethodName())
                        && methodInvokeExpr.getArguments().size() == 1
                        && methodInvokeExpr.getArguments().get(0) instanceof SQLName) {
                    SQLName table = (SQLName) methodInvokeExpr.getArguments().get(0);

                    if (table instanceof SQLPropertyExpr) {
                        SQLPropertyExpr propertyExpr = (SQLPropertyExpr) table;
                        SQLIdentifierExpr owner = (SQLIdentifierExpr) propertyExpr.getOwner();
                        if (propertyExpr.getResolvedTableSource() != null
                                && propertyExpr.getResolvedTableSource() instanceof SQLExprTableSource) {
                            SQLExpr resolveExpr = ((SQLExprTableSource) propertyExpr.getResolvedTableSource()).getExpr();
                            if (resolveExpr instanceof SQLName) {
                                tableName = resolveExpr.toString() + "." + propertyExpr.getName();
                            }
                        }
                    }

                    if (tableName == null) {
                        tableName = table.toString();
                    }
                }
            }
        } else if (tableSource instanceof SQLWithSubqueryClause.Entry) {
            return false;
        } else if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSelectQueryBlock queryBlock = ((SQLSubqueryTableSource) tableSource).getSelect().getQueryBlock();
            if (queryBlock == null) {
                return false;
            }

            SQLSelectItem selectItem = queryBlock.findSelectItem(x.nameHashCode64());
            if (selectItem == null) {
                return false;
            }

            SQLExpr selectItemExpr = selectItem.getExpr();
            SQLTableSource columnTableSource = null;
            if (selectItemExpr instanceof SQLIdentifierExpr) {
                columnTableSource = ((SQLIdentifierExpr) selectItemExpr).getResolvedTableSource();
            } else if (selectItemExpr instanceof SQLPropertyExpr) {
                columnTableSource = ((SQLPropertyExpr) selectItemExpr).getResolvedTableSource();
            }

            if (columnTableSource instanceof SQLExprTableSource && ((SQLExprTableSource) columnTableSource).getExpr() instanceof SQLName) {
                SQLName tableExpr = (SQLName) ((SQLExprTableSource) columnTableSource).getExpr();
                if (tableExpr instanceof SQLIdentifierExpr) {
                    tableName = ((SQLIdentifierExpr) tableExpr).normalizedName();
                } else if (tableExpr instanceof SQLPropertyExpr) {
                    tableName = ((SQLPropertyExpr) tableExpr).normalizedName();
                }
            }
        } else {
            boolean skip = false;
            for (SQLObject parent = x.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof SQLSelectQueryBlock) {
                    SQLTableSource from = ((SQLSelectQueryBlock) parent).getFrom();

                    if (from instanceof SQLValuesTableSource) {
                        skip = true;
                        break;
                    }
                } else if (parent instanceof SQLSelectQuery) {
                    break;
                }
            }
        }

        String identName = x.getName();
        if (tableName != null) {
            orderByAddColumn(tableName, identName, x);
        } else {
            orderByAddColumn("UNKNOWN", identName, x);
        }
        return false;
    }

    private boolean visitOrderBy(SQLPropertyExpr x) {
        if (isSubQueryOrParamOrVariant(x)) {
            return false;
        }

        String owner = null;

        SQLTableSource tableSource = x.getResolvedTableSource();
        if (tableSource instanceof SQLExprTableSource) {
            SQLExpr tableSourceExpr = ((SQLExprTableSource) tableSource).getExpr();
            if (tableSourceExpr instanceof SQLName) {
                owner = tableSourceExpr.toString();
            }
        }

        if (owner == null && x.getOwner() instanceof SQLIdentifierExpr) {
            owner = ((SQLIdentifierExpr) x.getOwner()).getName();
        }

        if (owner == null) {
            return false;
        }

        orderByAddColumn(owner, x.getName(), x);

        return false;
    }

    private boolean visitOrderBy(SQLIntegerExpr x) {
        SQLObject parent = x.getParent();
        if (!(parent instanceof SQLSelectOrderByItem)) {
            return false;
        }

        if (parent.getParent() instanceof SQLSelectQueryBlock) {
            int selectItemIndex = x.getNumber().intValue() - 1;
            SQLSelectQueryBlock queryBlock = (SQLSelectQueryBlock) parent.getParent();
            final List<SQLSelectItem> selectList = queryBlock.getSelectList();

            if (selectItemIndex < 0 || selectItemIndex >= selectList.size()) {
                return false;
            }

            SQLExpr selectItemExpr = selectList.get(selectItemIndex).getExpr();
            if (selectItemExpr instanceof SQLIdentifierExpr) {
                visitOrderBy((SQLIdentifierExpr) selectItemExpr);
            } else if (selectItemExpr instanceof SQLPropertyExpr) {
                visitOrderBy((SQLPropertyExpr) selectItemExpr);
            }
        }

        return false;
    }

    private void orderByAddColumn(String table, String columnName, SQLObject expr) {
        Column column = new Column(table, columnName, dbType);

        SQLObject parent = expr.getParent();
        if (parent instanceof SQLSelectOrderByItem) {
            SQLOrderingSpecification type = ((SQLSelectOrderByItem) parent).getType();
            column.getAttributes().put("orderBy.type", type);
        }

        orderByColumns.add(column);
    }

    protected class OrderByStatVisitor extends SQLASTVisitorAdapter {
        private final SQLOrderBy orderBy;

        public OrderByStatVisitor(SQLOrderBy orderBy) {
            this.orderBy = orderBy;
            for (SQLSelectOrderByItem item : orderBy.getItems()) {
                item.getExpr().setParent(item);
            }
        }

        public SQLOrderBy getOrderBy() {
            return orderBy;
        }

        public boolean visit(SQLIdentifierExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLPropertyExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLIntegerExpr x) {
            return visitOrderBy(x);
        }
    }

    protected class MySqlOrderByStatVisitor extends MySqlASTVisitorAdapter {
        private final SQLOrderBy orderBy;

        public MySqlOrderByStatVisitor(SQLOrderBy orderBy) {
            this.orderBy = orderBy;
            for (SQLSelectOrderByItem item : orderBy.getItems()) {
                item.getExpr().setParent(item);
            }
        }

        public SQLOrderBy getOrderBy() {
            return orderBy;
        }

        public boolean visit(SQLIdentifierExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLPropertyExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLIntegerExpr x) {
            return visitOrderBy(x);
        }
    }

    protected class PGOrderByStatVisitor extends PGASTVisitorAdapter {
        private final SQLOrderBy orderBy;

        public PGOrderByStatVisitor(SQLOrderBy orderBy) {
            this.orderBy = orderBy;
            for (SQLSelectOrderByItem item : orderBy.getItems()) {
                item.getExpr().setParent(item);
            }
        }

        public SQLOrderBy getOrderBy() {
            return orderBy;
        }

        public boolean visit(SQLIdentifierExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLPropertyExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLIntegerExpr x) {
            return visitOrderBy(x);
        }
    }

    protected class OracleOrderByStatVisitor extends OracleASTVisitorAdapter {
        private final SQLOrderBy orderBy;

        public OracleOrderByStatVisitor(SQLOrderBy orderBy) {
            this.orderBy = orderBy;
            for (SQLSelectOrderByItem item : orderBy.getItems()) {
                item.getExpr().setParent(item);
            }
        }

        public SQLOrderBy getOrderBy() {
            return orderBy;
        }

        public boolean visit(SQLIdentifierExpr x) {
            return visitOrderBy(x);
        }

        public boolean visit(SQLPropertyExpr x) {
            SQLExpr unwrapped = unwrapExpr(x);
            if (unwrapped instanceof SQLPropertyExpr) {
                visitOrderBy((SQLPropertyExpr) unwrapped);
            } else if (unwrapped instanceof SQLIdentifierExpr) {
                visitOrderBy((SQLIdentifierExpr) unwrapped);
            }
            return false;
        }

        public boolean visit(SQLIntegerExpr x) {
            return visitOrderBy(x);
        }
    }

    public boolean visit(SQLOrderBy x) {
        final SQLASTVisitor orderByVisitor = createOrderByVisitor(x);

        SQLSelectQueryBlock query = null;
        if (x.getParent() instanceof SQLSelectQueryBlock) {
            query = (SQLSelectQueryBlock) x.getParent();
        }
        if (query != null) {
            for (SQLSelectOrderByItem item : x.getItems()) {
                SQLExpr expr = item.getExpr();
                if (expr instanceof SQLIntegerExpr) {
                    int intValue = ((SQLIntegerExpr) expr).getNumber().intValue() - 1;
                    if (intValue < query.getSelectList().size()) {
                        SQLExpr selectItemExpr = query.getSelectList().get(intValue).getExpr();
                        selectItemExpr.accept(orderByVisitor);
                    }
                } else if (expr instanceof MySqlExpr || expr instanceof OracleExpr) {
                    continue;
                }
            }
        }
        x.accept(orderByVisitor);

        for (SQLSelectOrderByItem orderByItem : x.getItems()) {
            statExpr(
                    orderByItem.getExpr());
        }

        return false;
    }

    public boolean visit(SQLOver x) {
        SQLName of = x.getOf();
        SQLOrderBy orderBy = x.getOrderBy();
        List<SQLExpr> partitionBy = x.getPartitionBy();

        if (of == null // skip if of is not null
                && orderBy != null) {
            orderBy.accept(this);
        }

        if (partitionBy != null) {
            for (SQLExpr expr : partitionBy) {
                expr.accept(this);
            }
        }

        return false;
    }

    public boolean visit(SQLWindow x) {
        SQLOver over = x.getOver();
        if (over != null) {
            visit(over);
        }
        return false;
    }

    protected SQLASTVisitor createOrderByVisitor(SQLOrderBy x) {
        if (dbType == null) {
            dbType = DbType.other;
        }

        final SQLASTVisitor orderByVisitor;
        switch (dbType) {
            case mysql:
            case mariadb:
            case tidb:
                return new MySqlOrderByStatVisitor(x);
            case postgresql:
                return new PGOrderByStatVisitor(x);
            case oracle:
                return new OracleOrderByStatVisitor(x);
            default:
                return new OrderByStatVisitor(x);
        }
    }

    public Set<Relationship> getRelationships() {
        return relationships;
    }

    public List<Column> getOrderByColumns() {
        return orderByColumns;
    }

    public Set<Column> getGroupByColumns() {
        return groupByColumns;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public List<SQLAggregateExpr> getAggregateFunctions() {
        return aggregateFunctions;
    }

    public boolean visit(SQLBetweenExpr x) {
        SQLObject parent = x.getParent();

        SQLExpr test = x.getTestExpr();
        SQLExpr begin = x.getBeginExpr();
        SQLExpr end = x.getEndExpr();

        statExpr(test);
        statExpr(begin);
        statExpr(end);

        handleCondition(test, "BETWEEN", begin, end);

        return false;
    }

    public boolean visit(SQLBinaryOpExpr x) {
        SQLObject parent = x.getParent();

        if (parent instanceof SQLIfStatement) {
            return true;
        }

        final SQLBinaryOperator op = x.getOperator();
        final SQLExpr left = x.getLeft();
        final SQLExpr right = x.getRight();

        if ((op == SQLBinaryOperator.BooleanAnd || op == SQLBinaryOperator.BooleanOr)
                && left instanceof SQLBinaryOpExpr
                && ((SQLBinaryOpExpr) left).getOperator() == op) {
            List<SQLExpr> groupList = SQLBinaryOpExpr.split(x, op);
            for (int i = 0; i < groupList.size(); i++) {
                SQLExpr item = groupList.get(i);
                item.accept(this);
            }
            return false;
        }

        switch (op) {
            case Equality:
            case NotEqual:
            case GreaterThan:
            case GreaterThanOrEqual:
            case LessThan:
            case LessThanOrGreater:
            case LessThanOrEqual:
            case LessThanOrEqualOrGreaterThan:
            case SoudsLike:
            case Like:
            case NotLike:
            case Is:
            case IsNot:
                handleCondition(left, op.name, right);

                String reverseOp = op.name;
                switch (op) {
                    case LessThan:
                        reverseOp = SQLBinaryOperator.GreaterThan.name;
                        break;
                    case LessThanOrEqual:
                        reverseOp = SQLBinaryOperator.GreaterThanOrEqual.name;
                        break;
                    case GreaterThan:
                        reverseOp = SQLBinaryOperator.LessThan.name;
                        break;
                    case GreaterThanOrEqual:
                        reverseOp = SQLBinaryOperator.LessThanOrEqual.name;
                        break;
                    default:
                        break;
                }
                handleCondition(right, reverseOp, left);

                handleRelationship(left, op.name, right);
                break;
            case BooleanOr: {
                List<SQLExpr> list = SQLBinaryOpExpr.split(x, op);

                for (SQLExpr item : list) {
                    if (item instanceof SQLBinaryOpExpr) {
                        visit((SQLBinaryOpExpr) item);
                    } else {
                        item.accept(this);
                    }
                }

                return false;
            }
            case Modulus:
                if (right instanceof SQLIdentifierExpr) {
                    long hashCode64 = ((SQLIdentifierExpr) right).hashCode64();
                    if (hashCode64 == FnvHash.Constants.ISOPEN) {
                        left.accept(this);
                        return false;
                    }
                }
                break;
            default:
                break;
        }

        if (left instanceof SQLBinaryOpExpr) {
            visit((SQLBinaryOpExpr) left);
        } else {
            statExpr(left);
        }
        statExpr(right);

        return false;
    }

    protected void handleRelationship(SQLExpr left, String operator, SQLExpr right) {
        Column leftColumn = getColumn(left);
        if (leftColumn == null) {
            return;
        }

        Column rightColumn = getColumn(right);
        if (rightColumn == null) {
            return;
        }

        Relationship relationship = new Relationship(leftColumn, rightColumn, operator);
        this.relationships.add(relationship);
    }

    protected void handleCondition(SQLExpr expr, String operator, List<SQLExpr> values) {
        handleCondition(expr, operator, values.toArray(new SQLExpr[values.size()]));
    }

    protected void handleCondition(SQLExpr expr, String operator, SQLExpr... valueExprs) {
        if (expr instanceof SQLCastExpr) {
            expr = ((SQLCastExpr) expr).getExpr();
        } else if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr func = (SQLMethodInvokeExpr) expr;
            List<SQLExpr> arguments = func.getArguments();
            if (func.methodNameHashCode64() == FnvHash.Constants.COALESCE
                    && arguments.size() > 0) {
                boolean allLiteral = true;
                for (int i = 1; i < arguments.size(); ++i) {
                    SQLExpr arg = arguments.get(i);
                    if (!(arg instanceof SQLLiteralExpr)) {
                        allLiteral = false;
                    }
                }
                if (allLiteral) {
                    expr = arguments.get(0);
                }
            }
        }

        Column column = getColumn(expr);

        if (column == null
                && expr instanceof SQLBinaryOpExpr
                && valueExprs.length == 1 && valueExprs[0] instanceof SQLLiteralExpr) {
            SQLBinaryOpExpr left = (SQLBinaryOpExpr) expr;
            SQLLiteralExpr right = (SQLLiteralExpr) valueExprs[0];

            if (left.getRight() instanceof SQLIntegerExpr && right instanceof SQLIntegerExpr) {
                long v0 = ((SQLIntegerExpr) left.getRight()).getNumber().longValue();
                long v1 = ((SQLIntegerExpr) right).getNumber().longValue();

                SQLBinaryOperator op = left.getOperator();

                long v;
                switch (op) {
                    case Add:
                        v = v1 - v0;
                        break;
                    case Subtract:
                        v = v1 + v0;
                        break;
                    default:
                        return;
                }

                handleCondition(
                        left.getLeft(), operator, new SQLIntegerExpr(v));
                return;
            }
        }

        if (column == null) {
            return;
        }

        Condition condition = null;
        for (Condition item : this.getConditions()) {
            if (item.getColumn().equals(column) && item.getOperator().equals(operator)) {
                condition = item;
                break;
            }
        }

        if (condition == null) {
            condition = new Condition(column, operator);
            this.conditions.add(condition);
        }

        for (SQLExpr item : valueExprs) {
            Column valueColumn = getColumn(item);
            if (valueColumn != null) {
                continue;
            }

            Object value;

            if (item instanceof SQLCastExpr) {
                item = ((SQLCastExpr) item).getExpr();
            }

            if (item instanceof SQLMethodInvokeExpr
                    || item instanceof SQLCurrentTimeExpr) {
                value = item.toString();
            } else {
                value = SQLEvalVisitorUtils.eval(dbType, item, parameters, false);
                if (value == SQLEvalVisitor.EVAL_VALUE_NULL) {
                    value = null;
                }
            }

            condition.addValue(value);
        }
    }

    public DbType getDbType() {
        return dbType;
    }

    protected Column getColumn(SQLExpr expr) {
        final SQLExpr original = expr;

        // unwrap
        expr = unwrapExpr(expr);

        if (expr instanceof SQLPropertyExpr) {
            SQLPropertyExpr propertyExpr = (SQLPropertyExpr) expr;

            SQLExpr owner = propertyExpr.getOwner();
            String column = SQLUtils.normalize(propertyExpr.getName());

            if (owner instanceof SQLName) {
                SQLName table = (SQLName) owner;

                SQLObject resolvedOwnerObject = propertyExpr.getResolvedOwnerObject();
                if (resolvedOwnerObject instanceof SQLSubqueryTableSource
                        || resolvedOwnerObject instanceof SQLCreateProcedureStatement
                        || resolvedOwnerObject instanceof SQLCreateFunctionStatement) {
                    table = null;
                }

                if (resolvedOwnerObject instanceof SQLExprTableSource) {
                    SQLExpr tableSourceExpr = ((SQLExprTableSource) resolvedOwnerObject).getExpr();
                    if (tableSourceExpr instanceof SQLName) {
                        table = (SQLName) tableSourceExpr;
                    }
                } else if (resolvedOwnerObject instanceof SQLValuesTableSource) {
                    return null;
                }

                if (table != null) {
                    String tableName;
                    if (table instanceof SQLIdentifierExpr) {
                        tableName = ((SQLIdentifierExpr) table).normalizedName();
                    } else if (table instanceof SQLPropertyExpr) {
                        tableName = ((SQLPropertyExpr) table).normalizedName();
                    } else {
                        tableName = table.toString();
                    }

                    long tableHashCode64 = table.hashCode64();

                    if (resolvedOwnerObject instanceof SQLExprTableSource) {
                        SchemaObject schemaObject = ((SQLExprTableSource) resolvedOwnerObject).getSchemaObject();
                        if (schemaObject != null && schemaObject.getStatement() instanceof SQLCreateTableStatement) {
                            SQLColumnDefinition columnDef = schemaObject.findColumn(propertyExpr.nameHashCode64());
                            if (columnDef == null) {
                                tableName = "UNKNOWN";
                                tableHashCode64 = FnvHash.Constants.UNKNOWN;
                            }
                        }
                    }

                    long basic = tableHashCode64;
                    basic ^= '.';
                    basic *= FnvHash.PRIME;
                    long columnHashCode64 = FnvHash.hashCode64(basic, column);

                    Column columnObj = this.columns.get(columnHashCode64);
                    if (columnObj == null) {
                        columnObj = new Column(tableName, column, columnHashCode64);
                        if (!(resolvedOwnerObject instanceof SQLSubqueryTableSource
                                || resolvedOwnerObject instanceof SQLWithSubqueryClause.Entry)) {
                            this.columns.put(columnHashCode64, columnObj);
                        }
                    }

                    return columnObj;
                }
            }

            return null;
        }

        if (expr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;
            if (identifierExpr.getResolvedParameter() != null) {
                return null;
            }

            if (identifierExpr.getResolvedTableSource() instanceof SQLSubqueryTableSource) {
                return null;
            }

            if (identifierExpr.getResolvedDeclareItem() != null || identifierExpr.getResolvedParameter() != null) {
                return null;
            }

            String column = identifierExpr.getName();

            SQLName table = null;
            SQLTableSource tableSource = identifierExpr.getResolvedTableSource();
            if (tableSource instanceof SQLExprTableSource) {
                SQLExpr tableSourceExpr = ((SQLExprTableSource) tableSource).getExpr();

                if (tableSourceExpr != null && !(tableSourceExpr instanceof SQLName)) {
                    tableSourceExpr = unwrapExpr(tableSourceExpr);
                }

                if (tableSourceExpr instanceof SQLName) {
                    table = (SQLName) tableSourceExpr;
                }
            }

            if (table != null) {
                long tableHashCode64 = table.hashCode64();
                long basic = tableHashCode64;
                basic ^= '.';
                basic *= FnvHash.PRIME;
                long columnHashCode64 = FnvHash.hashCode64(basic, column);

                final Column old = columns.get(columnHashCode64);
                if (old != null) {
                    return old;
                }

                return new Column(table.toString(), column, columnHashCode64);
            }

            return new Column("UNKNOWN", column);
        }

        if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
            List<SQLExpr> arguments = methodInvokeExpr.getArguments();
            long nameHash = methodInvokeExpr.methodNameHashCode64();
            if (nameHash == FnvHash.Constants.DATE_FORMAT) {
                if (arguments.size() == 2
                        && arguments.get(0) instanceof SQLName
                        && arguments.get(1) instanceof SQLCharExpr) {
                    return getColumn(arguments.get(0));
                }
            }
        }

        return null;
    }

    private SQLExpr unwrapExpr(SQLExpr expr) {
        SQLExpr original = expr;

        for (int i = 0; ; i++) {
            if (i > 1000) {
                return null;
            }

            if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr methodInvokeExp = (SQLMethodInvokeExpr) expr;
                if (methodInvokeExp.getArguments().size() == 1) {
                    SQLExpr firstExpr = methodInvokeExp.getArguments().get(0);
                    expr = firstExpr;
                    continue;
                }
            }

            if (expr instanceof SQLCastExpr) {
                expr = ((SQLCastExpr) expr).getExpr();
                continue;
            }

            if (expr instanceof SQLPropertyExpr) {
                SQLPropertyExpr propertyExpr = (SQLPropertyExpr) expr;

                SQLTableSource resolvedTableSource = propertyExpr.getResolvedTableSource();
                if (resolvedTableSource instanceof SQLSubqueryTableSource || resolvedTableSource instanceof SQLWithSubqueryClause.Entry) {
                    SQLSelect select;
                    if (resolvedTableSource instanceof SQLSubqueryTableSource) {
                        select = ((SQLSubqueryTableSource) resolvedTableSource).getSelect();
                    } else {
                        select = ((SQLWithSubqueryClause.Entry) resolvedTableSource).getSubQuery();
                    }

                    SQLSelectQueryBlock queryBlock = select.getFirstQueryBlock();
                    if (queryBlock != null) {
                        if (queryBlock.getGroupBy() != null) {
                            if (original.getParent() instanceof SQLBinaryOpExpr) {
                                SQLExpr other = ((SQLBinaryOpExpr) original.getParent()).other(original);
                                if (!SQLExprUtils.isLiteralExpr(other)) {
                                    break;
                                }
                            }
                        }

                        SQLSelectItem selectItem = queryBlock.findSelectItem(propertyExpr
                                .nameHashCode64());
                        if (selectItem != null) {
                            SQLExpr selectItemExpr = selectItem.getExpr();
                            if (selectItemExpr instanceof SQLMethodInvokeExpr
                                    && ((SQLMethodInvokeExpr) selectItemExpr).getArguments().size() == 1
                            ) {
                                selectItemExpr = ((SQLMethodInvokeExpr) selectItemExpr).getArguments().get(0);
                            }
                            if (selectItemExpr != expr) {
                                expr = selectItemExpr;
                                continue;
                            }
                        } else if (queryBlock.selectItemHasAllColumn()) {
                            SQLTableSource allColumnTableSource = null;

                            SQLTableSource from = queryBlock.getFrom();
                            if (from instanceof SQLJoinTableSource) {
                                SQLSelectItem allColumnSelectItem = queryBlock.findAllColumnSelectItem();
                                if (allColumnSelectItem != null && allColumnSelectItem.getExpr() instanceof SQLPropertyExpr) {
                                    SQLExpr owner = ((SQLPropertyExpr) allColumnSelectItem.getExpr()).getOwner();
                                    if (owner instanceof SQLName) {
                                        allColumnTableSource = from.findTableSource(((SQLName) owner).nameHashCode64());
                                    }
                                }
                            } else {
                                allColumnTableSource = from;
                            }

                            if (allColumnTableSource == null) {
                                break;
                            }

                            propertyExpr = propertyExpr.clone();
                            propertyExpr.setResolvedTableSource(allColumnTableSource);

                            if (allColumnTableSource instanceof SQLExprTableSource) {
                                propertyExpr.setOwner(((SQLExprTableSource) allColumnTableSource).getExpr().clone());
                            }
                            expr = propertyExpr;
                            continue;
                        }
                    }
                } else if (resolvedTableSource instanceof SQLExprTableSource) {
                    SQLExprTableSource exprTableSource = (SQLExprTableSource) resolvedTableSource;
                    if (exprTableSource.getSchemaObject() != null) {
                        break;
                    }

                    SQLTableSource redirectTableSource = null;
                    SQLExpr tableSourceExpr = exprTableSource.getExpr();
                    if (tableSourceExpr instanceof SQLIdentifierExpr) {
                        redirectTableSource = ((SQLIdentifierExpr) tableSourceExpr).getResolvedTableSource();
                    } else if (tableSourceExpr instanceof SQLPropertyExpr) {
                        redirectTableSource = ((SQLPropertyExpr) tableSourceExpr).getResolvedTableSource();
                    }

                    if (redirectTableSource == resolvedTableSource) {
                        redirectTableSource = null;
                    }

                    if (redirectTableSource != null) {
                        propertyExpr = propertyExpr.clone();
                        if (redirectTableSource instanceof SQLExprTableSource) {
                            propertyExpr.setOwner(((SQLExprTableSource) redirectTableSource).getExpr().clone());
                        }
                        propertyExpr.setResolvedTableSource(redirectTableSource);
                        expr = propertyExpr;
                        continue;
                    }

                    propertyExpr = propertyExpr.clone();
                    propertyExpr.setOwner(tableSourceExpr);
                    expr = propertyExpr;
                    break;
                }
            }
            break;
        }

        return expr;
    }

    @Override
    public boolean visit(SQLTruncateStatement x) {
        setMode(x, Mode.Delete);

        for (SQLExprTableSource tableSource : x.getTableSources()) {
            SQLName name = (SQLName) tableSource.getExpr();
            TableStat stat = getTableStat(name);
            stat.incrementDeleteCount();
        }

        return false;
    }

    @Override
    public boolean visit(SQLDropViewStatement x) {
        setMode(x, Mode.Drop);
        return true;
    }

    @Override
    public boolean visit(SQLDropTableStatement x) {
        setMode(x, Mode.Insert);

        for (SQLExprTableSource tableSource : x.getTableSources()) {
            SQLName name = (SQLName) tableSource.getExpr();
            TableStat stat = getTableStat(name);
            stat.incrementDropCount();
        }

        return false;
    }

    @Override
    public boolean visit(SQLInsertStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        setMode(x, Mode.Insert);

        if (x.getTableName() instanceof SQLName) {
            String ident = ((SQLName) x.getTableName()).toString();

            TableStat stat = getTableStat(x.getTableName());
            stat.incrementInsertCount();
        }

        accept(x.getColumns());
        accept(x.getQuery());

        return false;
    }

    protected static void putAliasMap(Map<String, String> aliasMap, String name, String value) {
        if (aliasMap == null || name == null) {
            return;
        }
        aliasMap.put(name.toLowerCase(), value);
    }

    protected void accept(SQLObject x) {
        if (x != null) {
            x.accept(this);
        }
    }

    protected void accept(List<? extends SQLObject> nodes) {
        for (int i = 0, size = nodes.size(); i < size; ++i) {
            accept(nodes.get(i));
        }
    }

    public boolean visit(SQLSelectQueryBlock x) {
        SQLTableSource from = x.getFrom();

        setMode(x, Mode.Select);

        boolean isHiveMultiInsert = false;
        if (from == null) {
            isHiveMultiInsert = x.getParent() != null
                    && x.getParent().getParent() instanceof HiveInsert
                    && x.getParent().getParent().getParent() instanceof HiveMultiInsertStatement;
            if (isHiveMultiInsert) {
                from = ((HiveMultiInsertStatement) x.getParent().getParent().getParent()).getFrom();
            }
        }

        if (from == null) {
            for (SQLSelectItem selectItem : x.getSelectList()) {
                statExpr(
                        selectItem.getExpr());
            }
            return false;
        }

//        if (x.getFrom() instanceof SQLSubqueryTableSource) {
//            x.getFrom().accept(this);
//            return false;
//        }

        if (from != null) {
            from.accept(this); // 提前执行，获得aliasMap
        }

        SQLExprTableSource into = x.getInto();
        if (into != null && into.getExpr() instanceof SQLName) {
            SQLName intoExpr = (SQLName) into.getExpr();

            boolean isParam = intoExpr instanceof SQLIdentifierExpr && isParam((SQLIdentifierExpr) intoExpr);

            if (!isParam) {
                TableStat stat = getTableStat(intoExpr);
                if (stat != null) {
                    stat.incrementInsertCount();
                }
            }
            into.accept(this);
        }

        for (SQLSelectItem selectItem : x.getSelectList()) {
            if (selectItem.getClass() == SQLSelectItem.class) {
                statExpr(
                        selectItem.getExpr());
            } else {
                selectItem.accept(this);
            }
        }

        SQLExpr where = x.getWhere();
        if (where != null) {
            statExpr(where);
        }

        SQLExpr startWith = x.getStartWith();
        if (startWith != null) {
            statExpr(startWith);
        }

        SQLExpr connectBy = x.getConnectBy();
        if (connectBy != null) {
            statExpr(connectBy);
        }

        SQLSelectGroupByClause groupBy = x.getGroupBy();
        if (groupBy != null) {
            for (SQLExpr expr : groupBy.getItems()) {
                statExpr(expr);
            }
            if (groupBy.getHaving() != null) {
                statExpr(groupBy.getHaving());
            }
        }

        List<SQLWindow> windows = x.getWindows();
        if (windows != null && windows.size() > 0) {
            for (SQLWindow window : windows) {
                window.accept(this);
            }
        }

        SQLOrderBy orderBy = x.getOrderBy();
        if (orderBy != null) {
            this.visit(orderBy);
        }

        SQLExpr first = x.getFirst();
        if (first != null) {
            statExpr(first);
        }

        List<SQLSelectOrderByItem> distributeBy = x.getDistributeBy();
        if (distributeBy != null) {
            for (SQLSelectOrderByItem item : distributeBy) {
                statExpr(item.getExpr());
            }
        }

        List<SQLSelectOrderByItem> sortBy = x.getSortBy();
        if (sortBy != null) {
            for (SQLSelectOrderByItem orderByItem : sortBy) {
                statExpr(orderByItem.getExpr());
            }
        }

        for (SQLExpr expr : x.getForUpdateOf()) {
            statExpr(expr);
        }

        return false;
    }

    private static boolean isParam(SQLIdentifierExpr x) {
        if (x.getResolvedParameter() != null
                || x.getResolvedDeclareItem() != null) {
            return true;
        }
        return false;
    }

    public void endVisit(SQLSelectQueryBlock x) {
        setModeOrigin(x);
    }

    public boolean visit(SQLJoinTableSource x) {
        SQLTableSource left = x.getLeft(), right = x.getRight();

        left.accept(this);
        right.accept(this);

        SQLExpr condition = x.getCondition();
        if (condition != null) {
            condition.accept(this);
        }

        if (x.getUsing().size() > 0
                && left instanceof SQLExprTableSource && right instanceof SQLExprTableSource) {
            SQLExpr leftExpr = ((SQLExprTableSource) left).getExpr();
            SQLExpr rightExpr = ((SQLExprTableSource) right).getExpr();

            for (SQLExpr expr : x.getUsing()) {
                if (expr instanceof SQLIdentifierExpr) {
                    String name = ((SQLIdentifierExpr) expr).getName();
                    SQLPropertyExpr leftPropExpr = new SQLPropertyExpr(leftExpr, name);
                    SQLPropertyExpr rightPropExpr = new SQLPropertyExpr(rightExpr, name);

                    leftPropExpr.setResolvedTableSource(left);
                    rightPropExpr.setResolvedTableSource(right);

                    SQLBinaryOpExpr usingCondition = new SQLBinaryOpExpr(leftPropExpr, SQLBinaryOperator.Equality, rightPropExpr);
                    usingCondition.accept(this);
                }
            }
        }

        return false;
    }

    public boolean visit(SQLPropertyExpr x) {
        Column column = null;
        String ident = SQLUtils.normalize(x.getName());

        SQLTableSource tableSource = x.getResolvedTableSource();

        if (tableSource instanceof SQLSubqueryTableSource) {
            SQLSelect subSelect = ((SQLSubqueryTableSource) tableSource).getSelect();
            SQLSelectQueryBlock subQuery = subSelect.getQueryBlock();
            if (subQuery != null) {
                SQLTableSource subTableSource = subQuery.findTableSourceWithColumn(x.nameHashCode64());
                if (subTableSource != null) {
                    tableSource = subTableSource;
                }
            }
        }

        if (tableSource instanceof SQLExprTableSource) {
            SQLExpr expr = ((SQLExprTableSource) tableSource).getExpr();

            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr table = (SQLIdentifierExpr) expr;
                SQLTableSource resolvedTableSource = table.getResolvedTableSource();
                if (resolvedTableSource instanceof SQLExprTableSource) {
                    expr = ((SQLExprTableSource) resolvedTableSource).getExpr();
                }
            } else if (expr instanceof SQLPropertyExpr) {
                SQLPropertyExpr table = (SQLPropertyExpr) expr;
                SQLTableSource resolvedTableSource = table.getResolvedTableSource();
                if (resolvedTableSource instanceof SQLExprTableSource) {
                    expr = ((SQLExprTableSource) resolvedTableSource).getExpr();
                }
            }

            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr table = (SQLIdentifierExpr) expr;

                SQLTableSource resolvedTableSource = table.getResolvedTableSource();
                if (resolvedTableSource instanceof SQLWithSubqueryClause.Entry) {
                    return false;
                }

                String tableName = table.getName();
                SchemaObject schemaObject = ((SQLExprTableSource) tableSource).getSchemaObject();
                if (schemaObject != null
                        && schemaObject.getStatement() instanceof SQLCreateTableStatement
                        && !"*".equals(ident)) {
                    SQLColumnDefinition columnDef = schemaObject.findColumn(x.nameHashCode64());
                    if (columnDef == null) {
                        column = addColumn("UNKNOWN", ident);
                    }
                }

                if (column == null) {
                    column = addColumn(table, ident);
                }

                if (isParentGroupBy(x)) {
                    this.groupByColumns.add(column);
                }
            } else if (expr instanceof SQLPropertyExpr) {
                SQLPropertyExpr table = (SQLPropertyExpr) expr;
                column = addColumn(table, ident);

                if (column != null && isParentGroupBy(x)) {
                    this.groupByColumns.add(column);
                }
            } else if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
                if ("table".equalsIgnoreCase(methodInvokeExpr.getMethodName())
                        && methodInvokeExpr.getArguments().size() == 1
                        && methodInvokeExpr.getArguments().get(0) instanceof SQLName) {
                    SQLName table = (SQLName) methodInvokeExpr.getArguments().get(0);

                    String tableName = null;
                    if (table instanceof SQLPropertyExpr) {
                        SQLPropertyExpr propertyExpr = (SQLPropertyExpr) table;
                        SQLIdentifierExpr owner = (SQLIdentifierExpr) propertyExpr.getOwner();
                        if (propertyExpr.getResolvedTableSource() != null
                                && propertyExpr.getResolvedTableSource() instanceof SQLExprTableSource) {
                            SQLExpr resolveExpr = ((SQLExprTableSource) propertyExpr.getResolvedTableSource()).getExpr();
                            if (resolveExpr instanceof SQLName) {
                                tableName = resolveExpr.toString() + "." + propertyExpr.getName();
                            }
                        }
                    }

                    if (tableName == null) {
                        tableName = table.toString();
                    }

                    column = addColumn(tableName, ident);
                }
            }
        } else if (tableSource instanceof SQLWithSubqueryClause.Entry
                || tableSource instanceof SQLSubqueryTableSource
                || tableSource instanceof SQLUnionQueryTableSource
                || tableSource instanceof SQLValuesTableSource
                || tableSource instanceof SQLLateralViewTableSource) {
            return false;
        } else {
            if (x.getResolvedProcudure() != null) {
                return false;
            }

            if (x.getResolvedOwnerObject() instanceof SQLParameter) {
                return false;
            }

            boolean skip = false;
            for (SQLObject parent = x.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof SQLSelectQueryBlock) {
                    SQLTableSource from = ((SQLSelectQueryBlock) parent).getFrom();

                    if (from instanceof SQLValuesTableSource) {
                        skip = true;
                        break;
                    }
                } else if (parent instanceof SQLSelectQuery) {
                    break;
                }
            }
            if (!skip) {
                column = handleUnknownColumn(ident);
            }
        }

        if (column != null) {
            SQLObject parent = x.getParent();
            if (parent instanceof SQLSelectOrderByItem) {
                parent = parent.getParent();
                if (parent instanceof SQLIndexDefinition) {
                    parent = parent.getParent();
                }
            }
            if (parent instanceof SQLPrimaryKey) {
                column.setPrimaryKey(true);
            } else if (parent instanceof SQLUnique) {
                column.setUnique(true);
            }

            setColumn(x, column);
        }

        return false;
    }

    protected boolean isPseudoColumn(long hash) {
        return false;
    }

    public boolean visit(SQLIdentifierExpr x) {
        if (isParam(x)) {
            return false;
        }

        SQLTableSource tableSource = x.getResolvedTableSource();
        if (x.getParent() instanceof SQLSelectOrderByItem) {
            SQLSelectOrderByItem selectOrderByItem = (SQLSelectOrderByItem) x.getParent();
            if (selectOrderByItem.getResolvedSelectItem() != null) {
                return false;
            }
        }

        if (tableSource == null
                && (x.getResolvedParameter() != null
                || x.getResolvedDeclareItem() != null)) {
            return false;
        }

        long hash = x.nameHashCode64();
        if (isPseudoColumn(hash)) {
            return false;
        }

        if ((hash == FnvHash.Constants.LEVEL
                || hash == FnvHash.Constants.CONNECT_BY_ISCYCLE
                || hash == FnvHash.Constants.ROWNUM)
                && x.getResolvedColumn() == null
                && tableSource == null) {
            return false;
        }

        Column column = null;
        String ident = x.normalizedName();

        if (tableSource instanceof SQLExprTableSource) {
            SQLExpr expr = ((SQLExprTableSource) tableSource).getExpr();

            if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr func = (SQLMethodInvokeExpr) expr;
                if (func.methodNameHashCode64() == FnvHash.Constants.ANN) {
                    expr = func.getArguments().get(0);
                }
            }

            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr table = (SQLIdentifierExpr) expr;
                column = addColumn(table, ident);

                if (column != null && isParentGroupBy(x)) {
                    this.groupByColumns.add(column);
                }
            } else if (expr instanceof SQLPropertyExpr || expr instanceof SQLDbLinkExpr) {
                String tableName;
                if (expr instanceof SQLPropertyExpr) {
                    tableName = ((SQLPropertyExpr) expr).normalizedName();
                } else {
                    tableName = expr.toString();
                }

                column = addColumn(tableName, ident);

                if (column != null && isParentGroupBy(x)) {
                    this.groupByColumns.add(column);
                }
            } else if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
                if ("table".equalsIgnoreCase(methodInvokeExpr.getMethodName())
                        && methodInvokeExpr.getArguments().size() == 1
                        && methodInvokeExpr.getArguments().get(0) instanceof SQLName) {
                    SQLName table = (SQLName) methodInvokeExpr.getArguments().get(0);

                    String tableName = null;
                    if (table instanceof SQLPropertyExpr) {
                        SQLPropertyExpr propertyExpr = (SQLPropertyExpr) table;
                        SQLIdentifierExpr owner = (SQLIdentifierExpr) propertyExpr.getOwner();
                        if (propertyExpr.getResolvedTableSource() != null
                                && propertyExpr.getResolvedTableSource() instanceof SQLExprTableSource) {
                            SQLExpr resolveExpr = ((SQLExprTableSource) propertyExpr.getResolvedTableSource()).getExpr();
                            if (resolveExpr instanceof SQLName) {
                                tableName = resolveExpr.toString() + "." + propertyExpr.getName();
                            }
                        }
                    }

                    if (tableName == null) {
                        tableName = table.toString();
                    }

                    column = addColumn(tableName, ident);
                }
            }
        } else if (tableSource instanceof SQLWithSubqueryClause.Entry
                || tableSource instanceof SQLSubqueryTableSource
                || tableSource instanceof SQLLateralViewTableSource) {
            return false;
        } else {
            boolean skip = false;
            for (SQLObject parent = x.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof SQLSelectQueryBlock) {
                    SQLTableSource from = ((SQLSelectQueryBlock) parent).getFrom();

                    if (from instanceof SQLValuesTableSource) {
                        skip = true;
                        break;
                    }
                } else if (parent instanceof SQLSelectQuery) {
                    break;
                }
            }
            if (x.getParent() instanceof SQLMethodInvokeExpr
                    && ((SQLMethodInvokeExpr) x.getParent()).methodNameHashCode64() == FnvHash.Constants.ANN) {
                skip = true;
            }

            if (!skip) {
                column = handleUnknownColumn(ident);
            }
        }

        if (column != null) {
            SQLObject parent = x.getParent();
            if (parent instanceof SQLSelectOrderByItem) {
                parent = parent.getParent();
                if (parent instanceof SQLIndexDefinition) {
                    parent = parent.getParent();
                }
            }
            if (parent instanceof SQLPrimaryKey) {
                column.setPrimaryKey(true);
            } else if (parent instanceof SQLUnique) {
                column.setUnique(true);
            }

            setColumn(x, column);
        }

        return false;
    }

    private boolean isParentSelectItem(SQLObject parent) {
        for (int i = 0; parent != null; parent = parent.getParent(), ++i) {
            if (i > 100) {
                break;
            }

            if (parent instanceof SQLSelectItem) {
                return true;
            }

            if (parent instanceof SQLSelectQueryBlock) {
                return false;
            }
        }
        return false;
    }

    private boolean isParentGroupBy(SQLObject parent) {
        for (; parent != null; parent = parent.getParent()) {
            if (parent instanceof SQLSelectItem) {
                return false;
            }

            if (parent instanceof SQLSelectGroupByClause) {
                return true;
            }
        }
        return false;
    }

    private void setColumn(SQLExpr x, Column column) {
        SQLObject current = x;
        for (int i = 0; i < 100; ++i) {
            SQLObject parent = current.getParent();

            if (parent == null) {
                break;
            }

            if (parent instanceof SQLSelectQueryBlock) {
                SQLSelectQueryBlock query = (SQLSelectQueryBlock) parent;
                if (query.getWhere() == current) {
                    column.setWhere(true);
                }
                break;
            }

            if (parent instanceof SQLSelectGroupByClause) {
                SQLSelectGroupByClause groupBy = (SQLSelectGroupByClause) parent;
                if (current == groupBy.getHaving()) {
                    column.setHaving(true);
                } else if (groupBy.getItems().contains(current)) {
                    column.setGroupBy(true);
                }
                break;
            }

            if (isParentSelectItem(parent)) {
                column.setSelec(true);
                break;
            }

            if (parent instanceof SQLJoinTableSource) {
                SQLJoinTableSource join = (SQLJoinTableSource) parent;
                if (join.getCondition() == current) {
                    column.setJoin(true);
                }
                break;
            }

            current = parent;
        }
    }

    protected Column handleUnknownColumn(String columnName) {
        return addColumn("UNKNOWN", columnName);
    }

    public boolean visit(SQLAllColumnExpr x) {
        SQLTableSource tableSource = x.getResolvedTableSource();
        if (tableSource == null) {
            return false;
        }

        statAllColumn(x, tableSource);

        return false;
    }

    private void statAllColumn(SQLAllColumnExpr x, SQLTableSource tableSource) {
        if (tableSource instanceof SQLExprTableSource) {
            statAllColumn(x, (SQLExprTableSource) tableSource);
            return;
        }

        if (tableSource instanceof SQLJoinTableSource) {
            SQLJoinTableSource join = (SQLJoinTableSource) tableSource;
            statAllColumn(x, join.getLeft());
            statAllColumn(x, join.getRight());
        }
    }

    private void statAllColumn(SQLAllColumnExpr x, SQLExprTableSource tableSource) {
        SQLExprTableSource exprTableSource = tableSource;
        SQLName expr = exprTableSource.getName();

        SQLCreateTableStatement createStmt = null;

        SchemaObject tableObject = exprTableSource.getSchemaObject();
        if (tableObject != null) {
            SQLStatement stmt = tableObject.getStatement();
            if (stmt instanceof SQLCreateTableStatement) {
                createStmt = (SQLCreateTableStatement) stmt;
            }
        }

        if (createStmt != null
                && createStmt.getTableElementList().size() > 0) {
            SQLName tableName = createStmt.getName();
            for (SQLTableElement e : createStmt.getTableElementList()) {
                if (e instanceof SQLColumnDefinition) {
                    SQLColumnDefinition columnDefinition = (SQLColumnDefinition) e;
                    SQLName columnName = columnDefinition.getName();
                    Column column = addColumn(tableName, columnName.toString());
                    if (isParentSelectItem(x.getParent())) {
                        column.setSelec(true);
                    }
                }
            }
        } else if (expr != null) {
            Column column = addColumn(expr, "*");
            if (isParentSelectItem(x.getParent())) {
                column.setSelec(true);
            }
        }
    }

    public Map<TableStat.Name, TableStat> getTables() {
        return tableStats;
    }

    public boolean containsTable(String tableName) {
        return tableStats.containsKey(new TableStat.Name(tableName));
    }

    public boolean containsColumn(String tableName, String columnName) {
        long hashCode;

        int p = tableName.indexOf('.');
        if (p != -1) {
            SQLExpr owner = SQLUtils.toSQLExpr(tableName, dbType);
            hashCode = new SQLPropertyExpr(owner, columnName).hashCode64();
        } else {
            hashCode = FnvHash.hashCode64(tableName, columnName);
        }
        return columns.containsKey(hashCode);
    }

    public Collection<Column> getColumns() {
        return columns.values();
    }

    public Column getColumn(String tableName, String columnName) {
        Column column = new Column(tableName, columnName);

        return this.columns.get(column.hashCode64());
    }

    public boolean visit(SQLSelectStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        visit(x.getSelect());

        return false;
    }

    public void endVisit(SQLSelectStatement x) {
    }

    @Override
    public boolean visit(SQLWithSubqueryClause.Entry x) {
        String alias = x.getAlias();
        SQLWithSubqueryClause with = (SQLWithSubqueryClause) x.getParent();

        SQLStatement returningStatement = x.getReturningStatement();
        SQLExpr expr = x.getExpr();

        if (Boolean.TRUE == with.getRecursive()) {
            SQLSelect select = x.getSubQuery();
            if (select != null) {
                select.accept(this);
            } else {
                returningStatement.accept(this);
            }
        } else {
            SQLSelect select = x.getSubQuery();
            if (select != null) {
                select.accept(this);
            } else if (expr != null) {
                expr.accept(this);
            } else {
                if (returningStatement != null) {
                    returningStatement.accept(this);
                }
            }
        }

        return false;
    }

    public boolean visit(SQLSubqueryTableSource x) {
        x.getSelect().accept(this);
        return false;
    }

    protected boolean isSimpleExprTableSource(SQLExprTableSource x) {
        return x.getExpr() instanceof SQLName;
    }

    public TableStat getTableStat(SQLExprTableSource tableSource) {
        return getTableStatWithUnwrap(
                tableSource.getExpr());
    }

    protected TableStat getTableStatWithUnwrap(SQLExpr expr) {
        SQLExpr identExpr = null;

        expr = unwrapExpr(expr);

        if (expr instanceof SQLIdentifierExpr) {
            SQLIdentifierExpr identifierExpr = (SQLIdentifierExpr) expr;

            if (identifierExpr.nameHashCode64() == FnvHash.Constants.DUAL) {
                return null;
            }

            if (isSubQueryOrParamOrVariant(identifierExpr)) {
                return null;
            }
        }

        SQLTableSource tableSource = null;
        if (expr instanceof SQLIdentifierExpr) {
            tableSource = ((SQLIdentifierExpr) expr).getResolvedTableSource();
        } else if (expr instanceof SQLPropertyExpr) {
            tableSource = ((SQLPropertyExpr) expr).getResolvedTableSource();
        }

        if (tableSource instanceof SQLExprTableSource) {
            SQLExpr tableSourceExpr = ((SQLExprTableSource) tableSource).getExpr();
            if (tableSourceExpr instanceof SQLName) {
                identExpr = tableSourceExpr;
            }
        }

        if (identExpr == null) {
            identExpr = expr;
        }

        if (identExpr instanceof SQLName) {
            return getTableStat((SQLName) identExpr);
        }
        return getTableStat(identExpr.toString());
    }

    public boolean visit(SQLExprTableSource x) {
        SQLExpr expr = x.getExpr();
        if (expr instanceof SQLMethodInvokeExpr) {
            SQLMethodInvokeExpr func = (SQLMethodInvokeExpr) expr;
            if (func.methodNameHashCode64() == FnvHash.Constants.ANN) {
                expr = func.getArguments().get(0);
            }
        }

        if (isSimpleExprTableSource(x)) {
            TableStat stat = getTableStatWithUnwrap(expr);
            if (stat == null) {
                return false;
            }

            Mode mode = getMode();
            if (mode != null) {
                switch (mode) {
                    case Delete:
                        stat.incrementDeleteCount();
                        break;
                    case Insert:
                        stat.incrementInsertCount();
                        break;
                    case Update:
                        stat.incrementUpdateCount();
                        break;
                    case Select:
                        stat.incrementSelectCount();
                        break;
                    case Merge:
                        stat.incrementMergeCount();
                        break;
                    case Drop:
                        stat.incrementDropCount();
                        break;
                    default:
                        break;
                }
            }
        } else {
            accept(expr);
        }

        return false;
    }

    protected boolean isSubQueryOrParamOrVariant(SQLIdentifierExpr identifierExpr) {
        SQLObject resolvedColumnObject = identifierExpr.getResolvedColumnObject();
        if (resolvedColumnObject instanceof SQLWithSubqueryClause.Entry
                || resolvedColumnObject instanceof SQLParameter
                || resolvedColumnObject instanceof SQLDeclareItem) {
            return true;
        }

        SQLObject resolvedOwnerObject = identifierExpr.getResolvedOwnerObject();
        if (resolvedOwnerObject instanceof SQLSubqueryTableSource
                || resolvedOwnerObject instanceof SQLWithSubqueryClause.Entry) {
            return true;
        }

        return false;
    }

    protected boolean isSubQueryOrParamOrVariant(SQLPropertyExpr x) {
        SQLObject resolvedOwnerObject = x.getResolvedOwnerObject();
        if (resolvedOwnerObject instanceof SQLSubqueryTableSource
                || resolvedOwnerObject instanceof SQLWithSubqueryClause.Entry) {
            return true;
        }

        SQLExpr owner = x.getOwner();
        if (owner instanceof SQLIdentifierExpr) {
            if (isSubQueryOrParamOrVariant((SQLIdentifierExpr) owner)) {
                return true;
            }
        }

        SQLTableSource tableSource = x.getResolvedTableSource();
        if (tableSource instanceof SQLExprTableSource) {
            SQLExprTableSource exprTableSource = (SQLExprTableSource) tableSource;
            if (exprTableSource.getSchemaObject() != null) {
                return false;
            }

            SQLExpr expr = exprTableSource.getExpr();

            if (expr instanceof SQLIdentifierExpr) {
                return isSubQueryOrParamOrVariant((SQLIdentifierExpr) expr);
            }

            if (expr instanceof SQLPropertyExpr) {
                return isSubQueryOrParamOrVariant((SQLPropertyExpr) expr);
            }
        }

        return false;
    }

    public boolean visit(SQLSelectItem x) {
        statExpr(
                x.getExpr());

        return false;
    }

    public boolean visit(SQLSelect x) {
        SQLWithSubqueryClause with = x.getWithSubQuery();
        if (with != null) {
            with.accept(this);
        }

        SQLSelectQuery query = x.getQuery();
        if (query != null) {
            query.accept(this);
        }

        SQLOrderBy orderBy = x.getOrderBy();
        if (orderBy != null) {
            accept(x.getOrderBy());
        }

        return false;
    }

    public boolean visit(SQLAggregateExpr x) {
        this.aggregateFunctions.add(x);

        accept(x.getArguments());
        accept(x.getOrderBy());
        accept(x.getOver());
        return false;
    }

    public boolean visit(SQLMethodInvokeExpr x) {
        this.functions.add(x);

        accept(x.getArguments());
        return false;
    }

    public boolean visit(SQLUpdateStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        setMode(x, Mode.Update);

        SQLTableSource tableSource = x.getTableSource();
        if (tableSource instanceof SQLExprTableSource) {
            SQLName identName = ((SQLExprTableSource) tableSource).getName();
            TableStat stat = getTableStat(identName);
            stat.incrementUpdateCount();
        } else {
            tableSource.accept(this);
        }

        final SQLTableSource from = x.getFrom();
        if (from != null) {
            from.accept(this);
        }

        final List<SQLUpdateSetItem> items = x.getItems();
        for (int i = 0, size = items.size(); i < size; ++i) {
            SQLUpdateSetItem item = items.get(i);
            visit(item);
        }

        final SQLExpr where = x.getWhere();
        if (where != null) {
            where.accept(this);
        }

        return false;
    }

    public boolean visit(SQLUpdateSetItem x) {
        final SQLExpr column = x.getColumn();
        if (column != null) {
            statExpr(column);

            final Column columnStat = getColumn(column);
            if (columnStat != null) {
                columnStat.setUpdate(true);
            }
        }

        final SQLExpr value = x.getValue();
        if (value != null) {
            statExpr(value);
        }

        return false;
    }

    public boolean visit(SQLDeleteStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        setMode(x, Mode.Delete);

        if (x.getTableSource() instanceof SQLSubqueryTableSource) {
            SQLSelectQuery selectQuery = ((SQLSubqueryTableSource) x.getTableSource()).getSelect().getQuery();
            if (selectQuery instanceof SQLSelectQueryBlock) {
                SQLSelectQueryBlock subQueryBlock = ((SQLSelectQueryBlock) selectQuery);
                subQueryBlock.getWhere().accept(this);
            }
        }

        TableStat stat = getTableStat(x.getTableName());
        stat.incrementDeleteCount();

        final SQLExpr where = x.getWhere();
        if (where != null) {
            where.accept(this);
        }

        return false;
    }

    public boolean visit(SQLInListExpr x) {
        if (x.isNot()) {
            handleCondition(x.getExpr(), "NOT IN", x.getTargetList());
        } else {
            handleCondition(x.getExpr(), "IN", x.getTargetList());
        }

        return true;
    }

    @Override
    public boolean visit(SQLInSubQueryExpr x) {
        if (x.isNot()) {
            handleCondition(x.getExpr(), "NOT IN");
        } else {
            handleCondition(x.getExpr(), "IN");
        }
        return true;
    }

    public void endVisit(SQLDeleteStatement x) {
    }

    public void endVisit(SQLUpdateStatement x) {
    }

    public boolean visit(SQLCreateTableStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        for (SQLTableElement e : x.getTableElementList()) {
            e.setParent(x);
        }

        TableStat stat = getTableStat(x.getName());
        stat.incrementCreateCount();

        accept(x.getTableElementList());

        if (x.getInherits() != null) {
            x.getInherits().accept(this);
        }

        if (x.getSelect() != null) {
            x.getSelect().accept(this);
            stat.incrementInsertCount();
        }

        SQLExprTableSource like = x.getLike();
        if (like != null) {
            like.accept(this);
        }

        return false;
    }

    public boolean visit(SQLColumnDefinition x) {
        SQLName tableName = null;
        {
            SQLObject parent = x.getParent();
            if (parent instanceof SQLCreateTableStatement) {
                tableName = ((SQLCreateTableStatement) parent).getName();
            }
        }

        if (tableName == null) {
            return true;
        }

        String columnName = x.getName().toString();
        Column column = addColumn(tableName, columnName);
        if (x.getDataType() != null) {
            column.setDataType(x.getDataType().getName());
        }

        for (SQLColumnConstraint item : x.getConstraints()) {
            if (item instanceof SQLPrimaryKey) {
                column.setPrimaryKey(true);
            } else if (item instanceof SQLUnique) {
                column.setUnique(true);
            }
        }

        return false;
    }

    @Override
    public boolean visit(SQLCallStatement x) {
        return false;
    }

    @Override
    public void endVisit(SQLCommentStatement x) {
    }

    @Override
    public boolean visit(SQLCommentStatement x) {
        return false;
    }

    public boolean visit(SQLCurrentOfCursorExpr x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableAddColumn x) {
        SQLAlterTableStatement stmt = (SQLAlterTableStatement) x.getParent();
        String table = stmt.getName().toString();

        for (SQLColumnDefinition column : x.getColumns()) {
            String columnName = SQLUtils.normalize(column.getName().toString());
            addColumn(stmt.getName(), columnName);
        }
        return false;
    }

    @Override
    public void endVisit(SQLAlterTableAddColumn x) {
    }

    @Override
    public boolean visit(SQLRollbackStatement x) {
        return false;
    }

    public boolean visit(SQLCreateViewStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        SQLSelect subQuery = x.getSubQuery();
        if (subQuery != null) {
            subQuery.accept(this);
        }

        SQLBlockStatement script = x.getScript();
        if (script != null) {
            script.accept(this);
        }
        return false;
    }

    public boolean visit(SQLAlterViewStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        x.getSubQuery().accept(this);
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDropForeignKey x) {
        return false;
    }

    @Override
    public boolean visit(SQLUseStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDisableConstraint x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableEnableConstraint x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        TableStat stat = getTableStat(x.getName());
        stat.incrementAlterCount();

        for (SQLAlterTableItem item : x.getItems()) {
            item.setParent(x);
            if (item instanceof SQLAlterTableAddPartition
                    || item instanceof SQLAlterTableRenamePartition
                    || item instanceof SQLAlterTableMergePartition
            ) {
                stat.incrementAddPartitionCount();
            }

            item.accept(this);
        }

        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDropConstraint x) {
        return false;
    }

    @Override
    public boolean visit(SQLDropIndexStatement x) {
        setMode(x, Mode.DropIndex);
        SQLExprTableSource table = x.getTableName();
        if (table != null) {
            SQLName name = (SQLName) table.getExpr();
            TableStat stat = getTableStat(name);
            stat.incrementDropIndexCount();
        }
        return false;
    }

    @Override
    public boolean visit(SQLCreateIndexStatement x) {
        setMode(x, Mode.CreateIndex);

        SQLName table = (SQLName) ((SQLExprTableSource) x.getTable()).getExpr();
        TableStat stat = getTableStat(table);
        stat.incrementCreateIndexCount();

        for (SQLSelectOrderByItem item : x.getItems()) {
            SQLExpr expr = item.getExpr();
            if (expr instanceof SQLIdentifierExpr) {
                SQLIdentifierExpr identExpr = (SQLIdentifierExpr) expr;
                String columnName = identExpr.getName();
                addColumn(table, columnName);
            } else if (expr instanceof SQLMethodInvokeExpr) {
                SQLMethodInvokeExpr methodInvokeExpr = (SQLMethodInvokeExpr) expr;
                if (methodInvokeExpr.getArguments().size() == 1) {
                    SQLExpr param = methodInvokeExpr.getArguments().get(0);
                    if (param instanceof SQLIdentifierExpr) {
                        SQLIdentifierExpr identExpr = (SQLIdentifierExpr) param;
                        String columnName = identExpr.getName();
                        addColumn(table, columnName);
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean visit(SQLForeignKeyImpl x) {
        for (SQLName column : x.getReferencingColumns()) {
            column.accept(this);
        }

        SQLName table = x.getReferencedTableName();

        TableStat stat = getTableStat(x.getReferencedTableName());
        stat.incrementReferencedCount();
        for (SQLName column : x.getReferencedColumns()) {
            String columnName = column.getSimpleName();
            addColumn(table, columnName);
        }

        return false;
    }

    @Override
    public boolean visit(SQLDropSequenceStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLDropTriggerStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLDropUserStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLGrantStatement x) {
        if (x.getResource() != null && (x.getResourceType() == null || x.getResourceType() == SQLObjectType.TABLE)) {
            x.getResource().accept(this);
        }
        return false;
    }

    @Override
    public boolean visit(SQLRevokeStatement x) {
        if (x.getResource() != null) {
            x.getResource().accept(this);
        }
        return false;
    }

    @Override
    public boolean visit(SQLDropDatabaseStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableAddIndex x) {
        for (SQLSelectOrderByItem item : x.getColumns()) {
            item.accept(this);
        }

        SQLName table = ((SQLAlterTableStatement) x.getParent()).getName();
        TableStat tableStat = this.getTableStat(table);
        tableStat.incrementCreateIndexCount();
        return false;
    }

    public boolean visit(SQLCheck x) {
        x.getExpr().accept(this);
        return false;
    }

    public boolean visit(SQLDefault x) {
        x.getExpr().accept(this);
        x.getColumn().accept(this);
        return false;
    }

    public boolean visit(SQLCreateTriggerStatement x) {
        SQLExprTableSource on = x.getOn();
        on.accept(this);
        return false;
    }

    public boolean visit(SQLDropFunctionStatement x) {
        return false;
    }

    public boolean visit(SQLDropTableSpaceStatement x) {
        return false;
    }

    public boolean visit(SQLDropProcedureStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableRename x) {
        return false;
    }

    @Override
    public boolean visit(SQLArrayExpr x) {
        accept(x.getValues());

        SQLExpr exp = x.getExpr();
        if (exp instanceof SQLIdentifierExpr) {
            if (((SQLIdentifierExpr) exp).getName().equals("ARRAY")) {
                return false;
            }
        }
        if (exp != null) {
            exp.accept(this);
        }
        return false;
    }

    @Override
    public boolean visit(SQLOpenStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLFetchStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLDropMaterializedViewStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLShowMaterializedViewStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLRefreshMaterializedViewStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLCloseStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLCreateProcedureStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        accept(x.getBlock());
        return false;
    }

    @Override
    public boolean visit(SQLCreateFunctionStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        accept(x.getBlock());
        return false;
    }

    @Override
    public boolean visit(SQLBlockStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        for (SQLParameter param : x.getParameters()) {
            param.setParent(x);
            param.accept(this);
        }

        for (SQLStatement stmt : x.getStatementList()) {
            stmt.accept(this);
        }

        SQLStatement exception = x.getException();
        if (exception != null) {
            exception.accept(this);
        }

        return false;
    }

    @Override
    public boolean visit(SQLShowTablesStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLDeclareItem x) {
        return false;
    }

    @Override
    public boolean visit(SQLPartitionByHash x) {
        return false;
    }

    @Override
    public boolean visit(SQLPartitionByRange x) {
        return false;
    }

    @Override
    public boolean visit(SQLPartitionByList x) {
        return false;
    }

    @Override
    public boolean visit(SQLPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLSubPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLSubPartitionByHash x) {
        return false;
    }

    @Override
    public boolean visit(SQLPartitionValue x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterDatabaseStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableConvertCharSet x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDropPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableReOrganizePartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableCoalescePartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableTruncatePartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDiscardPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableImportPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableAnalyzePartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableCheckPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableOptimizePartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableRebuildPartition x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableRepairPartition x) {
        return false;
    }

    public boolean visit(SQLSequenceExpr x) {
        return false;
    }

    @Override
    public boolean visit(SQLMergeStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        setMode(x.getUsing(), Mode.Select);
        x.getUsing().accept(this);

        setMode(x, Mode.Merge);

        SQLTableSource into = x.getInto();
        if (into instanceof SQLExprTableSource) {
            String ident = ((SQLExprTableSource) into).getExpr().toString();
            TableStat stat = getTableStat(ident);
            stat.incrementMergeCount();
        } else {
            into.accept(this);
        }

        x.getOn().accept(this);

        if (x.getUpdateClause() != null) {
            x.getUpdateClause().accept(this);
        }

        if (x.getInsertClause() != null) {
            x.getInsertClause().accept(this);
        }

        return false;
    }

    @Override
    public boolean visit(SQLSetStatement x) {
        return false;
    }

    public List<SQLMethodInvokeExpr> getFunctions() {
        return this.functions;
    }

    public boolean visit(SQLCreateSequenceStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableAddConstraint x) {
        SQLConstraint constraint = x.getConstraint();
        if (constraint instanceof SQLUniqueConstraint) {
            SQLAlterTableStatement stmt = (SQLAlterTableStatement) x.getParent();
            TableStat tableStat = this.getTableStat(stmt.getName());
            tableStat.incrementCreateIndexCount();
        }
        return true;
    }

    @Override
    public boolean visit(SQLAlterTableDropIndex x) {
        SQLAlterTableStatement stmt = (SQLAlterTableStatement) x.getParent();
        TableStat tableStat = this.getTableStat(stmt.getName());
        tableStat.incrementDropIndexCount();
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDropPrimaryKey x) {
        SQLAlterTableStatement stmt = (SQLAlterTableStatement) x.getParent();
        TableStat tableStat = this.getTableStat(stmt.getName());
        tableStat.incrementDropIndexCount();
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableDropKey x) {
        SQLAlterTableStatement stmt = (SQLAlterTableStatement) x.getParent();
        TableStat tableStat = this.getTableStat(stmt.getName());
        tableStat.incrementDropIndexCount();
        return false;
    }

    @Override
    public boolean visit(SQLDescribeStatement x) {
        SQLName tableName = x.getObject();

        TableStat tableStat = this.getTableStat(x.getObject());

        SQLName column = x.getColumn();
        if (column != null) {
            String columnName = column.toString();
            this.addColumn(tableName, columnName);
        }
        return false;
    }

    public boolean visit(SQLExplainStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        if (x.getStatement() != null) {
            accept(x.getStatement());
        }

        return false;
    }

    public boolean visit(SQLCreateMaterializedViewStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        SQLSelect query = x.getQuery();
        if (query != null) {
            query.accept(this);
        }

        return false;
    }

    public boolean visit(SQLReplaceStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        setMode(x, Mode.Replace);

        SQLName tableName = x.getTableName();

        TableStat stat = getTableStat(tableName);

        if (stat != null) {
            stat.incrementInsertCount();
        }

        accept(x.getColumns());
        accept(x.getValuesList());
        accept(x.getQuery());

        return false;
    }

    protected final void statExpr(SQLExpr x) {
        if (x == null) {
            return;
        }

        Class<?> clazz = x.getClass();
        if (clazz == SQLIdentifierExpr.class) {
            visit((SQLIdentifierExpr) x);
        } else if (clazz == SQLPropertyExpr.class) {
            visit((SQLPropertyExpr) x);
//        } else if (clazz == SQLAggregateExpr.class) {
//            visit((SQLAggregateExpr) x);
        } else if (clazz == SQLBinaryOpExpr.class) {
            visit((SQLBinaryOpExpr) x);
//        } else if (clazz == SQLCharExpr.class) {
//            visit((SQLCharExpr) x);
//        } else if (clazz == SQLNullExpr.class) {
//            visit((SQLNullExpr) x);
//        } else if (clazz == SQLIntegerExpr.class) {
//            visit((SQLIntegerExpr) x);
//        } else if (clazz == SQLNumberExpr.class) {
//            visit((SQLNumberExpr) x);
//        } else if (clazz == SQLMethodInvokeExpr.class) {
//            visit((SQLMethodInvokeExpr) x);
//        } else if (clazz == SQLVariantRefExpr.class) {
//            visit((SQLVariantRefExpr) x);
//        } else if (clazz == SQLBinaryOpExprGroup.class) {
//            visit((SQLBinaryOpExprGroup) x);
        } else if (x instanceof SQLLiteralExpr) {
            // skip
        } else {
            x.accept(this);
        }
    }

    public boolean visit(SQLAlterFunctionStatement x) {
        return false;
    }

    public boolean visit(SQLDropSynonymStatement x) {
        return false;
    }

    public boolean visit(SQLAlterTypeStatement x) {
        return false;
    }

    public boolean visit(SQLAlterProcedureStatement x) {
        return false;
    }

    public boolean visit(SQLExprStatement x) {
        SQLExpr expr = x.getExpr();

        if (expr instanceof SQLName) {
            return false;
        }

        return true;
    }

    @Override
    public boolean visit(SQLDropTypeStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLExternalRecordFormat x) {
        return false;
    }

    public boolean visit(SQLCreateDatabaseStatement x) {
        return false;
    }

    public boolean visit(SQLCreateTableGroupStatement x) {
        return false;
    }

    public boolean visit(SQLDropTableGroupStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLShowDatabasesStatement x) {
        return false;
    }

    public boolean visit(SQLShowColumnsStatement x) {
        SQLName table = x.getTable();

        TableStat stat = getTableStat(table);
        if (stat != null) {
//            stat.incrementSh
        }

        return false;
    }

    public boolean visit(SQLShowCreateTableStatement x) {
        SQLName table = x.getName();

        if (table != null) {
            TableStat stat = getTableStat(table);
            if (stat != null) {
//            stat.incrementSh
            }
        }

        return false;
    }

    public boolean visit(SQLShowTableGroupsStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableSetOption x) {
        return false;
    }

    @Override
    public boolean visit(SQLShowCreateViewStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLCreateRoleStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLDropRoleStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLShowViewsStatement x) {
        return false;
    }

    @Override
    public boolean visit(SQLAlterTableExchangePartition x) {
        SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
        }
        return false;
    }

    public boolean visit(SQLDropCatalogStatement x) {
        return false;
    }

    public boolean visit(SQLUnionQuery x) {
        SQLUnionOperator operator = x.getOperator();
        List<SQLSelectQuery> relations = x.getRelations();
        if (relations.size() > 2) {
            for (SQLSelectQuery relation : x.getRelations()) {
                relation.accept(this);
            }
            return false;
        }

        SQLSelectQuery left = x.getLeft();
        SQLSelectQuery right = x.getRight();

        boolean bracket = x.isParenthesized() && !(x.getParent() instanceof SQLUnionQueryTableSource);

        if ((!bracket)
                && left instanceof SQLUnionQuery
                && ((SQLUnionQuery) left).getOperator() == operator
                && !right.isParenthesized()
                && x.getOrderBy() == null) {
            SQLUnionQuery leftUnion = (SQLUnionQuery) left;

            List<SQLSelectQuery> rights = new ArrayList<SQLSelectQuery>();
            rights.add(right);

            if (leftUnion.getRelations().size() > 2) {
                rights.addAll(leftUnion.getRelations());
            } else {
                for (; ; ) {
                    SQLSelectQuery leftLeft = leftUnion.getLeft();
                    SQLSelectQuery leftRight = leftUnion.getRight();

                    if ((!leftUnion.isParenthesized())
                            && leftUnion.getOrderBy() == null
                            && (!leftLeft.isParenthesized())
                            && (!leftRight.isParenthesized())
                            && leftLeft instanceof SQLUnionQuery
                            && ((SQLUnionQuery) leftLeft).getOperator() == operator) {
                        rights.add(leftRight);
                        leftUnion = (SQLUnionQuery) leftLeft;
                        continue;
                    } else {
                        rights.add(leftRight);
                        rights.add(leftLeft);
                    }
                    break;
                }
            }

            for (int i = rights.size() - 1; i >= 0; i--) {
                SQLSelectQuery item = rights.get(i);
                item.accept(this);
            }
            return false;
        }

        return true;
    }

    @Override
    public boolean visit(SQLValuesTableSource x) {
        return false;
    }

    public boolean visit(SQLAlterIndexStatement x) {
        final SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
        }
        return false;
    }

    public boolean visit(SQLShowIndexesStatement x) {
        final SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
        }
        return false;
    }

    public boolean visit(SQLAnalyzeTableStatement x) {
        for (SQLExprTableSource table : x.getTables()) {
            if (table != null) {
                TableStat stat = getTableStat(table.getName());
                if (stat != null) {
                    stat.incrementAnalyzeCount();
                }
            }
        }

        SQLExprTableSource table = x.getTables().size() == 1 ? x.getTables().get(0) : null;

        SQLPartitionRef partition = x.getPartition();
        if (partition != null) {
            for (SQLPartitionRef.Item item : partition.getItems()) {
                SQLIdentifierExpr columnName = item.getColumnName();
                columnName.setResolvedTableSource(table);
                columnName.accept(this);
            }
        }

        return false;
    }

    public boolean visit(SQLExportTableStatement x) {
        final SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
        }

        for (SQLAssignItem item : x.getPartition()) {
            final SQLExpr target = item.getTarget();
            if (target instanceof SQLIdentifierExpr) {
                ((SQLIdentifierExpr) target).setResolvedTableSource(table);
                target.accept(this);
            }
        }

        return false;
    }

    public boolean visit(SQLImportTableStatement x) {
        final SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
        }

        for (SQLAssignItem item : x.getPartition()) {
            final SQLExpr target = item.getTarget();
            if (target instanceof SQLIdentifierExpr) {
                ((SQLIdentifierExpr) target).setResolvedTableSource(table);
                target.accept(this);
            }
        }

        return false;
    }

    public boolean visit(SQLCreateOutlineStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        if (x.getOn() != null) {
            x.getOn().accept(this);
        }

        if (x.getTo() != null) {
            x.getTo().accept(this);
        }

        return false;
    }

    public boolean visit(SQLDumpStatement x) {
        if (repository != null
                && x.getParent() == null) {
            repository.resolve(x);
        }

        final SQLExprTableSource into = x.getInto();
        if (into != null) {
            into.accept(this);
        }

        final SQLSelect select = x.getSelect();
        if (select != null) {
            select.accept(this);
        }

        return false;
    }

    public boolean visit(SQLDropOutlineStatement x) {
        return false;
    }

    public boolean visit(SQLAlterOutlineStatement x) {
        return false;
    }

    public boolean visit(SQLAlterTableArchivePartition x) {
        return true;
    }

    @Override
    public boolean visit(HiveCreateTableStatement x) {
        return visit((SQLCreateTableStatement) x);
    }

    @Override
    public boolean visit(SQLCopyFromStatement x) {
        SQLExprTableSource table = x.getTable();
        if (table != null) {
            table.accept(this);
            for (SQLName column : x.getColumns()) {
                addColumn(table.getName(), column.getSimpleName());
            }
        }

        return false;
    }

    @Override
    public boolean visit(SQLCloneTableStatement x) {
        SQLExprTableSource from = x.getFrom();
        if (from != null) {
            TableStat stat = getTableStat(from.getName());
            if (stat != null) {
                stat.incrementSelectCount();
            }
        }

        SQLExprTableSource to = x.getTo();
        if (to != null) {
            TableStat stat = getTableStat(to.getName());
            if (stat != null) {
                stat.incrementInsertCount();
            }
        }
        return false;
    }

    @Override
    public boolean visit(SQLSyncMetaStatement x) {
        return false;
    }

    public List<SQLName> getOriginalTables() {
        return originalTables;
    }

    @Override
    public boolean visit(SQLUnique x) {
        for (SQLSelectOrderByItem column : x.getColumns()) {
            column.accept(this);
        }
        return false;
    }

    public boolean visit(SQLSavePointStatement x) {
        return false;
    }

    public boolean visit(SQLShowPartitionsStmt x) {
        setMode(x, Mode.DESC);
        return true;
    }
}

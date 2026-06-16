package kestrel.engine

import kestrel.KestrelException
import kestrel.catalog.Catalog
import kestrel.catalog.CatalogSnapshot
import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.IndexDefinition
import kestrel.catalog.Sequence
import kestrel.catalog.SequenceDefinition
import kestrel.catalog.TableSchema
import kestrel.catalog.TriggerDefinition
import kestrel.catalog.UniqueConstraint
import kestrel.sql.ast.AddColumn
import kestrel.sql.ast.AddConstraint
import kestrel.sql.ast.AddedCheck
import kestrel.sql.ast.AddedConstraint
import kestrel.sql.ast.AddedForeignKey
import kestrel.sql.ast.AddedUnique
import kestrel.sql.ast.AlterSequenceStatement
import kestrel.sql.ast.AlterTableStatement
import kestrel.sql.ast.BeginStatement
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.ChangeColumnType
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.CommitStatement
import kestrel.sql.ast.ConflictDoNothing
import kestrel.sql.ast.ConflictDoUpdate
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateSequenceStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.CreateTriggerStatement
import kestrel.sql.ast.CreateViewStatement
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.DeallocateStatement
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropColumn
import kestrel.sql.ast.DropConstraint
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropMode
import kestrel.sql.ast.DropSequenceStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.DropTriggerStatement
import kestrel.sql.ast.DropViewStatement
import kestrel.sql.ast.ExecuteStatement
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.OnConflictClause
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.PrepareStatement
import kestrel.sql.ast.PreparableStatement
import kestrel.sql.ast.Projection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.ReleaseSavepointStatement
import kestrel.sql.ast.RenameColumn
import kestrel.sql.ast.RenameTable
import kestrel.sql.ast.RollbackStatement
import kestrel.sql.ast.RollbackToSavepointStatement
import kestrel.sql.ast.SavepointStatement
import kestrel.sql.ast.ScalarSubqueryExpression
import kestrel.sql.ast.SelectItem
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.sql.ast.TruncateTableStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.ast.WindowExpression
import java.math.BigDecimal
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.DecimalColumns
import kestrel.storage.Row
import kestrel.storage.Table
import kestrel.storage.Value
import kestrel.storage.displayWith
import kestrel.storage.typeName

class Executor(private val catalog: Catalog) : SequenceAccess {
    private val selectRunner = SelectRunner(catalog)
    private val subqueryRunner = SubqueryRunner { select, outer -> selectRunner.run(select, outer) }
    private var transactionSnapshot: CatalogSnapshot? = null
    private val savepoints = mutableListOf<Pair<String, CatalogSnapshot>>()

    /** Guards against a trigger whose body (directly or transitively) fires itself forever. */
    private var triggerDepth = 0

    /**
     * Postgres' CURRVAL is scoped to "the current session" - this engine's closest equivalent is
     * one [Executor] instance. Keyed by [Sequence] identity rather than by name so that ALTER
     * SEQUENCE (which always installs a new [Sequence] instance, see [Sequence.altered]) or a
     * DROP+CREATE of the same name invalidates any remembered value instead of silently handing
     * back a value that belonged to a different configuration of "the same" name - a deliberately
     * conservative choice over trying to decide case by case which ALTERs should or shouldn't
     * preserve it.
     */
    private val lastSequenceValues = HashMap<Sequence, Long>()

    /**
     * Session-scoped, deliberately outside [catalog] - see [PrepareStatement]'s own doc for why a
     * prepared statement is not part of the transactional snapshot/restore every other catalog
     * object participates in.
     */
    private val preparedStatements = linkedMapOf<String, PreparableStatement>()

    val preparedStatementNames: List<String> get() = preparedStatements.keys.toList()

    override fun nextValue(sequenceName: String): Long {
        val sequence = catalog.sequence(sequenceName)
        return sequence.next().also { lastSequenceValues[sequence] = it }
    }

    override fun currentValue(sequenceName: String): Long {
        val sequence = catalog.sequence(sequenceName)
        return lastSequenceValues[sequence]
            ?: throw KestrelException("currval of sequence '$sequenceName' is not yet defined in this session - call NEXTVAL first")
    }

    val inTransaction: Boolean get() = transactionSnapshot != null

    fun execute(statement: Statement): StatementResult = when (statement) {
        is CreateTableStatement -> executeCreateTable(statement)
        is DropTableStatement -> executeDropTable(statement)
        is TruncateTableStatement -> executeTruncate(statement)
        is AlterTableStatement -> executeAlterTable(statement)
        is CreateIndexStatement -> executeCreateIndex(statement)
        is DropIndexStatement -> executeDropIndex(statement)
        is CreateViewStatement -> executeCreateView(statement)
        is DropViewStatement -> executeDropView(statement)
        is CreateTriggerStatement -> executeCreateTrigger(statement)
        is DropTriggerStatement -> executeDropTrigger(statement)
        is CreateSequenceStatement -> executeCreateSequence(statement)
        is DropSequenceStatement -> executeDropSequence(statement)
        is AlterSequenceStatement -> executeAlterSequence(statement)
        is InsertStatement -> executeInsert(statement)
        is QueryStatement -> selectRunner.runQuery(statement)
        is DeleteStatement -> executeDelete(statement)
        is UpdateStatement -> executeUpdate(statement)
        is MergeStatement -> executeMerge(statement)
        is ExplainStatement -> ExplainResult(selectRunner.explain(statement.select))
        is BeginStatement -> executeBegin()
        is CommitStatement -> executeCommit()
        is RollbackStatement -> executeRollback()
        is SavepointStatement -> executeSavepoint(statement)
        is ReleaseSavepointStatement -> executeReleaseSavepoint(statement)
        is RollbackToSavepointStatement -> executeRollbackToSavepoint(statement)
        is PrepareStatement -> executePrepare(statement)
        is ExecuteStatement -> execute(resolveExecute(statement))
        is DeallocateStatement -> executeDeallocate(statement)
    }

    private fun executePrepare(statement: PrepareStatement): StatementResult {
        val key = statement.name.lowercase()
        if (preparedStatements.containsKey(key)) {
            throw KestrelException("prepared statement '${statement.name}' already exists")
        }
        preparedStatements[key] = statement.target
        return PrepareResult(statement.name)
    }

    /**
     * Looks [statement]'s name up, evaluates every argument expression as a CONSTANT (a bare
     * `Evaluator()` with no [ColumnResolver]/[SubqueryRunner]/[SequenceAccess] - a column reference,
     * subquery, or NEXTVAL/CURRVAL in an EXECUTE argument throws exactly like one would in a DEFAULT
     * expression, for the same reason: there is no row to resolve any of them against), and
     * substitutes the results for every `$1`, `$2`, ... in the prepared statement. The result is
     * always a concrete [PreparableStatement] - never [ExecuteStatement] itself - ready to run
     * through [execute] like any statement typed directly.
     */
    fun resolveExecute(statement: ExecuteStatement): Statement {
        val target = preparedStatements[statement.name.lowercase()]
            ?: throw KestrelException("no such prepared statement: ${statement.name}")
        val required = ParameterSubstitution.parameterCount(target)
        if (statement.arguments.size != required) {
            throw KestrelException(
                "prepared statement '${statement.name}' expects $required parameter(s) but got ${statement.arguments.size}"
            )
        }
        val values = statement.arguments.map { Evaluator().evaluate(it) }
        return ParameterSubstitution.substitute(target, values)
    }

    private fun executeDeallocate(statement: DeallocateStatement): StatementResult {
        if (statement.all) {
            preparedStatements.clear()
            return DeallocateResult(null, all = true)
        }
        val name = statement.name!!
        if (preparedStatements.remove(name.lowercase()) == null) {
            throw KestrelException("no such prepared statement: $name")
        }
        return DeallocateResult(name, all = false)
    }

    private fun executeBegin(): StatementResult {
        if (inTransaction) throw KestrelException("a transaction is already in progress")
        transactionSnapshot = catalog.snapshot()
        return BeginResult
    }

    private fun executeCommit(): StatementResult {
        if (!inTransaction) throw KestrelException("no transaction is in progress")
        transactionSnapshot = null
        savepoints.clear()
        return CommitResult
    }

    private fun executeRollback(): StatementResult {
        val snapshot = transactionSnapshot ?: throw KestrelException("no transaction is in progress")
        catalog.restore(snapshot)
        transactionSnapshot = null
        savepoints.clear()
        return RollbackResult
    }

    /**
     * A savepoint that reuses an already-established name is intentionally allowed (matches
     * Postgres): it shadows the earlier one, and RELEASE/ROLLBACK TO always target the most
     * recently established savepoint of that name via [indexOfLastSavepoint], leaving the older,
     * shadowed one addressable again once the newer one is released or rolled back past.
     */
    private fun executeSavepoint(statement: SavepointStatement): StatementResult {
        if (!inTransaction) throw KestrelException("SAVEPOINT can only be used inside a transaction")
        savepoints.add(statement.name to catalog.snapshot())
        return SavepointResult(statement.name)
    }

    private fun executeReleaseSavepoint(statement: ReleaseSavepointStatement): StatementResult {
        if (!inTransaction) throw KestrelException("RELEASE SAVEPOINT can only be used inside a transaction")
        val index = indexOfLastSavepoint(statement.name)
        while (savepoints.size > index) savepoints.removeAt(savepoints.size - 1)
        return ReleaseSavepointResult(statement.name)
    }

    private fun executeRollbackToSavepoint(statement: RollbackToSavepointStatement): StatementResult {
        if (!inTransaction) throw KestrelException("ROLLBACK TO SAVEPOINT can only be used inside a transaction")
        val index = indexOfLastSavepoint(statement.name)
        catalog.restore(savepoints[index].second)
        while (savepoints.size > index + 1) savepoints.removeAt(savepoints.size - 1)
        return RollbackToSavepointResult(statement.name)
    }

    private fun indexOfLastSavepoint(name: String): Int {
        val index = savepoints.indexOfLast { it.first.equals(name, ignoreCase = true) }
        if (index < 0) throw KestrelException("no such savepoint: $name")
        return index
    }

    private fun executeCreateTable(statement: CreateTableStatement): StatementResult {
        if (statement.columns.count { it.primaryKey } > 1 ||
            (statement.compositePrimaryKey.isNotEmpty() && statement.columns.any { it.primaryKey })
        ) {
            throw KestrelException("table '${statement.tableName}' declares more than one primary key")
        }
        val seen = mutableSetOf<String>()
        for (column in statement.columns) {
            if (!seen.add(column.name.lowercase())) {
                throw KestrelException("duplicate column name: ${column.name}")
            }
        }
        val columns = applyCompositePrimaryKey(statement)
        validateForeignKeyDefinitions(statement, columns)
        validateCheckDefinitions(statement)
        validateUniqueDefinitions(statement)
        validateDefaultDefinitions(columns)
        validateGeneratedDefinitions(columns)
        catalog.createTable(
            TableSchema(
                statement.tableName,
                columns,
                statement.foreignKeys,
                statement.checks,
                statement.uniqueConstraints,
                statement.compositePrimaryKey,
                statement.temporary
            )
        )
        return CreateTableResult(statement.tableName)
    }

    /**
     * A table-level `PRIMARY KEY (a, b, ...)` implies NOT NULL on every listed column, exactly
     * like the inline `col TYPE PRIMARY KEY` spelling already does - applied here (rather than in
     * the parser) because the parser builds [Column]s one at a time and has no view of the whole
     * table-level clause yet when it does.
     */
    private fun applyCompositePrimaryKey(statement: CreateTableStatement): List<Column> {
        if (statement.compositePrimaryKey.isEmpty()) return statement.columns
        val declared = statement.compositePrimaryKey.map { it.lowercase() }.toSet()
        if (declared.size != statement.compositePrimaryKey.size) {
            throw KestrelException("table '${statement.tableName}' declares a duplicate column in its PRIMARY KEY")
        }
        for (name in statement.compositePrimaryKey) {
            if (statement.columns.none { it.name.equals(name, ignoreCase = true) }) {
                throw KestrelException("PRIMARY KEY refers to unknown column: $name")
            }
        }
        return statement.columns.map { column ->
            if (column.name.lowercase() in declared) column.copy(notNull = true) else column
        }
    }

    private fun validateUniqueDefinitions(statement: CreateTableStatement) {
        for (unique in statement.uniqueConstraints) {
            for (columnName in unique.columns) {
                if (statement.columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw KestrelException("UNIQUE constraint refers to unknown column: $columnName")
                }
            }
        }
    }

    /**
     * A self-reference (a FK on the table pointing back at itself, e.g. an employee/manager
     * hierarchy) is checked against a [TableSchema] built from [columns] (already normalized by
     * [applyCompositePrimaryKey]) rather than the catalog, because the table doesn't exist there
     * yet while it's being created.
     */
    private fun validateForeignKeyDefinitions(statement: CreateTableStatement, columns: List<Column>) {
        for (fk in statement.foreignKeys) {
            if (fk.columns.size != fk.referencedColumns.size) {
                throw KestrelException(
                    "FOREIGN KEY (${fk.columns.joinToString(", ")}) column count does not match " +
                        "REFERENCES ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")})"
                )
            }
            for (columnName in fk.columns) {
                if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw KestrelException("FOREIGN KEY refers to unknown column: $columnName")
                }
            }
            val selfReference = fk.referencedTable.equals(statement.tableName, ignoreCase = true)
            val hasEnforcedUniqueness = if (selfReference) {
                for (columnName in fk.referencedColumns) {
                    if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                        throw KestrelException(
                            "FOREIGN KEY references unknown column: ${fk.referencedTable}.$columnName"
                        )
                    }
                }
                val selfSchema = TableSchema(
                    statement.tableName,
                    columns,
                    uniqueConstraints = statement.uniqueConstraints,
                    compositePrimaryKey = statement.compositePrimaryKey
                )
                selfSchema.hasEnforcedUniqueness(fk.referencedColumns)
            } else {
                val referencedTable = catalog.tableOrNull(fk.referencedTable)
                    ?: throw KestrelException("FOREIGN KEY references unknown table: ${fk.referencedTable}")
                for (columnName in fk.referencedColumns) {
                    if (referencedTable.schema.column(columnName) == null) {
                        throw KestrelException(
                            "FOREIGN KEY references unknown column: ${fk.referencedTable}.$columnName"
                        )
                    }
                }
                referencedTable.schema.hasEnforcedUniqueness(fk.referencedColumns)
            }
            if (!hasEnforcedUniqueness) {
                throw KestrelException(
                    "FOREIGN KEY must reference a PRIMARY KEY or UNIQUE column(s), but " +
                        "${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")}) is not one"
                )
            }
        }
    }

    private fun validateCheckDefinitions(statement: CreateTableStatement) {
        val schema = TableSchema(statement.tableName, statement.columns)
        for (check in statement.checks) {
            checkColumns(check.expression, schema)
            requireNoSubquery(check.expression)
        }
    }

    private fun validateDefaultDefinitions(columns: List<Column>) {
        for (column in columns) {
            val default = column.default ?: continue
            validateConstantDefault(default)
            if (default is NextValueExpression) {
                validateNextValueDefault(default, column)
                continue
            }
            coerceForColumn(Evaluator().evaluate(default), column)
        }
    }

    /**
     * A GENERATED column's expression is recomputed from this row's OTHER column values on every
     * INSERT/UPDATE (see [applyGeneratedColumns]) - it must therefore be a pure function of that
     * row's own columns: [checkColumns] rejects a reference to a nonexistent column plus any
     * aggregate/window function, [requireNoSubquery] rejects a subquery and NEXTVAL/CURRVAL/
     * MERGE_ACTION() on top of that (same two-function combination [validateCheckDefinitions] uses
     * for CHECK), and [requireNoGeneratedColumnReference] additionally forbids referencing another
     * GENERATED column - not because it's unsound in general, but because allowing it would need a
     * dependency-ordered evaluation pass instead of the current single independent pass over every
     * GENERATED column (see [applyGeneratedColumns]), matching Postgres's own restriction.
     */
    private fun validateGeneratedDefinitions(columns: List<Column>) {
        val schema = TableSchema("", columns)
        val generatedNames = columns.filter { it.generated != null }.map { it.name.lowercase() }.toSet()
        for (column in columns) {
            val generated = column.generated ?: continue
            if (column.default != null) {
                throw KestrelException("column ${column.name} cannot declare both DEFAULT and GENERATED ALWAYS AS")
            }
            checkColumns(generated, schema)
            requireNoSubquery(generated)
            requireNoGeneratedColumnReference(generated, generatedNames, column.name)
        }
    }

    private fun requireNoGeneratedColumnReference(expression: Expression, generatedNames: Set<String>, ownerColumn: String) {
        when (expression) {
            is ColumnExpression -> if (expression.name.lowercase() in generatedNames) {
                throw KestrelException(
                    "GENERATED column $ownerColumn cannot reference another GENERATED column: ${expression.name}"
                )
            }
            is LiteralExpression -> Unit
            is BinaryExpression -> {
                requireNoGeneratedColumnReference(expression.left, generatedNames, ownerColumn)
                requireNoGeneratedColumnReference(expression.right, generatedNames, ownerColumn)
            }
            is UnaryExpression -> requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
            is CallExpression -> expression.arguments.forEach { requireNoGeneratedColumnReference(it, generatedNames, ownerColumn) }
            is IsNullExpression -> requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
            is InExpression -> {
                requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
                expression.values.forEach { requireNoGeneratedColumnReference(it, generatedNames, ownerColumn) }
            }
            is BetweenExpression -> {
                requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
                requireNoGeneratedColumnReference(expression.low, generatedNames, ownerColumn)
                requireNoGeneratedColumnReference(expression.high, generatedNames, ownerColumn)
            }
            is LikeExpression -> {
                requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
                requireNoGeneratedColumnReference(expression.pattern, generatedNames, ownerColumn)
            }
            is CaseExpression -> {
                expression.operand?.let { requireNoGeneratedColumnReference(it, generatedNames, ownerColumn) }
                expression.branches.forEach {
                    requireNoGeneratedColumnReference(it.condition, generatedNames, ownerColumn)
                    requireNoGeneratedColumnReference(it.result, generatedNames, ownerColumn)
                }
                expression.elseExpression?.let { requireNoGeneratedColumnReference(it, generatedNames, ownerColumn) }
            }
            is CastExpression -> requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
            is CollateExpression -> requireNoGeneratedColumnReference(expression.operand, generatedNames, ownerColumn)
            // Already rejected earlier in validateGeneratedDefinitions by checkColumns/requireNoSubquery -
            // kept here only so this `when` stays exhaustive.
            is FunctionExpression, is WindowExpression, is ScalarSubqueryExpression, is ExistsExpression,
            is InSubqueryExpression, is NextValueExpression, is CurrentValueExpression, is MergeActionExpression,
            is ParameterExpression -> Unit
        }
    }

    /**
     * Recomputes every GENERATED column in [values] from its expression, in place - safe to call
     * any time after every non-GENERATED column has its final value for this row (explicit
     * assignment, DEFAULT, or otherwise), because [validateGeneratedDefinitions] guarantees a
     * GENERATED expression only ever reads a non-GENERATED column of the same row, never another
     * GENERATED one - so evaluation order among multiple GENERATED columns never matters.
     */
    private fun applyGeneratedColumns(schema: TableSchema, values: MutableList<Value>) {
        if (schema.columns.none { it.generated != null }) return
        val evaluator = Evaluator(schema, Row(values), subqueryRunner)
        for ((index, column) in schema.columns.withIndex()) {
            val generated = column.generated ?: continue
            values[index] = coerceForColumn(evaluator.evaluate(generated), column)
        }
    }

    /** Standard SQL forbids assigning a GENERATED column's value directly - it is always derived, never set. */
    private fun rejectGeneratedAssignment(column: Column, explicitValueGiven: Boolean) {
        if (explicitValueGiven && column.generated != null) {
            throw KestrelException("cannot assign an explicit value to GENERATED column ${column.name}")
        }
    }

    /**
     * [NextValueExpression] skips the generic "evaluate once to see what type comes back" check
     * [validateDefaultDefinitions] otherwise uses, because evaluating it for real would mean a bare
     * `CREATE TABLE` actually consumes a sequence value before a single row exists - instead this
     * checks the two things that actually matter statically: the sequence is real, and the column it
     * defaults can hold what NEXTVAL returns.
     */
    private fun validateNextValueDefault(default: NextValueExpression, column: Column) {
        catalog.sequence(default.sequenceName)
        if (column.type != ColumnType.Integer) {
            throw KestrelException("NEXTVAL can only be used as a DEFAULT for an INTEGER column, but ${column.name} is ${column.type.displayName}")
        }
    }

    /**
     * A DEFAULT must be a constant expression: no column reference (there is no row to read one
     * from when a value is missing at INSERT time, or none at all yet when backfilling ALTER
     * TABLE ADD COLUMN), no subquery, and no aggregate. [NextValueExpression] is the one exception -
     * see [validateNextValueDefault] - because unlike everything else here it names an external,
     * mutable counter rather than something computable from the expression alone.
     * [CurrentValueExpression] is rejected outright: CURRVAL only ever means something after a prior
     * NEXTVAL in the same session, which can never be true yet at CREATE TABLE/ADD COLUMN time.
     */
    private fun validateConstantDefault(expression: Expression) {
        when (expression) {
            is ColumnExpression -> throw KestrelException("DEFAULT expression cannot reference a column")
            is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression ->
                throw KestrelException("DEFAULT expression cannot contain a subquery")
            is FunctionExpression -> throw KestrelException("DEFAULT expression cannot contain an aggregate function")
            is BinaryExpression -> {
                validateConstantDefault(expression.left)
                validateConstantDefault(expression.right)
            }
            is UnaryExpression -> validateConstantDefault(expression.operand)
            is CallExpression -> expression.arguments.forEach { validateConstantDefault(it) }
            is IsNullExpression -> validateConstantDefault(expression.operand)
            is InExpression -> {
                validateConstantDefault(expression.operand)
                expression.values.forEach { validateConstantDefault(it) }
            }
            is BetweenExpression -> {
                validateConstantDefault(expression.operand)
                validateConstantDefault(expression.low)
                validateConstantDefault(expression.high)
            }
            is LikeExpression -> {
                validateConstantDefault(expression.operand)
                validateConstantDefault(expression.pattern)
            }
            is CaseExpression -> {
                expression.operand?.let { validateConstantDefault(it) }
                expression.branches.forEach {
                    validateConstantDefault(it.condition)
                    validateConstantDefault(it.result)
                }
                expression.elseExpression?.let { validateConstantDefault(it) }
            }
            is CastExpression -> validateConstantDefault(expression.operand)
            is CollateExpression -> validateConstantDefault(expression.operand)
            is WindowExpression -> throw KestrelException("DEFAULT expression cannot contain a window function")
            is NextValueExpression -> Unit
            is CurrentValueExpression -> throw KestrelException("DEFAULT expression cannot contain CURRVAL")
            is MergeActionExpression -> throw KestrelException("DEFAULT expression cannot contain MERGE_ACTION()")
            is ParameterExpression -> throw KestrelException("DEFAULT expression cannot contain a parameter reference")
            is LiteralExpression -> Unit
        }
    }

    private fun requireNoSubquery(expression: Expression) {
        when (expression) {
            is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression ->
                throw KestrelException("CHECK constraints and GENERATED column expressions cannot contain subqueries")
            is FunctionExpression -> throw KestrelException("aggregate functions are not allowed in CHECK constraints")
            is BinaryExpression -> {
                requireNoSubquery(expression.left)
                requireNoSubquery(expression.right)
            }
            is UnaryExpression -> requireNoSubquery(expression.operand)
            is CallExpression -> expression.arguments.forEach { requireNoSubquery(it) }
            is IsNullExpression -> requireNoSubquery(expression.operand)
            is InExpression -> {
                requireNoSubquery(expression.operand)
                expression.values.forEach { requireNoSubquery(it) }
            }
            is BetweenExpression -> {
                requireNoSubquery(expression.operand)
                requireNoSubquery(expression.low)
                requireNoSubquery(expression.high)
            }
            is LikeExpression -> {
                requireNoSubquery(expression.operand)
                requireNoSubquery(expression.pattern)
            }
            is CaseExpression -> {
                expression.operand?.let { requireNoSubquery(it) }
                expression.branches.forEach {
                    requireNoSubquery(it.condition)
                    requireNoSubquery(it.result)
                }
                expression.elseExpression?.let { requireNoSubquery(it) }
            }
            is CastExpression -> requireNoSubquery(expression.operand)
            is CollateExpression -> requireNoSubquery(expression.operand)
            is WindowExpression -> throw KestrelException("window functions are not allowed in CHECK constraints")
            is NextValueExpression, is CurrentValueExpression ->
                throw KestrelException("NEXTVAL/CURRVAL are not allowed in a CHECK constraint, trigger WHEN clause, or GENERATED column expression")
            is MergeActionExpression ->
                throw KestrelException("MERGE_ACTION() is not allowed in a CHECK constraint, trigger WHEN clause, or GENERATED column expression")
            is ParameterExpression ->
                throw KestrelException("a parameter reference is not allowed in a CHECK constraint, trigger WHEN clause, or GENERATED column expression")
            is ColumnExpression, is LiteralExpression -> Unit
        }
    }

    private fun enforceChecks(schema: TableSchema, row: Row) {
        for (check in schema.checks) {
            val result = Evaluator(schema, row, subqueryRunner).evaluate(check.expression)
            if (result is Value.BooleanValue && !result.value) {
                val label = check.name?.let { " $it" } ?: ""
                throw KestrelException("CHECK constraint$label failed: ${schema.name}")
            }
        }
    }

    private fun executeDropTable(statement: DropTableStatement): StatementResult {
        catalog.dropTable(statement.tableName, cascade = statement.mode == DropMode.Cascade)
        return DropTableResult(statement.tableName)
    }

    /**
     * TRUNCATE never fires a row-level trigger (Postgres/SQLite both only ever fire TRUNCATE-shaped
     * statement-level triggers this engine has no equivalent of) and never runs a FOREIGN KEY's
     * `onDelete` action row by row - see [kestrel.catalog.Catalog.truncateTables]'s own doc for why
     * its RESTRICT/CASCADE handling is structurally different from [ForeignKeyEnforcer.cascadeDelete].
     */
    private fun executeTruncate(statement: TruncateTableStatement): StatementResult {
        val truncated = catalog.truncateTables(statement.tableNames, statement.mode == DropMode.Cascade, statement.restartIdentity)
        return TruncateTableResult(truncated)
    }

    private fun executeAlterTable(statement: AlterTableStatement): StatementResult {
        when (val action = statement.action) {
            is AddColumn -> executeAddColumn(statement.tableName, action.column)
            is DropColumn -> catalog.dropColumn(statement.tableName, action.columnName, cascade = action.mode == DropMode.Cascade)
            is RenameColumn -> catalog.renameColumn(statement.tableName, action.oldName, action.newName)
            is RenameTable -> catalog.renameTable(statement.tableName, action.newName)
            is AddConstraint -> executeAddConstraint(statement.tableName, action.constraint)
            is DropConstraint -> catalog.dropConstraint(statement.tableName, action.name, cascade = action.mode == DropMode.Cascade)
            is ChangeColumnType -> executeChangeColumnType(statement.tableName, action)
        }
        val resultName = (statement.action as? RenameTable)?.newName ?: statement.tableName
        return AlterTableResult(resultName)
    }

    private fun executeAddConstraint(tableName: String, added: AddedConstraint) {
        when (added) {
            is AddedCheck -> executeAddCheckConstraint(tableName, added.check)
            is AddedUnique -> executeAddUniqueConstraint(tableName, added.unique)
            is AddedForeignKey -> executeAddForeignKeyConstraint(tableName, added.foreignKey)
        }
    }

    /**
     * `ALTER TABLE t ADD CONSTRAINT name CHECK (...)` - validated exactly like a CREATE TABLE-time
     * CHECK ([validateCheckDefinitions]: known columns only, no subquery), PLUS - since this table
     * may already hold rows a brand new CREATE TABLE never has to worry about - every existing row
     * is evaluated against it up front, the same per-row logic [enforceChecks] already runs on every
     * future INSERT/UPDATE.
     */
    private fun executeAddCheckConstraint(tableName: String, check: CheckConstraint) {
        val schema = catalog.table(tableName).schema
        check.name?.let { requireUniqueConstraintName(schema, it) }
        checkColumns(check.expression, schema)
        requireNoSubquery(check.expression)
        for (row in catalog.table(tableName).rows) {
            val result = Evaluator(schema, row, subqueryRunner).evaluate(check.expression)
            if (result is Value.BooleanValue && !result.value) {
                val label = check.name?.let { " $it" } ?: ""
                throw KestrelException("CHECK constraint$label failed: an existing row in $tableName violates it")
            }
        }
        catalog.addCheckConstraint(tableName, check)
    }

    /**
     * `ALTER TABLE t ADD CONSTRAINT name UNIQUE (...)` - every existing row is checked for a
     * duplicate combination of [unique]'s columns before the constraint is installed, the same
     * [collationFoldedUniquenessKey]-folded, NULL-exempt logic [enforceUniqueConstraints] already
     * runs on every future INSERT/UPDATE, just run once up front here against rows that already exist.
     */
    private fun executeAddUniqueConstraint(tableName: String, unique: UniqueConstraint) {
        val schema = catalog.table(tableName).schema
        unique.name?.let { requireUniqueConstraintName(schema, it) }
        for (columnName in unique.columns) {
            if (schema.columnIndex(columnName) == null) {
                throw KestrelException("UNIQUE constraint refers to unknown column: $columnName")
            }
        }
        val columnIndices = unique.columns.map { schema.columnIndex(it)!! }
        val seen = HashSet<List<Value>>()
        for (row in catalog.table(tableName).rows) {
            val rawKey = columnIndices.map { row.values[it] }
            if (rawKey.any { it is Value.NullValue }) continue
            if (!seen.add(collationFoldedUniquenessKey(schema, columnIndices, rawKey))) {
                val label = unique.name?.let { " $it" } ?: ""
                throw KestrelException(
                    "UNIQUE constraint$label failed: an existing row combination in " +
                        "$tableName(${unique.columns.joinToString(", ")}) is already duplicated"
                )
            }
        }
        catalog.addUniqueConstraint(tableName, unique)
    }

    /**
     * `ALTER TABLE t ADD CONSTRAINT name FOREIGN KEY (...) REFERENCES ...` - validated the same way
     * [validateForeignKeyDefinitions] validates one at CREATE TABLE time (unknown column, referenced
     * table/column existence, enforced uniqueness of the referenced column group), PLUS every
     * existing row of [tableName] is checked against it via [ForeignKeyEnforcer.validateRow] before
     * the constraint is installed - run against a throwaway [TableSchema] carrying [foreignKey]
     * alongside every FOREIGN KEY [tableName] already has, so a self-referencing FOREIGN KEY
     * (`referencedTable == tableName`) can find its own sibling rows the same way
     * [validateForeignKeyDefinitions] does for a genuinely new table, except those "siblings" here
     * are the table's own already-committed rows rather than an uncommitted INSERT batch.
     */
    private fun executeAddForeignKeyConstraint(tableName: String, foreignKey: ForeignKeyConstraint) {
        val schema = catalog.table(tableName).schema
        foreignKey.name?.let { requireUniqueConstraintName(schema, it) }
        if (foreignKey.columns.size != foreignKey.referencedColumns.size) {
            throw KestrelException(
                "FOREIGN KEY (${foreignKey.columns.joinToString(", ")}) column count does not match " +
                    "REFERENCES ${foreignKey.referencedTable}(${foreignKey.referencedColumns.joinToString(", ")})"
            )
        }
        for (columnName in foreignKey.columns) {
            if (schema.columnIndex(columnName) == null) {
                throw KestrelException("FOREIGN KEY refers to unknown column: $columnName")
            }
        }
        val selfReference = foreignKey.referencedTable.equals(tableName, ignoreCase = true)
        val referencedSchema = if (selfReference) {
            schema
        } else {
            catalog.tableOrNull(foreignKey.referencedTable)?.schema
                ?: throw KestrelException("FOREIGN KEY references unknown table: ${foreignKey.referencedTable}")
        }
        for (columnName in foreignKey.referencedColumns) {
            if (referencedSchema.column(columnName) == null) {
                throw KestrelException("FOREIGN KEY references unknown column: ${foreignKey.referencedTable}.$columnName")
            }
        }
        if (!referencedSchema.hasEnforcedUniqueness(foreignKey.referencedColumns)) {
            throw KestrelException(
                "FOREIGN KEY must reference a PRIMARY KEY or UNIQUE column(s), but " +
                    "${foreignKey.referencedTable}(${foreignKey.referencedColumns.joinToString(", ")}) is not one"
            )
        }
        val tempSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys + foreignKey, schema.checks,
            schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )
        for (row in catalog.table(tableName).rows) {
            ForeignKeyEnforcer.validateRow(catalog, tempSchema, row.values)
        }
        catalog.addForeignKey(tableName, foreignKey)
    }

    /**
     * `ALTER TABLE t ALTER COLUMN col TYPE newtype [USING expression]` - deliberately RESTRICT-only
     * (no CASCADE option, unlike [kestrel.catalog.Catalog.dropColumn]): a column that is part of
     * the PRIMARY KEY, any FOREIGN KEY (this table's own, or another table's referencing it), a
     * UNIQUE constraint, or a secondary index is rejected outright rather than threading a type
     * change through every one of those structures too - drop the dependency first, exactly like
     * `ALTER TABLE ADD COLUMN` already refuses to declare one of those inline
     * ([kestrel.sql.parser.Parser.parseAlterAddColumnDefinition]). A GENERATED column is rejected
     * for a related but distinct reason: its value is always freshly recomputed from its own
     * expression, never carried over from an OLD value the way [ChangeColumnType.using] (or its
     * implicit "recompute this column as its own old value" default) assumes - changing its type
     * means dropping and re-adding it with a new expression instead.
     */
    private fun executeChangeColumnType(tableName: String, action: ChangeColumnType) {
        val schema = catalog.table(tableName).schema
        val columnIndex = schema.columnIndex(action.columnName)
            ?: throw KestrelException("no such column: ${action.columnName} in table $tableName")
        val column = schema.columns[columnIndex]
        if (column.generated != null) {
            throw KestrelException(
                "cannot change the type of GENERATED column ${action.columnName} in $tableName; drop and re-add it instead"
            )
        }
        if (schema.primaryKeyColumns.any { it.equals(action.columnName, ignoreCase = true) }) {
            throw KestrelException(
                "cannot change the type of column ${action.columnName} because it is part of the PRIMARY KEY of $tableName"
            )
        }
        if (schema.foreignKeys.any { fk -> fk.columns.any { it.equals(action.columnName, ignoreCase = true) } }) {
            throw KestrelException(
                "cannot change the type of column ${action.columnName} because it is used by one of ${tableName}'s own " +
                    "FOREIGN KEY constraints; drop the constraint first"
            )
        }
        val referencingTables = catalog.tableNames.filter { other ->
    private fun executeCreateIndex(statement: CreateIndexStatement): StatementResult {
        catalog.createIndex(IndexDefinition(statement.indexName, statement.tableName, statement.columnName))
        return CreateIndexResult(statement.indexName)
    }

    private fun executeDropIndex(statement: DropIndexStatement): StatementResult {
        catalog.dropIndex(statement.indexName)
        return DropIndexResult(statement.indexName)
    }

    private fun executeInsert(statement: InsertStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val targetIndices = resolveInsertTargets(schema, statement.columns)
        val evaluator = Evaluator()
        val newRows = statement.rows.map { rawRow ->
            if (rawRow.size != targetIndices.size) {
                throw KestrelException(
                    "table ${schema.name} expects ${targetIndices.size} values but ${rawRow.size} were supplied"
                )
            }
            val values = MutableList<Value>(schema.columns.size) { Value.NullValue }
            for ((position, expression) in rawRow.withIndex()) {
                val columnIndex = targetIndices[position]
                values[columnIndex] = coerceForColumn(evaluator.evaluate(expression), schema.columns[columnIndex])
            }
            validateNotNull(schema, values)
            Row(values)
        }
        enforcePrimaryKeyUniqueness(schema, table.rows + newRows)
        table.rows.addAll(newRows)
        catalog.reindexTable(schema.name)
        return InsertResult(newRows.size)
    }

    private fun resolveInsertTargets(schema: TableSchema, columns: List<String>?): List<Int> {
        if (columns == null) return schema.columns.indices.toList()
        return columns.map { columnName ->
            schema.columnIndex(columnName)
                ?: throw KestrelException("no such column: $columnName in table ${schema.name}")
        }
    }

    private fun executeDelete(statement: DeleteStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        if (statement.where == null) {
            val removed = table.rows.size
            table.rows.clear()
            catalog.reindexTable(schema.name)
            return DeleteResult(removed)
        }
        checkColumns(statement.where, schema)
        val kept = mutableListOf<Row>()
        var deleted = 0
        for (row in table.rows) {
            if (isTrue(Evaluator(schema, row).evaluate(statement.where))) {
                deleted++
            } else {
                kept.add(row)
            }
        }
        table.rows.clear()
        table.rows.addAll(kept)
        catalog.reindexTable(schema.name)
        return DeleteResult(deleted)
    }

    private fun executeUpdate(statement: UpdateStatement): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val assignments = statement.assignments.map { assignment ->
            val index = schema.columnIndex(assignment.columnName)
                ?: throw KestrelException("no such column: ${assignment.columnName}")
            checkColumns(assignment.value, schema)
            index to assignment.value
        }
        statement.where?.let { checkColumns(it, schema) }

        var updated = 0
        val newRows = table.rows.map { row ->
            val matches = statement.where == null || isTrue(Evaluator(schema, row).evaluate(statement.where))
            if (!matches) return@map row
            val values = row.values.toMutableList()
            val evaluator = Evaluator(schema, row)
            for ((index, expression) in assignments) {
                values[index] = coerceForColumn(evaluator.evaluate(expression), schema.columns[index])
            }
            validateNotNull(schema, values)
            updated++
            Row(values)
        }
        enforcePrimaryKeyUniqueness(schema, newRows)
        table.rows.clear()
        table.rows.addAll(newRows)
        catalog.reindexTable(schema.name)
        return UpdateResult(updated)
    }

    private fun coerceForColumn(value: Value, column: Column): Value {
        if (value is Value.NullValue) return value
        return when (column.type) {
            ColumnType.Integer -> value as? Value.IntegerValue ?: throw typeMismatch(column, value)
            ColumnType.Text -> value as? Value.TextValue ?: throw typeMismatch(column, value)
            ColumnType.Boolean -> value as? Value.BooleanValue ?: throw typeMismatch(column, value)
            ColumnType.Real -> when (value) {
                is Value.RealValue -> value
                is Value.IntegerValue -> Value.RealValue(value.value.toDouble())
                else -> throw typeMismatch(column, value)
            }
        }
    }

    private fun typeMismatch(column: Column, value: Value): KestrelException =
        KestrelException("type mismatch: column ${column.name} expects ${column.type.displayName} but got ${value.typeName}")

    private fun validateNotNull(schema: TableSchema, values: List<Value>) {
        for ((index, column) in schema.columns.withIndex()) {
            if (column.notNull && values[index] is Value.NullValue) {
                throw KestrelException("NOT NULL constraint failed: ${schema.name}.${column.name}")
            }
        }
    }

    private fun enforcePrimaryKeyUniqueness(schema: TableSchema, rows: List<Row>) {
        val primaryKeyIndex = schema.primaryKeyIndex ?: return
        val seen = HashSet<Value>()
        for (row in rows) {
            val key = row.values[primaryKeyIndex]
            if (!seen.add(key)) {
                throw KestrelException("UNIQUE constraint failed: ${schema.name}.${schema.columns[primaryKeyIndex].name}")
            }
        }
    }

    private fun checkColumns(expression: Expression, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> if (schema.columnIndex(expression.name) == null) {
                throw KestrelException("no such column: ${expression.name}")
            }
            is BinaryExpression -> {
                checkColumns(expression.left, schema)
                checkColumns(expression.right, schema)
            }
            is UnaryExpression -> checkColumns(expression.operand, schema)
            is FunctionExpression -> throw KestrelException("aggregate functions are not allowed here")
            is LiteralExpression -> Unit
        }
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value
}

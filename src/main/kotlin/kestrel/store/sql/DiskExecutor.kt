package kestrel.store.sql

import java.math.BigDecimal
import kestrel.KestrelException
import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.Sequence
import kestrel.catalog.SequenceDefinition
import kestrel.catalog.TableSchema
import kestrel.catalog.TriggerDefinition
import kestrel.catalog.UniqueConstraint
import kestrel.engine.AggregateFunctions
import kestrel.engine.AlterSequenceResult
import kestrel.engine.AlterTableResult
import kestrel.engine.BeginResult
import kestrel.engine.ColumnResolver
import kestrel.engine.CommitResult
import kestrel.engine.collationFoldedKey
import kestrel.engine.collationFoldedUniquenessKey
import kestrel.engine.compareOrderKeyValues
import kestrel.engine.CreateIndexResult
import kestrel.engine.CreateSequenceResult
import kestrel.engine.CreateTableResult
import kestrel.engine.CreateTriggerResult
import kestrel.engine.CreateViewResult
import kestrel.engine.DeallocateResult
import kestrel.engine.DeleteResult
import kestrel.engine.DropIndexResult
import kestrel.engine.DropSequenceResult
import kestrel.engine.DropTableResult
import kestrel.engine.DropTriggerResult
import kestrel.engine.DropViewResult
import kestrel.engine.Evaluator
import kestrel.engine.ExplainResult
import kestrel.engine.GroupAggregation
import kestrel.engine.GroupingSetExpansion
import kestrel.engine.InsertResult
import kestrel.engine.MergeResult
import kestrel.engine.ParameterSubstitution
import kestrel.engine.PrepareResult
import kestrel.engine.ReleaseSavepointResult
import kestrel.engine.RollbackResult
import kestrel.engine.RollbackToSavepointResult
import kestrel.engine.SavepointResult
import kestrel.engine.SelectResult
import kestrel.engine.SequenceAccess
import kestrel.engine.StatementResult
import kestrel.engine.SubqueryRunner
import kestrel.engine.TriggerRowContext
import kestrel.engine.TruncateTableResult
import kestrel.engine.UpdateResult
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
import kestrel.sql.ast.CommonTableExpression
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
import kestrel.sql.ast.ExplainStatement
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FrameBound
import kestrel.sql.ast.FrameBoundType
import kestrel.sql.ast.FrameExclude
import kestrel.sql.ast.FrameUnit
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinClause
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.OnConflictClause
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.PreparableStatement
import kestrel.sql.ast.PrepareStatement
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
import kestrel.sql.ast.SelectStatement
import kestrel.sql.ast.SetOperationStatement
import kestrel.sql.ast.SetOperator
import kestrel.sql.ast.StarProjection
import kestrel.sql.ast.Statement
import kestrel.sql.ast.SubquerySource
import kestrel.sql.ast.TableSource
import kestrel.sql.ast.TriggerEvent
import kestrel.sql.ast.TriggerTiming
import kestrel.sql.ast.TruncateTableStatement
import kestrel.sql.ast.UnaryExpression
import kestrel.sql.ast.UpdateStatement
import kestrel.sql.ast.WindowExpression
import kestrel.sql.ast.WindowFrame
import kestrel.sql.ast.WindowFunctionKind
import kestrel.sql.ast.WithQuery
import kestrel.store.heap.RecordId
import kestrel.storage.Collation
import kestrel.storage.ColumnType
import kestrel.storage.DecimalColumns
import kestrel.storage.Row
import kestrel.storage.Table
import kestrel.storage.Value
import kestrel.storage.displayWith
import kestrel.storage.typeName

class DiskExecutorException(message: String) : KestrelException(message)

class DiskExecutor(private val catalog: DiskCatalog) : SequenceAccess {
    private val accessPlanner = DiskIndexAccessPlanner()
    private val activeCtes = mutableListOf<CteScope>()
    private val expandingViews = mutableSetOf<String>()

    private var triggerDepth = 0

    private val lastSequenceValues = HashMap<Sequence, Long>()

    override fun nextValue(sequenceName: String): Long {
        val sequence = catalog.sequence(sequenceName)
        return catalog.nextValue(sequenceName).also { lastSequenceValues[sequence] = it }
    }

    override fun currentValue(sequenceName: String): Long {
        val sequence = catalog.sequence(sequenceName)
        return lastSequenceValues[sequence]
            ?: throw DiskExecutorException("currval of sequence '$sequenceName' is not yet defined in this session - call NEXTVAL first")
    }

    private val preparedStatements = linkedMapOf<String, PreparableStatement>()

    val preparedStatementNames: List<String> get() = preparedStatements.keys.toList()

    private var explicitTxn: Long? = null

    val inTransaction: Boolean get() = explicitTxn != null

    private val savepoints = mutableListOf<Pair<String, Long>>()

    private val temporaryTableSnapshots = mutableListOf<Map<Table, List<Row>>>()

    private fun snapshotTemporaryTables(): Map<Table, List<Row>> =
        catalog.temporaryTableValues.associateWith { it.rows.toList() }

    private fun restoreTemporaryTables(snapshot: Map<Table, List<Row>>) {
        for ((table, rows) in snapshot) {
            table.rows.clear()
            table.rows.addAll(rows)
        }
    }

    fun execute(statement: Statement): StatementResult {
        if (explicitTxn != null && isDdl(statement)) {
            throw DiskExecutorException(
                "${statement::class.simpleName} cannot run inside an explicit transaction on the " +
                    "disk-backed executor - COMMIT or ROLLBACK first. Unlike the in-memory engine's " +
                    "whole-catalog snapshot/restore, an explicit transaction here is the storage " +
                    "engine's own WAL/2PL transaction (see BEGIN/COMMIT/ROLLBACK below), which has no " +
                    "way to undo a schema change recorded by DiskCatalog outside of it."
            )
        }
        return when (statement) {
            is CreateTableStatement -> executeCreateTable(statement)
            is DropTableStatement -> executeDropTable(statement)
            is TruncateTableStatement -> executeTruncate(statement)
            is AlterTableStatement -> executeAlterTable(statement)
            is CreateIndexStatement -> executeCreateIndex(statement)
            is DropIndexStatement -> executeDropIndex(statement)
            is CreateViewStatement -> executeCreateView(statement)
            is DropViewStatement -> executeDropView(statement)
            is CreateSequenceStatement -> executeCreateSequence(statement)
            is DropSequenceStatement -> executeDropSequence(statement)
            is AlterSequenceStatement -> executeAlterSequence(statement)
            is CreateTriggerStatement -> executeCreateTrigger(statement)
            is DropTriggerStatement -> executeDropTrigger(statement)
            is InsertStatement -> executeInsert(statement)
            is MergeStatement -> executeMerge(statement)
            is QueryStatement -> executeQuery(statement)
            is ExplainStatement -> executeExplain(statement)
            is UpdateStatement -> executeUpdate(statement)
            is DeleteStatement -> executeDelete(statement)
            is BeginStatement -> executeBegin()
            is CommitStatement -> executeCommit()
            is RollbackStatement -> executeRollback()
            is SavepointStatement -> executeSavepoint(statement)
            is ReleaseSavepointStatement -> executeReleaseSavepoint(statement)
            is RollbackToSavepointStatement -> executeRollbackToSavepoint(statement)
            is PrepareStatement -> executePrepare(statement)
            is ExecuteStatement -> execute(resolveExecute(statement))
            is DeallocateStatement -> executeDeallocate(statement)
            else -> throw DiskExecutorException(
                "${statement::class.simpleName} is not supported by the disk-backed executor yet"
            )
        }
    }

    private fun isDdl(statement: Statement): Boolean = when (statement) {
        is CreateTableStatement, is DropTableStatement, is AlterTableStatement,
        is CreateIndexStatement, is DropIndexStatement, is CreateViewStatement, is DropViewStatement,
        is CreateSequenceStatement, is DropSequenceStatement, is AlterSequenceStatement,
        is CreateTriggerStatement, is DropTriggerStatement -> true
        else -> false
    }

    private fun executePrepare(statement: PrepareStatement): StatementResult {
        val key = statement.name.lowercase()
        if (preparedStatements.containsKey(key)) {
            throw DiskExecutorException("prepared statement '${statement.name}' already exists")
        }
        preparedStatements[key] = statement.target
        return PrepareResult(statement.name)
    }

    fun resolveExecute(statement: ExecuteStatement): Statement {
        val target = preparedStatements[statement.name.lowercase()]
            ?: throw DiskExecutorException("no such prepared statement: ${statement.name}")
        val required = ParameterSubstitution.parameterCount(target)
        if (statement.arguments.size != required) {
            throw DiskExecutorException(
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
            throw DiskExecutorException("no such prepared statement: $name")
        }
        return DeallocateResult(name, all = false)
    }

    private fun executeBegin(): StatementResult {
        if (explicitTxn != null) throw DiskExecutorException("a transaction is already in progress")
        explicitTxn = catalog.beginTransaction()
        temporaryTableSnapshots.clear()
        temporaryTableSnapshots.add(snapshotTemporaryTables())
        return BeginResult
    }

    private fun executeCommit(): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("no transaction is in progress")
        catalog.commit(txn)
        explicitTxn = null
        savepoints.clear()
        temporaryTableSnapshots.clear()
        return CommitResult
    }

    private fun executeRollback(): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("no transaction is in progress")
        catalog.abort(txn)
        explicitTxn = null
        savepoints.clear()
        restoreTemporaryTables(temporaryTableSnapshots[0])
        temporaryTableSnapshots.clear()
        return RollbackResult
    }

    private fun executeSavepoint(statement: SavepointStatement): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("SAVEPOINT can only be used inside a transaction")
        savepoints.add(statement.name to catalog.savepoint(txn))
        temporaryTableSnapshots.add(snapshotTemporaryTables())
        return SavepointResult(statement.name)
    }

    private fun executeReleaseSavepoint(statement: ReleaseSavepointStatement): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("RELEASE SAVEPOINT can only be used inside a transaction")
        val index = indexOfLastSavepoint(statement.name)
        while (savepoints.size > index) {
            val (_, lsn) = savepoints.removeAt(savepoints.size - 1)
            catalog.discardSavepoint(txn, lsn)
            temporaryTableSnapshots.removeAt(temporaryTableSnapshots.size - 1)
        }
        return ReleaseSavepointResult(statement.name)
    }

    private fun executeRollbackToSavepoint(statement: RollbackToSavepointStatement): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("ROLLBACK TO SAVEPOINT can only be used inside a transaction")
        val index = indexOfLastSavepoint(statement.name)
        catalog.rollbackToSavepoint(txn, savepoints[index].second)
        restoreTemporaryTables(temporaryTableSnapshots[index + 1])
        while (savepoints.size > index + 1) {
            val (_, lsn) = savepoints.removeAt(savepoints.size - 1)
            catalog.discardSavepoint(txn, lsn)
            temporaryTableSnapshots.removeAt(temporaryTableSnapshots.size - 1)
        }
        return RollbackToSavepointResult(statement.name)
    }

    private fun indexOfLastSavepoint(name: String): Int {
        val index = savepoints.indexOfLast { it.first.equals(name, ignoreCase = true) }
        if (index < 0) throw DiskExecutorException("no such savepoint: $name")
        return index
    }

    private fun <T> withStatementTransaction(block: (Long) -> T): T {
        val active = explicitTxn
        if (active != null) return block(active)
        val txn = catalog.beginTransaction()
        return runInTransaction(txn) { block(txn) }
    }

    private fun <T> withTriggerAtomicity(tableName: String, txn: Long, triggerContext: TriggerRowContext?, block: () -> T): T {
        if (triggerContext != null || !catalog.hasTriggers(tableName)) return block()
        val savepointLsn = catalog.savepoint(txn)
        val result = try {
            block()
        } catch (failure: Exception) {
            catalog.rollbackToSavepoint(txn, savepointLsn)
            catalog.discardSavepoint(txn, savepointLsn)
            throw failure
        }
        catalog.discardSavepoint(txn, savepointLsn)
        return result
    }

    private fun composeResolver(base: ColumnResolver?, trigger: TriggerRowContext?): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value =
            if (trigger != null && trigger.matches(expression)) trigger.resolve(expression)
            else base?.resolve(expression) ?: throw DiskExecutorException("column '${expression.name}' is not allowed here")

        override fun declaredCollation(expression: ColumnExpression): Collation? =
            if (trigger != null && trigger.matches(expression)) trigger.declaredCollation(expression)
            else base?.declaredCollation(expression)
    }

    private fun rowResolver(schema: TableSchema, row: Row): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val index = schema.columnIndex(expression.name) ?: throw DiskExecutorException("no such column: ${expression.name}")
            return row.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
    }

    private fun evaluateReturning(schema: TableSchema, rows: List<Row>, projection: Projection, subqueries: SubqueryRunner): SelectResult {
        val columns = when (projection) {
            is StarProjection -> schema.columns.map { it.name }
            is ExpressionProjection -> projection.items.mapIndexed { index, item ->
                checkReturningColumns(item.expression, schema)
                columnLabel(item, index)
            }
        }
        val resultRows = rows.map { row ->
            when (projection) {
                is StarProjection -> row.values.toList()
                is ExpressionProjection -> {
                    val evaluator = Evaluator(rowResolver(schema, row), subqueries)
                    projection.items.map { evaluator.evaluate(it.expression) }
                }
            }
        }
        return SelectResult(columns, resultRows)
    }

    private fun fireRowTriggers(schema: TableSchema, timing: TriggerTiming, event: TriggerEvent, rows: List<Pair<Row?, Row?>>, txn: Long) {
        val triggers = catalog.triggersOn(schema.name, timing, event)
        if (triggers.isEmpty()) return
        val subqueries = subqueryRunnerFor(txn)
        for ((oldRow, newRow) in rows) {
            val context = TriggerRowContext(schema, newRow, oldRow)
            for (trigger in triggers) {
                val whenClause = trigger.whenClause
                if (whenClause != null) {
                    val evaluator = Evaluator(composeResolver(null, context), subqueries)
                    if (!isTrue(evaluator.evaluate(whenClause))) continue
                }
                runTriggerBody(trigger, context, txn)
            }
        }
    }

    private fun runTriggerBody(trigger: TriggerDefinition, context: TriggerRowContext, txn: Long) {
        if (triggerDepth >= MAX_TRIGGER_DEPTH) {
            throw DiskExecutorException(
                "trigger recursion limit ($MAX_TRIGGER_DEPTH) exceeded while firing '${trigger.name}' - " +
                    "check for a trigger whose body ends up re-triggering itself"
            )
        }
        triggerDepth++
        try {
            for (bodyStatement in trigger.body) {
                when (bodyStatement) {
                    is InsertStatement -> {
                        val table = catalog.table(bodyStatement.tableName)
                        executeInsertInTxn(bodyStatement, table, table.schema, txn, context)
                    }
                    is UpdateStatement -> {
                        val table = catalog.table(bodyStatement.tableName)
                        executeUpdateInTxn(bodyStatement, buildAssignments(table.schema, bodyStatement), table, table.schema, txn, context)
                    }
                    is DeleteStatement -> {
                        val table = catalog.table(bodyStatement.tableName)
                        executeDeleteInTxn(bodyStatement, table, table.schema, txn, context)
                    }
                    else -> error("unreachable: trigger bodies only ever contain INSERT/UPDATE/DELETE")
                }
            }
        } finally {
            triggerDepth--
        }
    }

    private fun buildAssignments(schema: TableSchema, statement: UpdateStatement): List<Pair<Int, Expression?>> =
        statement.assignments.map { assignment ->
            val index = schema.columnIndex(assignment.columnName)
                ?: throw DiskExecutorException("no such column: ${assignment.columnName} in table ${schema.name}")
            rejectGeneratedAssignment(schema.columns[index], explicitValueGiven = assignment.value != null)
            index to assignment.value
        }

    private fun executeCreateIndex(statement: CreateIndexStatement): StatementResult {
        catalog.createIndex(statement.tableName, statement.indexName, statement.columnNames, statement.unique)
        return CreateIndexResult(statement.indexName)
    }

    private fun executeDropIndex(statement: DropIndexStatement): StatementResult {
        catalog.dropIndex(statement.indexName)
        return DropIndexResult(statement.indexName)
    }

    private fun executeCreateSequence(statement: CreateSequenceStatement): StatementResult {
        val incrementBy = statement.incrementBy ?: 1L
        if (incrementBy == 0L) throw DiskExecutorException("sequence '${statement.sequenceName}' INCREMENT BY cannot be zero")
        val ascending = incrementBy > 0
        val minValue = statement.minValue ?: if (ascending) 1L else Long.MIN_VALUE
        val maxValue = statement.maxValue ?: if (ascending) Long.MAX_VALUE else -1L
        if (minValue > maxValue) {
            throw DiskExecutorException("sequence '${statement.sequenceName}' MINVALUE ($minValue) must not exceed MAXVALUE ($maxValue)")
        }
        val startValue = statement.startValue ?: if (ascending) minValue else maxValue
        if (startValue < minValue || startValue > maxValue) {
            throw DiskExecutorException(
                "START WITH ($startValue) must be between MINVALUE ($minValue) and MAXVALUE ($maxValue)"
            )
        }
        catalog.createSequence(SequenceDefinition(statement.sequenceName, incrementBy, minValue, maxValue, startValue, statement.cycle))
        return CreateSequenceResult(statement.sequenceName)
    }

    private fun executeDropSequence(statement: DropSequenceStatement): StatementResult {
        catalog.dropSequence(statement.sequenceName)
        return DropSequenceResult(statement.sequenceName)
    }

    private fun executeAlterSequence(statement: AlterSequenceStatement): StatementResult {
        val existing = catalog.sequence(statement.sequenceName).definition
        val incrementBy = statement.incrementBy ?: existing.incrementBy
        if (incrementBy == 0L) throw DiskExecutorException("sequence '${statement.sequenceName}' INCREMENT BY cannot be zero")
        val ascending = incrementBy > 0
        val minValue = when {
            statement.clearMinValue -> if (ascending) 1L else Long.MIN_VALUE
            statement.minValue != null -> statement.minValue
            else -> existing.minValue
        }
        val maxValue = when {
            statement.clearMaxValue -> if (ascending) Long.MAX_VALUE else -1L
            statement.maxValue != null -> statement.maxValue
            else -> existing.maxValue
        }
        if (minValue > maxValue) {
            throw DiskExecutorException("sequence '${statement.sequenceName}' MINVALUE ($minValue) must not exceed MAXVALUE ($maxValue)")
        }
        val cycle = statement.cycle ?: existing.cycle
        val newDefinition = SequenceDefinition(statement.sequenceName, incrementBy, minValue, maxValue, existing.startValue, cycle)
        val restartAt = if (statement.restart) (statement.restartWith ?: existing.startValue) else null
        if (restartAt != null && (restartAt < minValue || restartAt > maxValue)) {
            throw DiskExecutorException("RESTART value ($restartAt) must be between MINVALUE ($minValue) and MAXVALUE ($maxValue)")
        }
        catalog.alterSequence(statement.sequenceName, newDefinition, restartAt)
        return AlterSequenceResult(statement.sequenceName)
    }

    private fun executeCreateView(statement: CreateViewStatement): StatementResult {
        val txn = catalog.beginTransaction()
        runInTransaction(txn) { runQuery(statement.query, txn, outer = null) }
        catalog.createView(statement.viewName, statement.query, statement.orReplace)
        return CreateViewResult(statement.viewName)
    }

    private fun executeDropView(statement: DropViewStatement): StatementResult {
        catalog.dropView(statement.viewName, cascade = statement.mode == DropMode.Cascade)
        return DropViewResult(statement.viewName)
    }

    private fun executeCreateTrigger(statement: CreateTriggerStatement): StatementResult {
        val schema = catalog.table(statement.tableName).schema
        statement.whenClause?.let { whenClause ->
            requireNoSubquery(whenClause)
            validateTriggerRowReferences(whenClause, statement.event, schema)
        }
        for (bodyStatement in statement.body) validateTriggerBodyStatementTarget(bodyStatement)
        catalog.createTrigger(
            TriggerDefinition(statement.triggerName, statement.timing, statement.event, statement.tableName, statement.whenClause, statement.body)
        )
        return CreateTriggerResult(statement.triggerName)
    }

    private fun validateTriggerBodyStatementTarget(statement: Statement) {
        when (statement) {
            is InsertStatement -> { catalog.table(statement.tableName); rejectReturning(statement.returning) }
            is UpdateStatement -> { catalog.table(statement.tableName); rejectReturning(statement.returning) }
            is DeleteStatement -> { catalog.table(statement.tableName); rejectReturning(statement.returning) }
            else -> throw DiskExecutorException("trigger body only supports INSERT, UPDATE, or DELETE statements")
        }
    }

    private fun rejectReturning(returning: Projection?) {
        if (returning != null) {
            throw DiskExecutorException("RETURNING is not allowed in a trigger body statement - its result would never be visible to anything")
        }
    }

    private fun validateTriggerRowReferences(expression: Expression, event: TriggerEvent, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> {
                val qualifier = expression.qualifier
                    ?: throw DiskExecutorException("column '${expression.name}' in trigger WHEN clause must be qualified with NEW or OLD")
                val isNew = qualifier.equals("new", ignoreCase = true)
                val isOld = qualifier.equals("old", ignoreCase = true)
                if (!isNew && !isOld) throw DiskExecutorException("no such table or alias: $qualifier")
                val availableForEvent = when (event) {
                    TriggerEvent.Insert -> isNew
                    TriggerEvent.Delete -> isOld
                    TriggerEvent.Update -> true
                }
                if (!availableForEvent) {
                    throw DiskExecutorException("${if (isNew) "NEW" else "OLD"} is not available in a trigger ON ${event.name.uppercase()}")
                }
                if (schema.columnIndex(expression.name) == null) {
                    throw DiskExecutorException("no such column: ${expression.name}")
                }
            }
            is BinaryExpression -> {
                validateTriggerRowReferences(expression.left, event, schema)
                validateTriggerRowReferences(expression.right, event, schema)
            }
            is UnaryExpression -> validateTriggerRowReferences(expression.operand, event, schema)
            is CallExpression -> expression.arguments.forEach { validateTriggerRowReferences(it, event, schema) }
            is IsNullExpression -> validateTriggerRowReferences(expression.operand, event, schema)
            is InExpression -> {
                validateTriggerRowReferences(expression.operand, event, schema)
                expression.values.forEach { validateTriggerRowReferences(it, event, schema) }
            }
            is BetweenExpression -> {
                validateTriggerRowReferences(expression.operand, event, schema)
                validateTriggerRowReferences(expression.low, event, schema)
                validateTriggerRowReferences(expression.high, event, schema)
            }
            is LikeExpression -> {
                validateTriggerRowReferences(expression.operand, event, schema)
                validateTriggerRowReferences(expression.pattern, event, schema)
            }
            is CaseExpression -> {
                expression.operand?.let { validateTriggerRowReferences(it, event, schema) }
                expression.branches.forEach {
                    validateTriggerRowReferences(it.condition, event, schema)
                    validateTriggerRowReferences(it.result, event, schema)
                }
                expression.elseExpression?.let { validateTriggerRowReferences(it, event, schema) }
            }
            is CastExpression -> validateTriggerRowReferences(expression.operand, event, schema)
            is CollateExpression -> validateTriggerRowReferences(expression.operand, event, schema)
            is LiteralExpression -> Unit
            is FunctionExpression, is WindowExpression, is ScalarSubqueryExpression,
            is ExistsExpression, is InSubqueryExpression,
            is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression ->
                error("unreachable: banned by requireNoSubquery")
        }
    }

    private fun executeDropTrigger(statement: DropTriggerStatement): StatementResult {
        catalog.dropTrigger(statement.triggerName)
        return DropTriggerResult(statement.triggerName)
    }

    private fun executeCreateTable(statement: CreateTableStatement): StatementResult {
        if (statement.temporary) return executeCreateTemporaryTable(statement)
        if (statement.columns.count { it.primaryKey } > 1 ||
            (statement.compositePrimaryKey.isNotEmpty() && statement.columns.any { it.primaryKey })
        ) {
            throw DiskExecutorException("table ${statement.tableName} declares more than one primary key")
        }
        validateUniqueDefinitions(statement)
        validateCheckDefinitions(statement)
        val columns = applyCompositePrimaryKey(statement)
        validateForeignKeyDefinitions(statement, columns)
        for (column in columns) {
            when (val default = column.default) {
                is NextValueExpression -> validateNextValueDefault(default, column)
                is CurrentValueExpression -> throw DiskExecutorException("DEFAULT expression cannot contain CURRVAL")
                else -> Unit
            }
        }
        validateGeneratedDefinitions(columns)
        catalog.createTable(
            statement.tableName, columns, statement.compositePrimaryKey, statement.uniqueConstraints,
            statement.checks, statement.foreignKeys
        )
        return CreateTableResult(statement.tableName)
    }

    private fun executeCreateTemporaryTable(statement: CreateTableStatement): StatementResult {
        if (statement.foreignKeys.isNotEmpty()) {
            throw DiskExecutorException("FOREIGN KEY on a temporary table is not supported by the disk-backed executor yet")
        }
        if (statement.columns.count { it.primaryKey } > 1 ||
            (statement.compositePrimaryKey.isNotEmpty() && statement.columns.any { it.primaryKey })
        ) {
            throw DiskExecutorException("table ${statement.tableName} declares more than one primary key")
        }
        validateUniqueDefinitions(statement)
        validateCheckDefinitions(statement)
        val columns = applyCompositePrimaryKey(statement)
        for (column in columns) {
            when (val default = column.default) {
                is NextValueExpression -> validateNextValueDefault(default, column)
                is CurrentValueExpression -> throw DiskExecutorException("DEFAULT expression cannot contain CURRVAL")
                else -> Unit
            }
        }
        validateGeneratedDefinitions(columns)
        val schema = TableSchema(
            statement.tableName, columns,
            checks = statement.checks,
            uniqueConstraints = statement.uniqueConstraints,
            compositePrimaryKey = statement.compositePrimaryKey,
            temporary = true
        )
        catalog.createTemporaryTable(schema)
        return CreateTableResult(statement.tableName)
    }

    private fun validateNextValueDefault(default: NextValueExpression, column: Column) {
        catalog.sequence(default.sequenceName)
        if (column.type != ColumnType.Integer) {
            throw DiskExecutorException("NEXTVAL can only be used as a DEFAULT for an INTEGER column, but ${column.name} is ${column.type.displayName}")
        }
    }

    private fun validateForeignKeyDefinitions(statement: CreateTableStatement, columns: List<Column>) {
        for (fk in statement.foreignKeys) {
            if (fk.columns.size != fk.referencedColumns.size) {
                throw DiskExecutorException(
                    "FOREIGN KEY (${fk.columns.joinToString(", ")}) column count does not match " +
                        "REFERENCES ${fk.referencedTable}(${fk.referencedColumns.joinToString(", ")})"
                )
            }
            for (columnName in fk.columns) {
                if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw DiskExecutorException("FOREIGN KEY refers to unknown column: $columnName")
                }
            }
            val selfReference = fk.referencedTable.equals(statement.tableName, ignoreCase = true)
            val hasEnforcedUniqueness = if (selfReference) {
                for (columnName in fk.referencedColumns) {
                    if (columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                        throw DiskExecutorException("FOREIGN KEY references unknown column: ${fk.referencedTable}.$columnName")
                    }
                }
                val selfSchema = TableSchema(
                    statement.tableName, columns,
                    uniqueConstraints = statement.uniqueConstraints,
                    compositePrimaryKey = statement.compositePrimaryKey
                )
                selfSchema.hasEnforcedUniqueness(fk.referencedColumns)
            } else {
                val referencedTable = catalog.tableOrNull(fk.referencedTable)
                    ?: throw DiskExecutorException("FOREIGN KEY references unknown table: ${fk.referencedTable}")
                for (columnName in fk.referencedColumns) {
                    if (referencedTable.schema.column(columnName) == null) {
                        throw DiskExecutorException("FOREIGN KEY references unknown column: ${fk.referencedTable}.$columnName")
                    }
                }
                referencedTable.schema.hasEnforcedUniqueness(fk.referencedColumns)
            }
            if (!hasEnforcedUniqueness) {
                throw DiskExecutorException(
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

    private fun checkColumns(expression: Expression, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> if (schema.columnIndex(expression.name) == null) {
                throw DiskExecutorException("no such column: ${expression.name}")
            }
            is BinaryExpression -> {
                checkColumns(expression.left, schema)
                checkColumns(expression.right, schema)
            }
            is UnaryExpression -> checkColumns(expression.operand, schema)
            is FunctionExpression -> throw DiskExecutorException("aggregate functions are not allowed here")
            is WindowExpression -> throw DiskExecutorException("window functions are not allowed here")
            is LiteralExpression -> Unit
            is CallExpression -> expression.arguments.forEach { checkColumns(it, schema) }
            is IsNullExpression -> checkColumns(expression.operand, schema)
            is InExpression -> {
                checkColumns(expression.operand, schema)
                expression.values.forEach { checkColumns(it, schema) }
            }
            is BetweenExpression -> {
                checkColumns(expression.operand, schema)
                checkColumns(expression.low, schema)
                checkColumns(expression.high, schema)
            }
            is LikeExpression -> {
                checkColumns(expression.operand, schema)
                checkColumns(expression.pattern, schema)
            }
            is CaseExpression -> {
                expression.operand?.let { checkColumns(it, schema) }
                expression.branches.forEach {
                    checkColumns(it.condition, schema)
                    checkColumns(it.result, schema)
                }
                expression.elseExpression?.let { checkColumns(it, schema) }
            }
            is ScalarSubqueryExpression -> Unit
            is ExistsExpression -> Unit
            is InSubqueryExpression -> checkColumns(expression.operand, schema)
            is CastExpression -> checkColumns(expression.operand, schema)
            is CollateExpression -> checkColumns(expression.operand, schema)
            is NextValueExpression, is CurrentValueExpression ->
                throw DiskExecutorException("NEXTVAL/CURRVAL are not allowed in a CHECK constraint or GENERATED column expression")
            is MergeActionExpression -> throw DiskExecutorException("MERGE_ACTION() is not allowed in a CHECK constraint or GENERATED column expression")
            is ParameterExpression -> throw DiskExecutorException("a parameter reference is not allowed in a CHECK constraint or GENERATED column expression")
        }
    }

    private fun checkReturningColumns(expression: Expression, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> if (schema.columnIndex(expression.name) == null) {
                throw DiskExecutorException("no such column: ${expression.name}")
            }
            is BinaryExpression -> {
                checkReturningColumns(expression.left, schema)
                checkReturningColumns(expression.right, schema)
            }
            is UnaryExpression -> checkReturningColumns(expression.operand, schema)
            is FunctionExpression -> throw DiskExecutorException("aggregate functions are not allowed here")
            is WindowExpression -> throw DiskExecutorException("window functions are not allowed here")
            is LiteralExpression -> Unit
            is CallExpression -> expression.arguments.forEach { checkReturningColumns(it, schema) }
            is IsNullExpression -> checkReturningColumns(expression.operand, schema)
            is InExpression -> {
                checkReturningColumns(expression.operand, schema)
                expression.values.forEach { checkReturningColumns(it, schema) }
            }
            is BetweenExpression -> {
                checkReturningColumns(expression.operand, schema)
                checkReturningColumns(expression.low, schema)
                checkReturningColumns(expression.high, schema)
            }
            is LikeExpression -> {
                checkReturningColumns(expression.operand, schema)
                checkReturningColumns(expression.pattern, schema)
            }
            is CaseExpression -> {
                expression.operand?.let { checkReturningColumns(it, schema) }
                expression.branches.forEach {
                    checkReturningColumns(it.condition, schema)
                    checkReturningColumns(it.result, schema)
                }
                expression.elseExpression?.let { checkReturningColumns(it, schema) }
            }
            is ScalarSubqueryExpression -> Unit
            is ExistsExpression -> Unit
            is InSubqueryExpression -> checkReturningColumns(expression.operand, schema)
            is CastExpression -> checkReturningColumns(expression.operand, schema)
            is CollateExpression -> checkReturningColumns(expression.operand, schema)
            is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> Unit
        }
    }

    private fun requireNoSubquery(expression: Expression) {
        when (expression) {
            is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression ->
                throw DiskExecutorException("CHECK constraints and GENERATED column expressions cannot contain subqueries")
            is FunctionExpression -> throw DiskExecutorException("aggregate functions are not allowed in CHECK constraints")
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
            is WindowExpression -> throw DiskExecutorException("window functions are not allowed in CHECK constraints")
            is NextValueExpression, is CurrentValueExpression ->
                throw DiskExecutorException("NEXTVAL/CURRVAL are not allowed in a CHECK constraint or GENERATED column expression")
            is MergeActionExpression -> throw DiskExecutorException("MERGE_ACTION() is not allowed in a CHECK constraint or GENERATED column expression")
            is ParameterExpression -> throw DiskExecutorException("a parameter reference is not allowed in a CHECK constraint or GENERATED column expression")
            is ColumnExpression, is LiteralExpression -> Unit
        }
    }

    private fun validateGeneratedDefinitions(columns: List<Column>) {
        val schema = TableSchema("", columns)
        val generatedNames = columns.filter { it.generated != null }.map { it.name.lowercase() }.toSet()
        for (column in columns) {
            val generated = column.generated ?: continue
            if (column.default != null) {
                throw DiskExecutorException("column ${column.name} cannot declare both DEFAULT and GENERATED ALWAYS AS")
            }
            checkColumns(generated, schema)
            requireNoSubquery(generated)
            requireNoGeneratedColumnReference(generated, generatedNames, column.name)
        }
    }

    private fun requireNoGeneratedColumnReference(expression: Expression, generatedNames: Set<String>, ownerColumn: String) {
        when (expression) {
            is ColumnExpression -> if (expression.name.lowercase() in generatedNames) {
                throw DiskExecutorException(
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
            is FunctionExpression, is WindowExpression, is ScalarSubqueryExpression, is ExistsExpression,
            is InSubqueryExpression, is NextValueExpression, is CurrentValueExpression, is MergeActionExpression,
            is ParameterExpression -> Unit
        }
    }

    private fun applyGeneratedColumns(schema: TableSchema, values: MutableList<Value>, subqueries: SubqueryRunner) {
        if (schema.columns.none { it.generated != null }) return
        val evaluator = Evaluator(schema, Row(values), subqueries)
        for ((index, column) in schema.columns.withIndex()) {
            val generated = column.generated ?: continue
            values[index] = coerceForColumn(evaluator.evaluate(generated), column)
        }
    }

    private fun rejectGeneratedAssignment(column: Column, explicitValueGiven: Boolean) {
        if (explicitValueGiven && column.generated != null) {
            throw DiskExecutorException("cannot assign an explicit value to GENERATED column ${column.name}")
        }
    }

    private fun enforceChecks(schema: TableSchema, row: Row, subqueries: SubqueryRunner) {
        for (check in schema.checks) {
            val result = Evaluator(schema, row, subqueries).evaluate(check.expression)
            if (result is Value.BooleanValue && !result.value) {
                val label = check.name?.let { " $it" } ?: ""
                throw DiskExecutorException("CHECK constraint$label failed: ${schema.name}")
            }
        }
    }

    private fun validateTemporaryNotNull(schema: TableSchema, values: List<Value>) {
        for ((index, column) in schema.columns.withIndex()) {
            if (column.notNull && values[index] is Value.NullValue) {
                throw DiskExecutorException("NOT NULL constraint failed: ${schema.name}.${column.name}")
            }
        }
    }

    private fun enforceTemporaryPrimaryKeyUniqueness(schema: TableSchema, rows: List<Row>) {
        val indices = schema.primaryKeyIndices
        if (indices.isEmpty()) return
        val seen = HashSet<List<Value>>()
        for (row in rows) {
            val key = collationFoldedUniquenessKey(schema, indices, indices.map { row.values[it] })
            if (!seen.add(key)) {
                val columnList = indices.joinToString(", ") { schema.columns[it].name }
                throw DiskExecutorException("UNIQUE constraint failed: ${schema.name}($columnList)")
            }
        }
    }

    private fun enforceTemporaryUniqueConstraints(schema: TableSchema, rows: List<Row>) {
        for (unique in schema.uniqueConstraints) {
            val columnIndices = unique.columns.map { schema.columnIndex(it)!! }
            val seen = HashSet<List<Value>>()
            for (row in rows) {
                val rawKey = columnIndices.map { row.values[it] }
                if (rawKey.any { it is Value.NullValue }) continue
                if (!seen.add(collationFoldedUniquenessKey(schema, columnIndices, rawKey))) {
                    val label = unique.name?.let { " $it" } ?: ""
                    val columnList = unique.columns.joinToString(", ")
                    throw DiskExecutorException("UNIQUE constraint$label failed: ${schema.name}($columnList)")
                }
            }
        }
    }

    private fun validateUniqueDefinitions(statement: CreateTableStatement) {
        for (unique in statement.uniqueConstraints) {
            for (columnName in unique.columns) {
                if (statement.columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw DiskExecutorException("UNIQUE constraint refers to unknown column: $columnName")
                }
            }
        }
    }

    private fun applyCompositePrimaryKey(statement: CreateTableStatement): List<Column> {
        if (statement.compositePrimaryKey.isEmpty()) return statement.columns
        val declared = statement.compositePrimaryKey.map { it.lowercase() }.toSet()
        if (declared.size != statement.compositePrimaryKey.size) {
            throw DiskExecutorException("table '${statement.tableName}' declares a duplicate column in its PRIMARY KEY")
        }
        for (name in statement.compositePrimaryKey) {
            if (statement.columns.none { it.name.equals(name, ignoreCase = true) }) {
                throw DiskExecutorException("PRIMARY KEY refers to unknown column: $name")
            }
        }
        return statement.columns.map { column ->
            if (column.name.lowercase() in declared) column.copy(notNull = true) else column
        }
    }

    private fun executeDropTable(statement: DropTableStatement): StatementResult {
        if (catalog.temporaryTableOrNull(statement.tableName) != null) {
            catalog.dropTemporaryTable(statement.tableName)
        } else {
            catalog.dropTable(statement.tableName, cascade = statement.mode == DropMode.Cascade)
        }
        return DropTableResult(statement.tableName)
    }

    private fun executeTruncate(statement: TruncateTableStatement): StatementResult {
        val truncated = withStatementTransaction { txn ->
            catalog.truncateTables(txn, statement.tableNames, statement.mode == DropMode.Cascade)
        }
        if (statement.restartIdentity) {
            for (name in truncated) restartOwnedSequences(catalog.table(name).schema)
        }
        return TruncateTableResult(truncated)
    }

    private fun restartOwnedSequences(schema: TableSchema) {
        for (column in schema.columns) {
            val default = column.default
            if (default is NextValueExpression) {
                val sequence = catalog.sequenceOrNull(default.sequenceName) ?: continue
                catalog.alterSequence(default.sequenceName, sequence.definition, restartAt = sequence.definition.startValue)
            }
        }
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

    private fun executeAddCheckConstraint(tableName: String, check: CheckConstraint) {
        val table = catalog.table(tableName)
        val schema = table.schema
        check.name?.let { requireUniqueConstraintName(schema, it) }
        checkColumns(check.expression, schema)
        requireNoSubquery(check.expression)
        val probeTxn = catalog.beginTransaction()
        runInTransaction(probeTxn) {
            val subqueries = subqueryRunnerFor(probeTxn)
            for ((_, row) in table.scan(probeTxn)) {
                val result = Evaluator(schema, row, subqueries).evaluate(check.expression)
                if (result is Value.BooleanValue && !result.value) {
                    val label = check.name?.let { " $it" } ?: ""
                    throw DiskExecutorException("CHECK constraint$label failed: an existing row in $tableName violates it")
                }
            }
        }
        catalog.addCheckConstraint(tableName, check)
    }

    private fun executeAddUniqueConstraint(tableName: String, unique: UniqueConstraint) {
        val table = catalog.table(tableName)
        val schema = table.schema
        unique.name?.let { requireUniqueConstraintName(schema, it) }
        for (columnName in unique.columns) {
            if (schema.columnIndex(columnName) == null) {
                throw DiskExecutorException("UNIQUE constraint refers to unknown column: $columnName")
            }
        }
        val columnIndices = unique.columns.map { schema.columnIndex(it)!! }
        val probeTxn = catalog.beginTransaction()
        runInTransaction(probeTxn) {
            val seen = HashSet<List<Value>>()
            for ((_, row) in table.scan(probeTxn)) {
                val rawKey = columnIndices.map { row.values[it] }
                if (rawKey.any { it is Value.NullValue }) continue
                if (!seen.add(collationFoldedUniquenessKey(schema, columnIndices, rawKey))) {
                    val label = unique.name?.let { " $it" } ?: ""
                    throw DiskExecutorException(
                        "UNIQUE constraint$label failed: an existing row combination in " +
                            "$tableName(${unique.columns.joinToString(", ")}) is already duplicated"
                    )
                }
            }
        }
        catalog.addUniqueConstraint(tableName, unique)
    }

    private fun executeAddForeignKeyConstraint(tableName: String, foreignKey: ForeignKeyConstraint) {
        val table = catalog.table(tableName)
        val schema = table.schema
        foreignKey.name?.let { requireUniqueConstraintName(schema, it) }
        if (foreignKey.columns.size != foreignKey.referencedColumns.size) {
            throw DiskExecutorException(
                "FOREIGN KEY (${foreignKey.columns.joinToString(", ")}) column count does not match " +
                    "REFERENCES ${foreignKey.referencedTable}(${foreignKey.referencedColumns.joinToString(", ")})"
            )
        }
        for (columnName in foreignKey.columns) {
            if (schema.columnIndex(columnName) == null) {
                throw DiskExecutorException("FOREIGN KEY refers to unknown column: $columnName")
            }
        }
        val selfReference = foreignKey.referencedTable.equals(tableName, ignoreCase = true)
        val referencedSchema = if (selfReference) {
            schema
        } else {
            catalog.tableOrNull(foreignKey.referencedTable)?.schema
                ?: throw DiskExecutorException("FOREIGN KEY references unknown table: ${foreignKey.referencedTable}")
        }
        for (columnName in foreignKey.referencedColumns) {
            if (referencedSchema.column(columnName) == null) {
                throw DiskExecutorException("FOREIGN KEY references unknown column: ${foreignKey.referencedTable}.$columnName")
            }
        }
        if (!referencedSchema.hasEnforcedUniqueness(foreignKey.referencedColumns)) {
            throw DiskExecutorException(
                "FOREIGN KEY must reference a PRIMARY KEY or UNIQUE column(s), but " +
                    "${foreignKey.referencedTable}(${foreignKey.referencedColumns.joinToString(", ")}) is not one"
            )
        }
        val tempSchema = TableSchema(
            schema.name, schema.columns, schema.foreignKeys + foreignKey, schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
        )
        val probeTxn = catalog.beginTransaction()
        runInTransaction(probeTxn) {
            for ((_, row) in table.scan(probeTxn)) {
                DiskForeignKeyEnforcer.validateRow(catalog, probeTxn, tempSchema, row.values)
            }
        }
        catalog.addForeignKey(tableName, foreignKey)
    }

    private fun executeChangeColumnType(tableName: String, action: ChangeColumnType) {
        val table = catalog.table(tableName)
        val schema = table.schema
        val columnIndex = schema.columnIndex(action.columnName)
            ?: throw DiskExecutorException("no such column: ${action.columnName} in table $tableName")
        val column = schema.columns[columnIndex]
        if (column.generated != null) {
            throw DiskExecutorException(
                "cannot change the type of GENERATED column ${action.columnName} in $tableName; drop and re-add it instead"
            )
        }
        if (schema.primaryKeyColumns.any { it.equals(action.columnName, ignoreCase = true) }) {
            throw DiskExecutorException(
                "cannot change the type of column ${action.columnName} because it is part of the PRIMARY KEY of $tableName"
            )
        }
        if (schema.foreignKeys.any { fk -> fk.columns.any { it.equals(action.columnName, ignoreCase = true) } }) {
            throw DiskExecutorException(
                "cannot change the type of column ${action.columnName} because it is used by one of ${tableName}'s own " +
                    "FOREIGN KEY constraints; drop the constraint first"
            )
        }
        val referencingTables = catalog.tableNames.filter { other ->
            !other.equals(tableName, ignoreCase = true) &&
                catalog.tableOrNull(other)?.schema?.foreignKeys?.any { fk ->
                    fk.referencedTable.equals(tableName, ignoreCase = true) &&
                        fk.referencedColumns.any { it.equals(action.columnName, ignoreCase = true) }
                } == true
        }
        if (referencingTables.isNotEmpty()) {
            throw DiskExecutorException(
                "cannot change the type of column ${action.columnName} because table(s) ${referencingTables.joinToString(", ")} " +
                    "have a FOREIGN KEY referencing it; drop the constraint first"
            )
        }
        if (schema.uniqueConstraints.any { unique -> unique.columns.any { it.equals(action.columnName, ignoreCase = true) } }) {
            throw DiskExecutorException(
                "cannot change the type of column ${action.columnName} because it is used by a UNIQUE constraint of $tableName; " +
                    "drop the constraint first"
            )
        }
        val dependentIndexes = table.secondaryIndexes.filter { it.columnNames.any { c -> c.equals(action.columnName, ignoreCase = true) } }
        if (dependentIndexes.isNotEmpty()) {
            throw DiskExecutorException(
                "cannot change the type of column ${action.columnName} because index(es) " +
                    "${dependentIndexes.joinToString(", ") { it.name }} are defined on it; drop the index first"
            )
        }

        val newColumn = column.copy(type = action.newType, precision = action.precision, scale = action.scale)
        newColumn.default?.let { default ->
            try {
                coerceForColumn(Evaluator().evaluate(default), newColumn)
            } catch (e: KestrelException) {
                throw DiskExecutorException(
                    "cannot change the type of column ${action.columnName}: its DEFAULT expression is not compatible " +
                        "with ${action.newType.displayWith(action.precision, action.scale)} (${e.message})"
                )
            }
        }
        val newSchema = TableSchema(
            schema.name, schema.columns.toMutableList().also { it[columnIndex] = newColumn }, schema.foreignKeys,
            schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey
        )

        val using = action.using ?: ColumnExpression(action.columnName)
        catalog.changeColumnType(tableName, action.columnName, newColumn) { row ->
            val value = coerceForColumn(Evaluator(schema, row).evaluate(using), newColumn)
            if (newColumn.notNull && value is Value.NullValue) {
                throw DiskExecutorException("NOT NULL constraint failed: $tableName.${action.columnName}")
            }
            val newRow = Row(row.values.toMutableList().also { it[columnIndex] = value })
            for (check in newSchema.checks) {
                val result = Evaluator(newSchema, newRow).evaluate(check.expression)
                if (result is Value.BooleanValue && !result.value) {
                    val label = check.name?.let { " $it" } ?: ""
                    throw DiskExecutorException("CHECK constraint$label failed: $tableName")
                }
            }
            value
        }
    }

    private fun requireUniqueConstraintName(schema: TableSchema, name: String) {
        val taken = schema.foreignKeys.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.checks.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.uniqueConstraints.any { it.name?.equals(name, ignoreCase = true) == true }
        if (taken) throw DiskExecutorException("constraint '$name' already exists on table ${schema.name}")
    }

    private fun executeAddColumn(tableName: String, column: Column) {
        val table = catalog.table(tableName)
        if (column.generated != null) {
            validateAddGeneratedColumn(table.schema, column)
            catalog.addColumn(tableName, column) { row -> addGeneratedColumnValue(table.schema, column, row) }
            return
        }
        column.default?.let { validateConstantDefault(it) }
        val probeTxn = catalog.beginTransaction()
        val hasRows = runInTransaction(probeTxn) { table.scan(probeTxn).isNotEmpty() }
        if (column.notNull && column.default == null && hasRows) {
            throw DiskExecutorException(
                "cannot add NOT NULL column ${column.name} to table $tableName with existing rows without a DEFAULT value"
            )
        }
        val defaultValue = column.default?.let { coerceForColumn(Evaluator().evaluate(it), column) } ?: Value.NullValue
        catalog.addColumn(tableName, column) { defaultValue }
    }

    private fun validateAddGeneratedColumn(schema: TableSchema, column: Column) {
        val generated = column.generated!!
        checkColumns(generated, schema)
        requireNoSubquery(generated)
        val generatedNames = schema.columns.filter { it.generated != null }.map { it.name.lowercase() }.toSet()
        requireNoGeneratedColumnReference(generated, generatedNames, column.name)
    }

    private fun addGeneratedColumnValue(schema: TableSchema, column: Column, row: Row): Value {
        val value = coerceForColumn(Evaluator(schema, row).evaluate(column.generated!!), column)
        if (column.notNull && value is Value.NullValue) {
            throw DiskExecutorException("NOT NULL constraint failed: ${schema.name}.${column.name}")
        }
        return value
    }

    private fun validateConstantDefault(expression: Expression) {
        when (expression) {
            is ColumnExpression -> throw DiskExecutorException("DEFAULT expression cannot reference a column")
            is ScalarSubqueryExpression, is ExistsExpression, is InSubqueryExpression ->
                throw DiskExecutorException("DEFAULT expression cannot contain a subquery")
            is FunctionExpression -> throw DiskExecutorException("DEFAULT expression cannot contain an aggregate function")
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
            is WindowExpression -> throw DiskExecutorException("DEFAULT expression cannot contain a window function")
            is NextValueExpression ->
                throw DiskExecutorException(
                    "NEXTVAL cannot be used as an ADD COLUMN DEFAULT - it would give every existing row " +
                        "the exact same value instead of a growing sequence, silently defeating the one thing NEXTVAL is for"
                )
            is CurrentValueExpression -> throw DiskExecutorException("DEFAULT expression cannot contain CURRVAL")
            is MergeActionExpression -> throw DiskExecutorException("DEFAULT expression cannot contain MERGE_ACTION()")
            is ParameterExpression -> throw DiskExecutorException("DEFAULT expression cannot contain a parameter reference")
            is LiteralExpression -> Unit
        }
    }

    private fun executeInsert(statement: InsertStatement, triggerContext: TriggerRowContext? = null): StatementResult {
        catalog.temporaryTableOrNull(statement.tableName)?.let { return executeTemporaryInsert(statement, it) }
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        return withStatementTransaction { txn ->
            withTriggerAtomicity(schema.name, txn, triggerContext) {
                executeInsertInTxn(statement, table, schema, txn, triggerContext)
            }
        }
    }

    private fun executeTemporaryInsert(statement: InsertStatement, table: Table): StatementResult {
        if (statement.onConflict != null) {
            throw DiskExecutorException("INSERT ... ON CONFLICT on a temporary table is not supported by the disk-backed executor yet")
        }
        val schema = table.schema
        return withStatementTransaction { txn ->
            val targetIndices = resolveInsertTargets(schema, statement.columns)
            val subqueries = subqueryRunnerFor(txn)
            val evaluator = Evaluator(composeResolver(null, null), subqueries, this)
            val newRows = statement.rows.map { rawRow ->
                if (rawRow.size != targetIndices.size) {
                    throw DiskExecutorException(
                        "table ${schema.name} expects ${targetIndices.size} values but ${rawRow.size} were supplied"
                    )
                }
                val values = MutableList<Value>(schema.columns.size) { Value.NullValue }
                val touched = BooleanArray(schema.columns.size)
                for ((position, expression) in rawRow.withIndex()) {
                    val columnIndex = targetIndices[position]
                    if (expression != null) {
                        rejectGeneratedAssignment(schema.columns[columnIndex], explicitValueGiven = true)
                        values[columnIndex] = coerceForColumn(evaluator.evaluate(expression), schema.columns[columnIndex])
                        touched[columnIndex] = true
                    }
                }
                for ((index, column) in schema.columns.withIndex()) {
                    val default = column.default
                    if (!touched[index] && default != null) {
                        values[index] = coerceForColumn(evaluator.evaluate(default), column)
                    }
                }
                applyGeneratedColumns(schema, values, subqueries)
                validateTemporaryNotNull(schema, values)
                Row(values)
            }
            for (row in newRows) enforceChecks(schema, row, subqueries)
            enforceTemporaryPrimaryKeyUniqueness(schema, table.rows + newRows)
            enforceTemporaryUniqueConstraints(schema, table.rows + newRows)
            table.rows.addAll(newRows)
            val returning = statement.returning?.let { evaluateReturning(schema, newRows, it, subqueries) }
            InsertResult(newRows.size, returning)
        }
    }

    private fun executeInsertInTxn(
        statement: InsertStatement, table: DiskTable, schema: TableSchema, txn: Long, triggerContext: TriggerRowContext?
    ): StatementResult {
        val targetIndices = resolveInsertTargets(schema, statement.columns)
        val onConflict = statement.onConflict
        val subqueries = subqueryRunnerFor(txn)
        val evaluator = Evaluator(composeResolver(null, triggerContext), subqueries, this)
        val newRows = statement.rows.map { rawRow ->
            if (rawRow.size != targetIndices.size) {
                throw DiskExecutorException(
                    "table ${schema.name} expects ${targetIndices.size} values but ${rawRow.size} were supplied"
                )
            }
            val values = MutableList<Value>(schema.columns.size) { Value.NullValue }
            val touched = BooleanArray(schema.columns.size)
            for ((position, expression) in rawRow.withIndex()) {
                val columnIndex = targetIndices[position]
                if (expression != null) {
                    rejectGeneratedAssignment(schema.columns[columnIndex], explicitValueGiven = true)
                    values[columnIndex] = coerceForColumn(evaluator.evaluate(expression), schema.columns[columnIndex])
                    touched[columnIndex] = true
                }
            }
            for ((index, column) in schema.columns.withIndex()) {
                val default = column.default
                if (!touched[index] && default != null) {
                    values[index] = coerceForColumn(evaluator.evaluate(default), column)
                }
            }
            applyGeneratedColumns(schema, values, subqueries)
            Row(values)
        }
        if (onConflict == null) {
            fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, newRows.map { null to it }, txn)
            for (row in newRows) {
                DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, row.values, uncommittedSiblings = newRows)
                enforceChecks(schema, row, subqueries)
            }
            for (row in newRows) table.insert(txn, row)
            fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, newRows.map { null to it }, txn)
            val returning = statement.returning?.let { evaluateReturning(schema, newRows, it, subqueries) }
            return InsertResult(newRows.size, returning)
        }
        return executeInsertWithConflictHandling(schema, table, txn, newRows, onConflict, subqueries, statement.returning, triggerContext)
    }

    private class DiskUniqueKey(val columnIndices: List<Int>, val columnNames: List<String>)

    private fun diskUniqueKeysOf(schema: TableSchema, table: DiskTable): List<DiskUniqueKey> {
        val keys = mutableListOf<DiskUniqueKey>()
        val pkIndices = schema.primaryKeyIndices
        if (pkIndices.isNotEmpty()) keys.add(DiskUniqueKey(pkIndices, pkIndices.map { schema.columns[it].name }))
        for (unique in schema.uniqueConstraints) {
            val indices = unique.columns.map { schema.columnIndex(it)!! }
            keys.add(DiskUniqueKey(indices, unique.columns))
        }
        for (handle in table.secondaryIndexes) {
            if (!handle.unique) continue
            keys.add(DiskUniqueKey(handle.columnIndices, handle.columnNames))
        }
        return keys
    }

    private fun resolveConflictTargetKeys(schema: TableSchema, targetColumns: List<String>, keys: List<DiskUniqueKey>): List<DiskUniqueKey> {
        if (targetColumns.isEmpty()) return keys
        val targetIndices = targetColumns.map { name ->
            schema.columnIndex(name) ?: throw DiskExecutorException("no such column: $name")
        }
        val targetSet = targetIndices.toSet()
        val matched = keys.firstOrNull { it.columnIndices.toSet() == targetSet }
            ?: throw DiskExecutorException(
                "no unique or primary key constraint matching ON CONFLICT specification: " +
                    targetColumns.joinToString(", ")
            )
        return listOf(matched)
    }

    private fun findConflictingRow(txn: Long, table: DiskTable, candidate: List<Value>, keys: List<DiskUniqueKey>): Pair<RecordId, Row>? {
        for ((existingRid, existing) in table.scan(txn)) {
            for (key in keys) {
                val candidateKey = key.columnIndices.map { candidate[it] }
                if (candidateKey.any { it is Value.NullValue }) continue
                val existingKey = key.columnIndices.map { existing.values[it] }
                if (candidateKey == existingKey) return existingRid to existing
            }
        }
        return null
    }

    private fun executeInsertWithConflictHandling(
        schema: TableSchema,
        table: DiskTable,
        txn: Long,
        candidateRows: List<Row>,
        onConflict: OnConflictClause,
        subqueries: SubqueryRunner,
        returning: Projection? = null,
        triggerContext: TriggerRowContext? = null
    ): StatementResult {
        val keys = diskUniqueKeysOf(schema, table)
        val targetKeys = resolveConflictTargetKeys(schema, onConflict.targetColumns, keys)
        var affected = 0
        val returnedRows = mutableListOf<Row>()
        for (row in candidateRows) {
            val conflict = findConflictingRow(txn, table, row.values, targetKeys)
            if (conflict == null) {
                fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, listOf(null to row), txn)
                DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, row.values, uncommittedSiblings = emptyList())
                enforceChecks(schema, row, subqueries)
                table.insert(txn, row)
                affected++
                returnedRows.add(row)
                fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, listOf(null to row), txn)
                continue
            }
            val (conflictingRid, conflictingRow) = conflict
            when (val action = onConflict.action) {
                ConflictDoNothing -> Unit
                is ConflictDoUpdate -> {
                    val resolver = composeResolver(excludedAwareResolver(schema, conflictingRow, row), triggerContext)
                    val conditionEvaluator = Evaluator(resolver, subqueries)
                    if (action.where != null && !isTrue(conditionEvaluator.evaluate(action.where))) continue
                    val assignmentEvaluator = Evaluator(resolver, subqueries, this)
                    val values = conflictingRow.values.toMutableList()
                    for (assignment in action.assignments) {
                        val index = schema.columnIndex(assignment.columnName)
                            ?: throw DiskExecutorException("no such column: ${assignment.columnName}")
                        val column = schema.columns[index]
                        rejectGeneratedAssignment(column, explicitValueGiven = assignment.value != null)
                        values[index] = if (assignment.value != null) {
                            coerceForColumn(assignmentEvaluator.evaluate(assignment.value), column)
                        } else {
                            column.default?.let { coerceForColumn(assignmentEvaluator.evaluate(it), column) } ?: Value.NullValue
                        }
                    }
                    applyGeneratedColumns(schema, values, subqueries)
                    val updated = Row(values)
                    fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, listOf(conflictingRow to updated), txn)
                    DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, updated.values, uncommittedSiblings = emptyList())
                    enforceChecks(schema, updated, subqueries)
                    for (group in schema.enforcedUniqueKeyGroups()) {
                        val columnIndices = group.map { schema.columnIndex(it)!! }
                        val oldKey = columnIndices.map { conflictingRow.values[it] }
                        val newKey = columnIndices.map { updated.values[it] }
                        DiskForeignKeyEnforcer.cascadeUpdate(catalog, txn, schema.name, group, listOf(oldKey to newKey))
                    }
                    table.update(txn, conflictingRid, updated)
                    affected++
                    returnedRows.add(updated)
                    fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, listOf(conflictingRow to updated), txn)
                }
            }
        }
        return InsertResult(affected, returning?.let { evaluateReturning(schema, returnedRows, it, subqueries) })
    }

    private fun excludedAwareResolver(schema: TableSchema, target: Row, excluded: Row): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val qualifier = expression.qualifier
            val useExcluded = qualifier != null && qualifier.equals("excluded", ignoreCase = true)
            if (qualifier != null && !useExcluded && !qualifier.equals(schema.name, ignoreCase = true)) {
                throw DiskExecutorException("no such table or alias: $qualifier")
            }
            val index = schema.columnIndex(expression.name)
                ?: throw DiskExecutorException("no such column: ${expression.name}")
            return if (useExcluded) excluded.values[index] else target.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
    }

    private fun resolveInsertTargets(schema: TableSchema, columns: List<String>?): List<Int> {
        if (columns == null) return schema.columns.indices.toList()
        return columns.map { columnName ->
            schema.columnIndex(columnName)
                ?: throw DiskExecutorException("no such column: $columnName in table ${schema.name}")
        }
    }

    private fun executeQuery(statement: QueryStatement): StatementResult =
        withStatementTransaction { txn -> runQuery(statement, txn, outer = null) }

    private fun executeExplain(statement: ExplainStatement): StatementResult =
        withStatementTransaction { txn -> ExplainResult(explainQuery(statement.select, txn)) }

    private fun explainQuery(query: QueryStatement, txn: Long): List<String> = when (query) {
        is SelectStatement -> explainSelect(query, txn)
        is SetOperationStatement -> explainSetOperation(query, txn)
        is WithQuery -> explainWith(query, txn)
    }

    private fun explainOperatorLabel(operator: SetOperator): String = when (operator) {
        SetOperator.Union -> "Union"
        SetOperator.UnionAll -> "Union All"
        SetOperator.Intersect -> "Intersect"
        SetOperator.IntersectAll -> "Intersect All"
        SetOperator.Except -> "Except"
        SetOperator.ExceptAll -> "Except All"
    }

    private fun explainSetOperation(statement: SetOperationStatement, txn: Long): List<String> {
        val header = explainOperatorLabel(statement.operator)
        val left = explainQuery(statement.left, txn).map { "  -> $it" }
        val right = explainQuery(statement.right, txn).map { "  -> $it" }
        var lines = listOf(header) + left + right
        statement.orderBy?.let { lines = listOf("Sort") + lines.map { line -> "  $line" } }
        statement.offset?.let { lines = listOf("Offset ${statement.offset}") + lines.map { line -> "  $line" } }
        statement.limit?.let { lines = listOf("Limit ${statement.limit}") + lines.map { line -> "  $line" } }
        return lines
    }

    private fun explainWith(query: WithQuery, txn: Long): List<String> {
        var pushed = 0
        try {
            for (cte in query.ctes) {
                activeCtes.add(buildCteScope(query.recursive, cte, txn))
                pushed++
            }
            val cteLines = query.ctes.flatMap { cte ->
                listOf("CTE ${cte.name}") + explainQuery(cte.query, txn).map { "  -> $it" }
            }
            return cteLines + explainQuery(query.body, txn)
        } finally {
            repeat(pushed) { activeCtes.removeAt(activeCtes.size - 1) }
        }
    }

    private fun explainSelect(statement: SelectStatement, txn: Long): List<String> {
        val fromItems = listOf(statement.from) + statement.joins.map { it.from }
        val fromSources = fromItems.map { item -> resolveFromItem(item, txn) }
        val sources = buildJoinSources(fromItems, fromSources.map { it.schema })
        statement.joins.forEach { join -> join.on?.let { rejectWindowFunction(it, "an ON condition") } }
        var body = explainJoinLines(statement, sources, fromSources)
        statement.where?.let { rejectWindowFunction(it, "WHERE") }
        statement.groupBy.forEach { rejectWindowFunction(it, "GROUP BY") }
        statement.having?.let { rejectWindowFunction(it, "HAVING") }
        statement.distinctOn.forEach { rejectWindowFunction(it, "DISTINCT ON") }
        val orderKeys = resolvedOrderKeys(statement)
        val aggregated = statement.groupBy.isNotEmpty() || statement.groupingSpec != null ||
            containsAggregate(statement.projection) ||
            (statement.having != null && containsAggregate(statement.having))
        if (statement.distinctOn.isNotEmpty()) validateDistinctOn(statement, orderKeys, aggregated)
        val windowExpressions = collectWindowExpressions(statement.projection, orderKeys)
        if (windowExpressions.isNotEmpty()) validateWindows(windowExpressions, aggregated)
        if (aggregated) {
            if (statement.projection is StarProjection) {
                throw DiskExecutorException("cannot use * with GROUP BY or aggregate functions")
            }
            validateAggregation(statement, orderKeys, sources)
            val keys = statement.groupBy.map { describeExpression(it) }
            val header = if (keys.isEmpty()) {
                "Aggregate"
            } else {
                val setCount = GroupingSetExpansion.setsFor(statement.groupingSpec, statement.groupBy).size
                val suffix = if (setCount > 1) " ($setCount grouping sets)" else ""
                "GroupAggregate keys: ${keys.joinToString(", ")}$suffix"
            }
            body = listOf(header) + body.map { "  $it" }
        }
        statement.orderBy?.let { body = listOf("Sort") + body.map { "  $it" } }
        if (statement.distinct) {
            val label = if (statement.distinctOn.isNotEmpty()) "Unique (DISTINCT ON)" else "Unique"
            body = listOf(label) + body.map { "  $it" }
        }
        statement.offset?.let { body = listOf("Offset ${statement.offset}") + body.map { "  $it" } }
        statement.limit?.let { body = listOf("Limit ${statement.limit}") + body.map { "  $it" } }
        return body
    }

    private fun explainJoinLines(statement: SelectStatement, sources: List<JoinSource>, fromSources: List<DiskFromSource>): List<String> {
        var lines = listOf(explainScanLine(statement, fromSources.first()))
        statement.joins.forEachIndexed { position, join ->
            val right = sources[position + 1]
            val lateral = lateralSelectOf(join.from) != null
            val label = joinLabel(join.type) + if (lateral) " Lateral" else ""
            val header = "Nested Loop $label"
            val rightScan = if (lateral) "Lateral Subquery Scan on ${right.schema.name}" else "Seq Scan on ${right.schema.name}"
            lines = listOf(header) + lines.map { "  -> $it" } + listOf("  -> $rightScan")
        }
        return lines
    }

    private fun explainScanLine(statement: SelectStatement, fromSource: DiskFromSource): String {
        if (statement.joins.isEmpty() && fromSource is DiskTableSource) {
            val alias = (statement.from as? TableSource)?.alias
            val plan = accessPlanner.plan(fromSource.table.schema, fromSource.table.secondaryIndexes, statement.where, alias)
            return describeAccessPlan(plan, fromSource.table.schema, fromSource.table.secondaryIndexes)
        }
        return "Seq Scan on ${fromSource.schema.name}"
    }

    private fun describeAccessPlan(plan: DiskAccessPlan, schema: TableSchema, secondaryIndexes: List<SecondaryIndexHandle>): String {
        val tableName = schema.name
        return when (plan) {
            DiskAccessPlan.FullScan -> "Seq Scan on $tableName"
            is DiskAccessPlan.PrimaryKeyPoint -> {
                val pkColumn = schema.columns[schema.primaryKeyIndex!!].name
                "Primary Key Scan on $tableName ($pkColumn = ${literalText(plan.key)})"
            }
            is DiskAccessPlan.PrimaryKeyRange -> {
                val pkColumn = schema.columns[schema.primaryKeyIndex!!].name
                "Primary Key Range Scan on $tableName (${rangeDescription(pkColumn, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive)})"
            }
            is DiskAccessPlan.CompositePrimaryKeyPoint -> {
                val parts = schema.primaryKeyColumns.zip(plan.keys) { name, value -> "$name = ${literalText(value)}" }
                "Primary Key Scan on $tableName (${parts.joinToString(" AND ")})"
            }
            is DiskAccessPlan.CompositePrimaryKeyRange -> {
                val columnName = schema.primaryKeyColumns[plan.prefix.size]
                val prefixParts = schema.primaryKeyColumns.zip(plan.prefix) { name, value -> "$name = ${literalText(value)}" }
                val rangePart = rangeDescription(columnName, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive)
                "Primary Key Range Scan on $tableName (${(prefixParts + rangePart).joinToString(" AND ")})"
            }
            is DiskAccessPlan.Secondary -> {
                val handle = secondaryIndexes.first { it.name == plan.indexName }
                val parts = handle.columnNames.zip(plan.keys) { name, value -> "$name = ${literalText(value)}" }
                "Index Scan using ${plan.indexName} on $tableName (${parts.joinToString(" AND ")})"
            }
            is DiskAccessPlan.SecondaryRange -> {
                val handle = secondaryIndexes.first { it.name == plan.indexName }
                val columnName = handle.columnNames[plan.prefix.size]
                val prefixParts = handle.columnNames.zip(plan.prefix) { name, value -> "$name = ${literalText(value)}" }
                val rangePart = rangeDescription(columnName, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive)
                "Index Range Scan using ${plan.indexName} on $tableName (${(prefixParts + rangePart).joinToString(" AND ")})"
            }
        }
    }

    private fun rangeDescription(columnName: String, lower: Value?, lowerInclusive: Boolean, upper: Value?, upperInclusive: Boolean): String {
        val parts = mutableListOf<String>()
        lower?.let { parts.add("$columnName ${if (lowerInclusive) ">=" else ">"} ${literalText(it)}") }
        upper?.let { parts.add("$columnName ${if (upperInclusive) "<=" else "<"} ${literalText(it)}") }
        return parts.joinToString(" AND ")
    }

    private fun joinLabel(type: JoinType): String = when (type) {
        JoinType.Left -> "Left"
        JoinType.Right -> "Right"
        JoinType.Full -> "Full"
        JoinType.Inner -> "Inner"
        JoinType.Cross -> "Cross"
    }

    private fun describe(expression: ColumnExpression): String =
        expression.qualifier?.let { "$it.${expression.name}" } ?: expression.name

    private fun describeExpression(expression: Expression): String = when (expression) {
        is ColumnExpression -> describe(expression)
        is LiteralExpression -> literalText(expression.value)
        is FunctionExpression -> "${expression.name}(${describeFunctionArguments(expression)})${describeFilterClause(expression)}"
        is BinaryExpression -> "${describeExpression(expression.left)} ${expression.operator.symbol} ${describeExpression(expression.right)}"
        is UnaryExpression -> "${expression.operator.symbol} ${describeExpression(expression.operand)}"
        is CallExpression -> "${expression.name}(${expression.arguments.joinToString(", ") { describeExpression(it) }})"
        is IsNullExpression -> "${describeExpression(expression.operand)} IS ${if (expression.negated) "NOT " else ""}NULL"
        is InExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}IN (${expression.values.joinToString(", ") { describeExpression(it) }})"
        is BetweenExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}BETWEEN ${describeExpression(expression.low)} AND ${describeExpression(expression.high)}"
        is LikeExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}LIKE ${describeExpression(expression.pattern)}"
        is CaseExpression -> buildString {
            append("CASE ")
            expression.operand?.let { append(describeExpression(it)); append(' ') }
            expression.branches.forEach { append("WHEN ${describeExpression(it.condition)} THEN ${describeExpression(it.result)} ") }
            expression.elseExpression?.let { append("ELSE ${describeExpression(it)} ") }
            append("END")
        }
        is ScalarSubqueryExpression -> "(subquery)"
        is ExistsExpression -> "EXISTS (subquery)"
        is InSubqueryExpression ->
            "${describeExpression(expression.operand)} ${if (expression.negated) "NOT " else ""}IN (subquery)"
        is CastExpression ->
            "CAST(${describeExpression(expression.operand)} AS ${expression.targetType.displayWith(expression.precision, expression.scale)})"
        is CollateExpression -> "${describeExpression(expression.operand)} COLLATE ${expression.collation.sqlName}"
        is WindowExpression ->
            "${expression.name}(${if (expression.star) "*" else expression.argument?.let { describeExpression(it) } ?: ""}) OVER (...)"
        is NextValueExpression -> "NEXTVAL('${expression.sequenceName}')"
        is CurrentValueExpression -> "CURRVAL('${expression.sequenceName}')"
        is MergeActionExpression -> "MERGE_ACTION()"
        is ParameterExpression -> "\$${expression.index}"
    }

    private fun describeFunctionArguments(expression: FunctionExpression): String {
        if (expression.star) return "*"
        val distinctPrefix = if (expression.distinct) "DISTINCT " else ""
        val value = expression.argument?.let { describeExpression(it) } ?: ""
        val separator = expression.separator ?: return "$distinctPrefix$value"
        val orderBy = if (expression.orderBy.isNotEmpty()) {
            " ORDER BY " + expression.orderBy.joinToString(", ") { key -> describeExpression(key.expression) + orderKeySuffix(key) }
        } else ""
        return "$distinctPrefix$value, ${describeExpression(separator)}$orderBy"
    }

    private fun orderKeySuffix(key: OrderKey): String {
        val direction = if (key.descending) " DESC" else ""
        val nulls = when (key.nullsFirst) {
            true -> " NULLS FIRST"
            false -> " NULLS LAST"
            null -> ""
        }
        return direction + nulls
    }

    private fun describeFilterClause(expression: FunctionExpression): String =
        expression.filter?.let { " FILTER (WHERE ${describeExpression(it)})" } ?: ""

    private fun literalText(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> value.value.toString()
        is Value.TextValue -> "'${value.value}'"
        is Value.BooleanValue -> value.value.toString()
        is Value.DecimalValue -> value.value.toPlainString()
        is Value.DateValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private fun subqueryRunnerFor(txn: Long): SubqueryRunner = SubqueryRunner { select, outer -> runSelect(select, txn, outer) }

    private fun runQuery(query: QueryStatement, txn: Long, outer: ColumnResolver?): SelectResult = when (query) {
        is SelectStatement -> runSelect(query, txn, outer)
        is SetOperationStatement -> runSetOperation(query, txn, outer)
        is WithQuery -> runWith(query, txn, outer)
    }

    private fun setOperatorKeyword(operator: SetOperator): String = when (operator) {
        SetOperator.Union -> "UNION"
        SetOperator.UnionAll -> "UNION ALL"
        SetOperator.Intersect -> "INTERSECT"
        SetOperator.IntersectAll -> "INTERSECT ALL"
        SetOperator.Except -> "EXCEPT"
        SetOperator.ExceptAll -> "EXCEPT ALL"
    }

    private fun runSetOperation(statement: SetOperationStatement, txn: Long, outer: ColumnResolver?): SelectResult {
        val left = runQuery(statement.left, txn, outer)
        val right = runQuery(statement.right, txn, outer)
        if (left.columns.size != right.columns.size) {
            throw DiskExecutorException(
                "${setOperatorKeyword(statement.operator)} queries must have the same number of columns " +
                    "but got ${left.columns.size} and ${right.columns.size}"
            )
        }
        val declaredCollationOf = declaredSetOperationCollation(statement, left.columns)
        val outputExpressions = left.columns.map { ColumnExpression(it) }
        fun foldedKey(row: List<Value>) = collationFoldedKey(row, outputExpressions, declaredCollationOf)
        var rows = when (statement.operator) {
            SetOperator.UnionAll -> left.rows + right.rows
            SetOperator.Union -> (left.rows + right.rows).distinctBy(::foldedKey)
            SetOperator.Intersect -> {
                val rightKeys = right.rows.mapTo(HashSet(), ::foldedKey)
                left.rows.filter { foldedKey(it) in rightKeys }.distinctBy(::foldedKey)
            }
            SetOperator.Except -> {
                val rightKeys = right.rows.mapTo(HashSet(), ::foldedKey)
                left.rows.filter { foldedKey(it) !in rightKeys }.distinctBy(::foldedKey)
            }
            SetOperator.IntersectAll -> multisetFilter(left.rows, right.rows, keepOnMatch = true, keyOf = ::foldedKey)
            SetOperator.ExceptAll -> multisetFilter(left.rows, right.rows, keepOnMatch = false, keyOf = ::foldedKey)
        }
        val orderKeys = statement.orderBy?.keys.orEmpty()
        if (orderKeys.isNotEmpty()) {
            rows = rows.sortedWith(
                multiKeyComparator(
                    orderKeys,
                    evaluate = { expression, row -> evaluateOutputRow(left.columns, expression, row, txn) },
                    declaredCollationOf = declaredCollationOf
                )
            )
        }
        statement.offset?.let { if (it < 0) throw DiskExecutorException("OFFSET must not be negative") }
        statement.limit?.let { if (it < 0) throw DiskExecutorException("LIMIT must not be negative") }
        if (statement.offset != null || statement.limit != null) {
            rows = rows.drop(statement.offset ?: 0)
            statement.limit?.let { rows = rows.take(it) }
        }
        return SelectResult(left.columns, rows)
    }

    private fun declaredSetOperationCollation(statement: SetOperationStatement, columns: List<String>): (ColumnExpression) -> Collation? =
        (statement.left as? SelectStatement)?.let { declaredCollationForSelectOutput(it, columns) } ?: { null }

    private fun declaredCollationForSelectOutput(select: SelectStatement, columns: List<String>): (ColumnExpression) -> Collation? {
        val items = listOf(select.from) + select.joins.map { it.from }
        val sources = items.map { item ->
            (item as? TableSource)?.let { source -> catalog.tableOrNull(source.name)?.schema?.let { (source.alias ?: source.name) to it } }
        }
        if (sources.any { it == null }) return { null }
        val resolvedSources = sources.filterNotNull()
        fun columnCollation(expression: ColumnExpression): Collation? {
            val matches = resolvedSources
                .filter { (alias, _) -> expression.qualifier == null || alias.equals(expression.qualifier, ignoreCase = true) }
                .mapNotNull { (_, schema) -> schema.column(expression.name) }
            return matches.singleOrNull()?.collation
        }
        return when (val projection = select.projection) {
            is ExpressionProjection -> outputExpr@{ outputExpr ->
                val index = columns.indexOfFirst { it.equals(outputExpr.name, ignoreCase = true) }
                val itemExpression = index.takeIf { it in projection.items.indices }?.let { projection.items[it].expression }
                (itemExpression as? ColumnExpression)?.let(::columnCollation)
            }
            is StarProjection -> outputExpr@{ outputExpr ->
                val index = columns.indexOfFirst { it.equals(outputExpr.name, ignoreCase = true) }
                if (index < 0) return@outputExpr null
                var remaining = index
                for ((_, schema) in resolvedSources) {
                    if (remaining < schema.columns.size) return@outputExpr schema.columns[remaining].collation
                    remaining -= schema.columns.size
                }
                null
            }
        }
    }

    private fun declaredRecursiveCteCollation(base: QueryStatement, columns: List<String>): (ColumnExpression) -> Collation? =
        (base as? SelectStatement)?.let { declaredCollationForSelectOutput(it, columns) } ?: { null }

    private fun multisetFilter(
        left: List<List<Value>>,
        right: List<List<Value>>,
        keepOnMatch: Boolean,
        keyOf: (List<Value>) -> List<Value>
    ): List<List<Value>> {
        val remainingInRight = right.groupingBy(keyOf).eachCount().toMutableMap()
        return left.filter { row ->
            val key = keyOf(row)
            val remaining = remainingInRight[key] ?: 0
            if (remaining > 0) {
                remainingInRight[key] = remaining - 1
                keepOnMatch
            } else {
                !keepOnMatch
            }
        }
    }

    private fun multiKeyComparator(
        keys: List<OrderKey>,
        evaluate: (Expression, List<Value>) -> Value,
        declaredCollationOf: (ColumnExpression) -> Collation? = { null }
    ): Comparator<List<Value>> =
        Comparator { first, second ->
            var result = 0
            for (key in keys) {
                val comparison = compareOrderKeyValues(
                    evaluate(key.expression, first), evaluate(key.expression, second), key, declaredCollationOf
                )
                if (comparison != 0) {
                    result = comparison
                    break
                }
            }
            result
        }

    private fun evaluateOutputRow(columns: List<String>, expression: Expression, row: List<Value>, txn: Long): Value {
        val resolver = ColumnResolver { column ->
            if (column.qualifier != null) throw DiskExecutorException("no such column: ${column.qualifier}.${column.name}")
            val index = columns.indexOfFirst { it.equals(column.name, ignoreCase = true) }
            if (index == -1) throw DiskExecutorException("no such column: ${column.name}")
            row[index]
        }
        return Evaluator(resolver, subqueryRunnerFor(txn)).evaluate(expression)
    }

    private fun runWith(query: WithQuery, txn: Long, outer: ColumnResolver?): SelectResult {
        var pushed = 0
        try {
            for (cte in query.ctes) {
                activeCtes.add(buildCteScope(query.recursive, cte, txn))
                pushed++
            }
            return runQuery(query.body, txn, outer)
        } finally {
            repeat(pushed) { activeCtes.removeAt(activeCtes.size - 1) }
        }
    }

    private fun buildCteScope(recursive: Boolean, cte: CommonTableExpression, txn: Long): CteScope {
        val recursivePlan = if (recursive) splitRecursiveCte(cte) else null
        if (recursivePlan == null) {
            val result = runQuery(cte.query, txn, outer = null)
            return CteScope(cte.name, cteSchema(cte, result), result.rows)
        }
        val (base, operator, recursiveTerm) = recursivePlan
        val baseResult = runQuery(base, txn, outer = null)
        val schema = cteSchema(cte, baseResult)
        val scope = CteScope(cte.name, schema, baseResult.rows)
        val declaredCollationOf = declaredRecursiveCteCollation(base, baseResult.columns)
        val outputExpressions = baseResult.columns.map { ColumnExpression(it) }
        val seenKeys = if (operator == SetOperator.Union)
            baseResult.rows.mapTo(HashSet()) { collationFoldedKey(it, outputExpressions, declaredCollationOf) }
        else null
        activeCtes.add(scope)
        try {
            val accumulated = mutableListOf<List<Value>>()
            accumulated.addAll(baseResult.rows)
            var working = baseResult.rows
            var iterations = 0
            while (working.isNotEmpty()) {
                iterations++
                if (iterations > MAX_RECURSION_ITERATIONS) {
                    throw DiskExecutorException(
                        "recursive common table expression '${cte.name}' did not terminate within $MAX_RECURSION_ITERATIONS iterations"
                    )
                }
                scope.rows = working
                var newRows = runQuery(recursiveTerm, txn, outer = null).rows
                if (operator == SetOperator.Union) {
                    newRows = newRows.filter { candidate -> seenKeys!!.add(collationFoldedKey(candidate, outputExpressions, declaredCollationOf)) }
                }
                if (newRows.isEmpty()) break
                accumulated.addAll(newRows)
                working = newRows
            }
            scope.rows = accumulated
        } finally {
            activeCtes.removeAt(activeCtes.size - 1)
        }
        return scope
    }

    private data class RecursiveCtePlan(val base: QueryStatement, val operator: SetOperator, val recursiveTerm: QueryStatement)

    private fun splitRecursiveCte(cte: CommonTableExpression): RecursiveCtePlan? {
        val query = cte.query
        if (query !is SetOperationStatement) return null
        if (query.operator != SetOperator.Union && query.operator != SetOperator.UnionAll) return null
        val leftRefs = referencesName(query.left, cte.name)
        val rightRefs = referencesName(query.right, cte.name)
        return when {
            leftRefs && rightRefs ->
                throw DiskExecutorException(
                    "recursive common table expression '${cte.name}' must not reference itself on both sides of ${query.operator}"
                )
            rightRefs -> RecursiveCtePlan(query.left, query.operator, query.right)
            leftRefs -> RecursiveCtePlan(query.right, query.operator, query.left)
            else -> null
        }
    }

    private fun referencesName(query: QueryStatement, name: String): Boolean = when (query) {
        is SelectStatement -> referencesName(query.from, name) || query.joins.any { referencesName(it.from, name) }
        is SetOperationStatement -> referencesName(query.left, name) || referencesName(query.right, name)
        is WithQuery -> query.ctes.none { it.name.equals(name, ignoreCase = true) } && referencesName(query.body, name)
    }

    private fun referencesName(item: FromItem, name: String): Boolean = when (item) {
        is TableSource -> item.name.equals(name, ignoreCase = true)
        is SubquerySource -> referencesName(item.select, name)
    }

    private fun cteSchema(cte: CommonTableExpression, result: SelectResult): TableSchema {
        val labels = cte.columns ?: result.columns
        if (labels.size != result.columns.size) {
            throw DiskExecutorException(
                "common table expression '${cte.name}' expects ${labels.size} columns but its query produces ${result.columns.size}"
            )
        }
        return derivedSchema(cte.name, result, labels)
    }

    private fun derivedSchema(name: String, result: SelectResult, labels: List<String> = result.columns): TableSchema {
        val columns = labels.mapIndexed { index, columnName ->
            val type = result.rows.asSequence().map { it[index] }.firstOrNull { !it.isNull }?.type ?: ColumnType.Text
            Column(columnName, type, notNull = false, primaryKey = false)
        }
        return TableSchema(name, columns)
    }

    private class CteScope(val name: String, val schema: TableSchema, var rows: List<List<Value>>)

    private sealed interface DiskFromSource {
        val schema: TableSchema
        fun scan(txn: Long): List<List<Value>>
    }

    private class DiskTableSource(val table: DiskTable) : DiskFromSource {
        override val schema get() = table.schema
        override fun scan(txn: Long): List<List<Value>> = table.scan(txn).map { it.second.values }
    }

    private class CteRowSource(override val schema: TableSchema, val rows: List<List<Value>>) : DiskFromSource {
        override fun scan(txn: Long): List<List<Value>> = rows
    }

    private fun resolveFromSource(name: String, txn: Long): DiskFromSource {
        activeCtes.lastOrNull { it.name.equals(name, ignoreCase = true) }?.let { return CteRowSource(it.schema, it.rows) }
        catalog.temporaryTableOrNull(name)?.let { return CteRowSource(it.schema, it.rows.map { row -> row.values }) }
        catalog.tableOrNull(name)?.let { return DiskTableSource(it) }
        val view = catalog.viewOrNull(name) ?: return DiskTableSource(catalog.table(name))
        val key = name.lowercase()
        if (!expandingViews.add(key)) {
            throw DiskExecutorException("recursive view definition: $name")
        }
        try {
            val result = runQuery(view, txn, outer = null)
            return CteRowSource(derivedSchema(name, result), result.rows)
        } finally {
            expandingViews.remove(key)
        }
    }

    private fun resolveFromItem(item: FromItem, txn: Long): DiskFromSource = when (item) {
        is TableSource -> resolveFromSource(item.name, txn)
        is SubquerySource ->
            if (item.lateral) {
                CteRowSource(lateralSchema(item), emptyList())
            } else {
                val result = runQuery(item.select, txn, outer = null)
                CteRowSource(derivedSchema(item.alias, result), result.rows)
            }
    }

    private fun lateralSchema(item: SubquerySource): TableSchema {
        val labels = structuralColumnNames(item.select)
        val columns = labels.map { Column(it, ColumnType.Text, notNull = false, primaryKey = false) }
        return TableSchema(item.alias, columns)
    }

    private fun structuralColumnNames(query: QueryStatement, localCtes: Map<String, List<String>> = emptyMap()): List<String> =
        when (query) {
            is SelectStatement -> when (val projection = query.projection) {
                is StarProjection ->
                    structuralSourceColumnNames(query.from, localCtes) +
                        query.joins.flatMap { structuralSourceColumnNames(it.from, localCtes) }
                is ExpressionProjection -> projection.items.mapIndexed { index, item -> columnLabel(item, index) }
            }
            is SetOperationStatement -> structuralColumnNames(query.left, localCtes)
            is WithQuery -> {
                val extended = localCtes.toMutableMap()
                for (cte in query.ctes) {
                    extended[cte.name.lowercase()] = cte.columns ?: structuralColumnNames(cte.query, extended)
                }
                structuralColumnNames(query.body, extended)
            }
        }

    private fun structuralSourceColumnNames(item: FromItem, localCtes: Map<String, List<String>>): List<String> =
        when (item) {
            is TableSource -> {
                val key = item.name.lowercase()
                localCtes[key]
                    ?: activeCtes.lastOrNull { it.name.equals(item.name, ignoreCase = true) }?.schema?.columnNames
                    ?: catalog.temporaryTableOrNull(item.name)?.schema?.columnNames
                    ?: catalog.tableOrNull(item.name)?.schema?.columnNames
                    ?: catalog.viewOrNull(item.name)?.let { structuralColumnNames(it, localCtes) }
                    ?: throw DiskExecutorException("no such table: ${item.name}")
            }
            is SubquerySource -> structuralColumnNames(item.select, localCtes)
        }

    private fun runSelect(statement: SelectStatement, txn: Long, outer: ColumnResolver?): SelectResult {
        val fromItems = listOf(statement.from) + statement.joins.map { it.from }
        val fromSources = fromItems.map { item -> resolveFromItem(item, txn) }
        val sources = buildJoinSources(fromItems, fromSources.map { it.schema })
        val subqueries = subqueryRunnerFor(txn)
        var combined = initialRows(statement, txn, statement.from, fromSources.first())
        statement.joins.forEachIndexed { position, join ->
            join.on?.let { rejectWindowFunction(it, "an ON condition") }
            val rightSource = sources[position + 1]
            val lateralSelect = lateralSelectOf(join.from)
            combined = if (lateralSelect != null) {
                executeLateralJoin(join, combined, lateralSelect, sources.subList(0, position + 1), rightSource, outer, subqueries, txn)
            } else {
                val rightRows = fromSources[position + 1].scan(txn)
                applyJoin(join, combined, rightRows, sources.subList(0, position + 2), outer, subqueries, rightSource)
            }
        }
        statement.where?.let { where ->
            rejectWindowFunction(where, "WHERE")
            combined = combined.filter { row -> isTrue(evaluateCombinedRow(sources, outer, subqueries, where, row)) }
        }
        statement.groupBy.forEach { rejectWindowFunction(it, "GROUP BY") }
        statement.having?.let { rejectWindowFunction(it, "HAVING") }
        statement.distinctOn.forEach { rejectWindowFunction(it, "DISTINCT ON") }
        val orderKeys = resolvedOrderKeys(statement)
        val aggregated = statement.groupBy.isNotEmpty() || statement.groupingSpec != null ||
            containsAggregate(statement.projection) ||
            (statement.having != null && containsAggregate(statement.having))
        if (statement.distinctOn.isNotEmpty()) validateDistinctOn(statement, orderKeys, aggregated)
        val windowExpressions = collectWindowExpressions(statement.projection, orderKeys)
        if (windowExpressions.isNotEmpty()) validateWindows(windowExpressions, aggregated)
        if (aggregated) {
            if (statement.projection is StarProjection) {
                throw DiskExecutorException("cannot use * with GROUP BY or aggregate functions")
            }
            validateAggregation(statement, orderKeys, sources)
            return GroupAggregation(
                { expression -> resolveIndex(sources, expression) },
                { expression, row -> evaluateCombinedRow(sources, outer, subqueries, expression, row) },
                ::columnLabel,
                subqueries,
                { expression -> declaredCollationOrNull(sources, expression) }
            ).aggregate(statement, orderKeys, combined)
        }
        val baseColumnCount = sources.sumOf { it.schema.columns.size }
        if (windowExpressions.isNotEmpty()) {
            val evaluateRow: (Expression, List<Value>) -> Value = { expression, row -> evaluateCombinedRow(sources, outer, subqueries, expression, row) }
            val perExpression = windowExpressions.map { window ->
                computeWindowValues(window, combined, evaluateRow) { expression -> declaredCollationOrNull(sources, expression) }
            }
            combined = combined.mapIndexed { rowIndex, row -> row + windowExpressions.indices.map { perExpression[it][rowIndex] } }
        }
        if (orderKeys.isNotEmpty()) {
            combined = sortCombinedRows(sources, outer, subqueries, combined, orderKeys, windowExpressions, baseColumnCount)
        }
        if (statement.distinctOn.isNotEmpty()) {
            val kept = LinkedHashMap<List<Value>, List<Value>>()
            for (row in combined) {
                val key = statement.distinctOn.map { evaluateWindowAware(sources, outer, subqueries, windowExpressions, baseColumnCount, it, row) }
                val bucketKey = collationFoldedKey(key, statement.distinctOn) { expression -> declaredCollationOrNull(sources, expression) }
                kept.putIfAbsent(bucketKey, row)
            }
            combined = kept.values.toList()
        }
        statement.offset?.let { if (it < 0) throw DiskExecutorException("OFFSET must not be negative") }
        statement.limit?.let { if (it < 0) throw DiskExecutorException("LIMIT must not be negative") }
        if (!statement.distinct) {
            if (statement.offset != null || statement.limit != null) {
                combined = combined.drop(statement.offset ?: 0)
                statement.limit?.let { combined = combined.take(it) }
            }
        }
        val (columns, projected) = projectCombined(statement.projection, sources, outer, subqueries, combined, windowExpressions, baseColumnCount)
        var resultRows = projected
        if (statement.distinct) {
            if (statement.distinctOn.isEmpty()) {
                val itemExpressions = when (statement.projection) {
                    is ExpressionProjection -> statement.projection.items.map { it.expression }
                    is StarProjection -> columns.map { ColumnExpression(it) }
                }
                resultRows = resultRows.distinctBy { row -> collationFoldedKey(row, itemExpressions) { expression -> declaredCollationOrNull(sources, expression) } }
            }
            if (statement.offset != null || statement.limit != null) {
                resultRows = resultRows.drop(statement.offset ?: 0)
                statement.limit?.let { resultRows = resultRows.take(it) }
            }
        }
        return SelectResult(columns, resultRows)
    }

    private fun resolvedOrderKeys(statement: SelectStatement): List<OrderKey> =
        statement.orderBy?.keys.orEmpty().map { key -> OrderKey(resolveOrderAlias(statement.projection, key.expression), key.descending, key.nullsFirst) }

    private fun resolveOrderAlias(projection: Projection, expression: Expression): Expression {
        if (expression is ColumnExpression && expression.qualifier == null && projection is ExpressionProjection) {
            val aliased = projection.items.firstOrNull { it.alias?.equals(expression.name, ignoreCase = true) == true }
            if (aliased != null) return aliased.expression
        }
        return expression
    }

    private class JoinSource(val alias: String, val schema: TableSchema, val offset: Int)

    private fun buildJoinSources(fromItems: List<FromItem>, schemas: List<TableSchema>): List<JoinSource> {
        val sources = mutableListOf<JoinSource>()
        var offset = 0
        for ((item, schema) in fromItems.zip(schemas)) {
            val alias = sourceLabel(item)
            if (sources.any { it.alias.equals(alias, ignoreCase = true) }) {
                throw DiskExecutorException("duplicate table alias: $alias")
            }
            sources.add(JoinSource(alias, schema, offset))
            offset += schema.columns.size
        }
        return sources
    }

    private fun sourceLabel(item: FromItem): String = when (item) {
        is TableSource -> item.alias ?: item.name
        is SubquerySource -> item.alias
    }

    private fun initialRows(statement: SelectStatement, txn: Long, item: FromItem, fromSource: DiskFromSource): List<List<Value>> =
        if (statement.joins.isEmpty() && fromSource is DiskTableSource) {
            candidateRows(fromSource.table, txn, statement.where, (item as TableSource).alias).map { it.second.values }
        } else {
            fromSource.scan(txn)
        }

    private fun applyJoin(
        join: JoinClause,
        left: List<List<Value>>,
        rightRows: List<List<Value>>,
        sourcesSoFar: List<JoinSource>,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        rightSource: JoinSource
    ): List<List<Value>> {
        val nullRight = List(rightSource.schema.columns.size) { Value.NullValue }
        val nullLeft = List(rightSource.offset) { Value.NullValue }
        val outerLeft = join.type == JoinType.Left || join.type == JoinType.Full
        val outerRight = join.type == JoinType.Right || join.type == JoinType.Full
        val condition = join.on
        val result = mutableListOf<List<Value>>()
        val matchedRight = BooleanArray(rightRows.size)
        for (leftRow in left) {
            var matched = false
            rightRows.forEachIndexed { index, rightRow ->
                val candidate = leftRow + rightRow
                if (condition == null || isTrue(evaluateCombinedRow(sourcesSoFar, outer, subqueries, condition, candidate))) {
                    result.add(candidate)
                    matched = true
                    matchedRight[index] = true
                }
            }
            if (!matched && outerLeft) result.add(leftRow + nullRight)
        }
        if (outerRight) {
            rightRows.forEachIndexed { index, rightRow -> if (!matchedRight[index]) result.add(nullLeft + rightRow) }
        }
        return result
    }

    private fun lateralSelectOf(item: FromItem): QueryStatement? = (item as? SubquerySource)?.takeIf { it.lateral }?.select

    private fun executeLateralJoin(
        join: JoinClause,
        left: List<List<Value>>,
        lateralSelect: QueryStatement,
        sourcesSoFar: List<JoinSource>,
        rightSource: JoinSource,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        txn: Long
    ): List<List<Value>> {
        val nullRight = List(rightSource.schema.columns.size) { Value.NullValue }
        val allSources = sourcesSoFar + rightSource
        val condition = join.on
        val result = mutableListOf<List<Value>>()
        for (leftRow in left) {
            val rightRows = runQuery(lateralSelect, txn, lateralOuterResolver(sourcesSoFar, leftRow)).rows
            var matched = false
            for (rightRow in rightRows) {
                val candidate = leftRow + rightRow
                if (condition == null || isTrue(evaluateCombinedRow(allSources, outer, subqueries, condition, candidate))) {
                    result.add(candidate)
                    matched = true
                }
            }
            if (!matched && join.type == JoinType.Left) result.add(leftRow + nullRight)
        }
        return result
    }

    private fun lateralOuterResolver(sourcesSoFar: List<JoinSource>, leftRow: List<Value>): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value =
            resolveIndexOrNull(sourcesSoFar, expression)?.let { leftRow[it] }
                ?: throw DiskExecutorException(
                    "LATERAL subquery cannot reference ${expression.name}: not joined yet at this point in the FROM clause"
                )

        override fun declaredCollation(expression: ColumnExpression): Collation? =
            resolveIndexOrNull(sourcesSoFar, expression)?.let { declaredCollationOrNull(sourcesSoFar, expression) }
    }

    private fun resolveIndexOrNull(sources: List<JoinSource>, expression: ColumnExpression): Int? {
        val qualifier = expression.qualifier
        val matches = mutableListOf<Int>()
        for (source in sources) {
            if (qualifier != null && !source.alias.equals(qualifier, ignoreCase = true)) continue
            val local = source.schema.columnIndex(expression.name) ?: continue
            matches.add(source.offset + local)
        }
        return when {
            matches.isEmpty() -> null
            matches.size > 1 -> throw DiskExecutorException("ambiguous column reference: ${expression.name}")
            else -> matches.single()
        }
    }

    private fun resolveIndex(sources: List<JoinSource>, expression: ColumnExpression): Int =
        resolveIndexOrNull(sources, expression)
            ?: throw DiskExecutorException("no such column: ${expression.name}")

    private fun declaredCollationOrNull(sources: List<JoinSource>, expression: ColumnExpression): Collation? {
        val qualifier = expression.qualifier
        val matches = mutableListOf<Collation>()
        for (source in sources) {
            if (qualifier != null && !source.alias.equals(qualifier, ignoreCase = true)) continue
            val column = source.schema.column(expression.name) ?: continue
            matches.add(column.collation)
        }
        return matches.singleOrNull()
    }

    private fun evaluateCombinedRow(
        sources: List<JoinSource>,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        expression: Expression,
        row: List<Value>
    ): Value {
        val resolver = object : ColumnResolver {
            override fun resolve(columnExpression: ColumnExpression): Value {
                val index = resolveIndexOrNull(sources, columnExpression)
                return if (index != null) {
                    row[index]
                } else {
                    (outer ?: throw DiskExecutorException("no such column: ${columnExpression.name}")).resolve(columnExpression)
                }
            }

            override fun declaredCollation(columnExpression: ColumnExpression): Collation? =
                declaredCollationOrNull(sources, columnExpression) ?: outer?.declaredCollation(columnExpression)
        }
        return Evaluator(resolver, subqueries).evaluate(expression)
    }

    private fun sortCombinedRows(
        sources: List<JoinSource>,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        rows: List<List<Value>>,
        orderKeys: List<OrderKey>,
        windowExpressions: List<WindowExpression>,
        baseColumnCount: Int
    ): List<List<Value>> {
        val keyed = rows.map { row ->
            row to orderKeys.map { key -> evaluateWindowAware(sources, outer, subqueries, windowExpressions, baseColumnCount, key.expression, row) }
        }
        val comparator = Comparator<List<Value>> { left, right ->
            for ((index, key) in orderKeys.withIndex()) {
                val comparison = compareOrderKeyValues(left[index], right[index], key) { expression ->
                    declaredCollationOrNull(sources, expression)
                }
                if (comparison != 0) return@Comparator comparison
            }
            0
        }
        return keyed.sortedWith(compareBy(comparator) { it.second }).map { it.first }
    }

    private fun evaluateWindowAware(
        sources: List<JoinSource>,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        windowExpressions: List<WindowExpression>,
        baseColumnCount: Int,
        expression: Expression,
        row: List<Value>
    ): Value {
        val prepared = if (windowExpressions.isEmpty()) expression else substituteWindowValues(expression, row, windowExpressions, baseColumnCount)
        return evaluateCombinedRow(sources, outer, subqueries, prepared, row)
    }

    private fun substituteWindowValues(
        expression: Expression,
        row: List<Value>,
        windowExpressions: List<WindowExpression>,
        baseColumnCount: Int
    ): Expression {
        fun recurse(inner: Expression) = substituteWindowValues(inner, row, windowExpressions, baseColumnCount)
        return when (expression) {
            is WindowExpression -> LiteralExpression(row[baseColumnCount + windowExpressions.indexOf(expression)])
            is BinaryExpression -> expression.copy(left = recurse(expression.left), right = recurse(expression.right))
            is UnaryExpression -> expression.copy(operand = recurse(expression.operand))
            is CallExpression -> expression.copy(arguments = expression.arguments.map(::recurse))
            is IsNullExpression -> expression.copy(operand = recurse(expression.operand))
            is InExpression -> expression.copy(operand = recurse(expression.operand), values = expression.values.map(::recurse))
            is BetweenExpression -> expression.copy(operand = recurse(expression.operand), low = recurse(expression.low), high = recurse(expression.high))
            is LikeExpression -> expression.copy(operand = recurse(expression.operand), pattern = recurse(expression.pattern))
            is CaseExpression -> expression.copy(
                operand = expression.operand?.let(::recurse),
                branches = expression.branches.map { it.copy(condition = recurse(it.condition), result = recurse(it.result)) },
                elseExpression = expression.elseExpression?.let(::recurse)
            )
            is CastExpression -> expression.copy(operand = recurse(expression.operand))
            is CollateExpression -> expression.copy(operand = recurse(expression.operand))
            else -> expression
        }
    }

    private fun projectCombined(
        projection: Projection,
        sources: List<JoinSource>,
        outer: ColumnResolver?,
        subqueries: SubqueryRunner,
        rows: List<List<Value>>,
        windowExpressions: List<WindowExpression>,
        baseColumnCount: Int
    ): Pair<List<String>, List<List<Value>>> =
        when (projection) {
            StarProjection -> sources.flatMap { it.schema.columnNames } to rows.map { it.subList(0, baseColumnCount) }
            is ExpressionProjection -> {
                val columns = projection.items.mapIndexed { index, item -> columnLabel(item, index) }
                val values = rows.map { row ->
                    projection.items.map { item ->
                        evaluateWindowAware(sources, outer, subqueries, windowExpressions, baseColumnCount, item.expression, row)
                    }
                }
                columns to values
            }
        }

    private fun columnLabel(item: SelectItem, index: Int): String =
        item.alias ?: when (val expression = item.expression) {
            is ColumnExpression -> expression.name
            is FunctionExpression -> expression.name.lowercase()
            is CallExpression -> expression.name.lowercase()
            else -> "column${index + 1}"
        }

    private fun containsAggregate(projection: Projection): Boolean = when (projection) {
        StarProjection -> false
        is ExpressionProjection -> projection.items.any { containsAggregate(it.expression) }
    }

    private fun containsAggregate(expression: Expression): Boolean = when (expression) {
        is FunctionExpression -> true
        is BinaryExpression -> containsAggregate(expression.left) || containsAggregate(expression.right)
        is UnaryExpression -> containsAggregate(expression.operand)
        is CallExpression -> expression.arguments.any { containsAggregate(it) }
        is IsNullExpression -> containsAggregate(expression.operand)
        is InExpression -> containsAggregate(expression.operand) || expression.values.any { containsAggregate(it) }
        is BetweenExpression ->
            containsAggregate(expression.operand) || containsAggregate(expression.low) || containsAggregate(expression.high)
        is LikeExpression -> containsAggregate(expression.operand) || containsAggregate(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { containsAggregate(it) } ?: false) ||
                expression.branches.any { containsAggregate(it.condition) || containsAggregate(it.result) } ||
                (expression.elseExpression?.let { containsAggregate(it) } ?: false)
        is CastExpression -> containsAggregate(expression.operand)
        is CollateExpression -> containsAggregate(expression.operand)
        else -> false
    }

    private fun validateDistinctOn(statement: SelectStatement, orderKeys: List<OrderKey>, aggregated: Boolean) {
        if (aggregated) {
            throw DiskExecutorException("DISTINCT ON cannot be combined with GROUP BY or aggregate functions")
        }
        if (statement.orderBy != null) {
            val leading = orderKeys.take(statement.distinctOn.size).map { it.expression }
            if (leading != statement.distinctOn) {
                throw DiskExecutorException("SELECT DISTINCT ON expressions must match initial ORDER BY expressions")
            }
        }
    }

    private fun validateAggregation(statement: SelectStatement, orderKeys: List<OrderKey>, sources: List<JoinSource>) {
        val groupColumns = statement.groupBy.filterIsInstance<ColumnExpression>()
            .map { resolveIndex(sources, it) }.toSet()
        if (statement.projection is ExpressionProjection) {
            statement.projection.items.forEach { requireGrouped(it.expression, statement.groupBy, groupColumns, sources) }
        }
        statement.having?.let { requireGrouped(it, statement.groupBy, groupColumns, sources) }
        orderKeys.forEach { requireGrouped(it.expression, statement.groupBy, groupColumns, sources) }
    }

    private fun requireGrouped(expression: Expression, groupBy: List<Expression>, groupColumns: Set<Int>, sources: List<JoinSource>) {
        if (expression in groupBy) return
        when (expression) {
            is FunctionExpression -> Unit
            is ColumnExpression -> if (resolveIndex(sources, expression) !in groupColumns) {
                throw DiskExecutorException(
                    "column ${expression.name} must appear in GROUP BY or be used in an aggregate function"
                )
            }
            is BinaryExpression -> {
                requireGrouped(expression.left, groupBy, groupColumns, sources)
                requireGrouped(expression.right, groupBy, groupColumns, sources)
            }
            is UnaryExpression -> requireGrouped(expression.operand, groupBy, groupColumns, sources)
            is CallExpression -> expression.arguments.forEach { requireGrouped(it, groupBy, groupColumns, sources) }
            is IsNullExpression -> requireGrouped(expression.operand, groupBy, groupColumns, sources)
            is InExpression -> {
                requireGrouped(expression.operand, groupBy, groupColumns, sources)
                expression.values.forEach { requireGrouped(it, groupBy, groupColumns, sources) }
            }
            is BetweenExpression -> {
                requireGrouped(expression.operand, groupBy, groupColumns, sources)
                requireGrouped(expression.low, groupBy, groupColumns, sources)
                requireGrouped(expression.high, groupBy, groupColumns, sources)
            }
            is LikeExpression -> {
                requireGrouped(expression.operand, groupBy, groupColumns, sources)
                requireGrouped(expression.pattern, groupBy, groupColumns, sources)
            }
            is CaseExpression -> {
                expression.operand?.let { requireGrouped(it, groupBy, groupColumns, sources) }
                expression.branches.forEach {
                    requireGrouped(it.condition, groupBy, groupColumns, sources)
                    requireGrouped(it.result, groupBy, groupColumns, sources)
                }
                expression.elseExpression?.let { requireGrouped(it, groupBy, groupColumns, sources) }
            }
            is CastExpression -> requireGrouped(expression.operand, groupBy, groupColumns, sources)
            is CollateExpression -> requireGrouped(expression.operand, groupBy, groupColumns, sources)
            is ScalarSubqueryExpression -> Unit
            is ExistsExpression -> Unit
            is InSubqueryExpression -> requireGrouped(expression.operand, groupBy, groupColumns, sources)
            else -> Unit
        }
    }

    private fun containsWindowFunction(expression: Expression): Boolean = when (expression) {
        is WindowExpression -> true
        is BinaryExpression -> containsWindowFunction(expression.left) || containsWindowFunction(expression.right)
        is UnaryExpression -> containsWindowFunction(expression.operand)
        is CallExpression -> expression.arguments.any { containsWindowFunction(it) }
        is IsNullExpression -> containsWindowFunction(expression.operand)
        is InExpression -> containsWindowFunction(expression.operand) || expression.values.any { containsWindowFunction(it) }
        is BetweenExpression ->
            containsWindowFunction(expression.operand) || containsWindowFunction(expression.low) || containsWindowFunction(expression.high)
        is LikeExpression -> containsWindowFunction(expression.operand) || containsWindowFunction(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { containsWindowFunction(it) } ?: false) ||
                expression.branches.any { containsWindowFunction(it.condition) || containsWindowFunction(it.result) } ||
                (expression.elseExpression?.let { containsWindowFunction(it) } ?: false)
        is CastExpression -> containsWindowFunction(expression.operand)
        is CollateExpression -> containsWindowFunction(expression.operand)
        else -> false
    }

    private fun rejectWindowFunction(expression: Expression, clause: String) {
        if (containsWindowFunction(expression)) throw DiskExecutorException("window functions are not allowed in $clause")
    }

    private fun collectWindowExpressions(expression: Expression): List<WindowExpression> = when (expression) {
        is WindowExpression -> listOf(expression)
        is BinaryExpression -> collectWindowExpressions(expression.left) + collectWindowExpressions(expression.right)
        is UnaryExpression -> collectWindowExpressions(expression.operand)
        is CallExpression -> expression.arguments.flatMap { collectWindowExpressions(it) }
        is IsNullExpression -> collectWindowExpressions(expression.operand)
        is InExpression -> collectWindowExpressions(expression.operand) + expression.values.flatMap { collectWindowExpressions(it) }
        is BetweenExpression ->
            collectWindowExpressions(expression.operand) + collectWindowExpressions(expression.low) + collectWindowExpressions(expression.high)
        is LikeExpression -> collectWindowExpressions(expression.operand) + collectWindowExpressions(expression.pattern)
        is CaseExpression ->
            (expression.operand?.let { collectWindowExpressions(it) } ?: emptyList()) +
                expression.branches.flatMap { collectWindowExpressions(it.condition) + collectWindowExpressions(it.result) } +
                (expression.elseExpression?.let { collectWindowExpressions(it) } ?: emptyList())
        is CastExpression -> collectWindowExpressions(expression.operand)
        is CollateExpression -> collectWindowExpressions(expression.operand)
        else -> emptyList()
    }

    private fun collectWindowExpressions(projection: Projection, orderKeys: List<OrderKey>): List<WindowExpression> {
        val projectionWindows = (projection as? ExpressionProjection)?.items?.flatMap { collectWindowExpressions(it.expression) } ?: emptyList()
        val orderWindows = orderKeys.flatMap { collectWindowExpressions(it.expression) }
        return (projectionWindows + orderWindows).distinct()
    }

    private fun validateWindows(windowExpressions: List<WindowExpression>, aggregated: Boolean) {
        if (aggregated) {
            throw DiskExecutorException("window functions cannot be combined with GROUP BY or aggregate functions in the same query")
        }
        for (window in windowExpressions) {
            if ((window.kind == WindowFunctionKind.Rank || window.kind == WindowFunctionKind.DenseRank) && window.orderBy.isEmpty()) {
                throw DiskExecutorException("${window.name}() OVER (...) requires an ORDER BY clause")
            }
            if (window.frame != null && window.kind !in FRAME_CAPABLE_KINDS) {
                throw DiskExecutorException("${window.name}() cannot be used with a ROWS/RANGE/GROUPS frame clause")
            }
            val nested = listOfNotNull(
                window.argument, window.offset, window.defaultValue,
                window.frame?.start?.offset, window.frame?.end?.offset
            ) + window.partitionBy + window.orderBy.map { it.expression }
            if (nested.any { containsWindowFunction(it) }) {
                throw DiskExecutorException("window functions cannot be nested")
            }
        }
    }

    private fun computeWindowValues(
        window: WindowExpression,
        combined: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value,
        declaredCollationOf: (ColumnExpression) -> Collation? = { null }
    ): List<Value> {
        val values = arrayOfNulls<Value>(combined.size)
        val partitions = combined.indices.groupBy { index -> window.partitionBy.map { evaluateRow(it, combined[index]) } }
        for (memberIndices in partitions.values) {
            val ordered = if (window.orderBy.isEmpty()) {
                memberIndices
            } else {
                memberIndices.sortedWith(Comparator { first, second ->
                    var result = 0
                    for (key in window.orderBy) {
                        val comparison = compareOrderKeyValues(
                            evaluateRow(key.expression, combined[first]),
                            evaluateRow(key.expression, combined[second]),
                            key,
                            declaredCollationOf
                        )
                        if (comparison != 0) {
                            result = comparison
                            break
                        }
                    }
                    result
                })
            }
            assignWindowValues(window, ordered, combined, values, evaluateRow)
        }
        return values.map { it ?: error("window value not computed for every row") }
    }

    private fun assignWindowValues(
        window: WindowExpression,
        ordered: List<Int>,
        combined: List<List<Value>>,
        out: Array<Value?>,
        evaluateRow: (Expression, List<Value>) -> Value
    ) {
        when (window.kind) {
            WindowFunctionKind.RowNumber ->
                ordered.forEachIndexed { position, index -> out[index] = Value.IntegerValue((position + 1).toLong()) }
            WindowFunctionKind.Rank, WindowFunctionKind.DenseRank -> {
                var rank = 0
                var denseRank = 0
                var previousKey: List<Value>? = null
                ordered.forEachIndexed { position, index ->
                    val key = window.orderBy.map { evaluateRow(it.expression, combined[index]) }
                    if (previousKey == null || key != previousKey) {
                        rank = position + 1
                        denseRank++
                        previousKey = key
                    }
                    out[index] = Value.IntegerValue((if (window.kind == WindowFunctionKind.Rank) rank else denseRank).toLong())
                }
            }
            WindowFunctionKind.Aggregate -> assignAggregateWindowValues(window, ordered, combined, out, evaluateRow)
            WindowFunctionKind.Lag -> assignOffsetWindowValues(window, ordered, combined, out, direction = -1, evaluateRow)
            WindowFunctionKind.Lead -> assignOffsetWindowValues(window, ordered, combined, out, direction = 1, evaluateRow)
            WindowFunctionKind.FirstValue -> {
                val frame = window.frame
                if (frame == null) {
                    val firstValue = evaluateRow(window.argument!!, combined[ordered.first()])
                    ordered.forEach { index -> out[index] = firstValue }
                } else {
                    ordered.indices.forEach { position ->
                        val members = frameMembers(window, position, ordered, combined, evaluateRow)
                        out[ordered[position]] =
                            if (members.isEmpty()) Value.NullValue else evaluateRow(window.argument!!, combined[ordered[members.first()]])
                    }
                }
            }
            WindowFunctionKind.LastValue -> {
                val frame = window.frame
                if (frame == null) {
                    for (range in peerGroupRanges(window.orderBy, ordered, combined, evaluateRow)) {
                        val value = evaluateRow(window.argument!!, combined[ordered[range.last]])
                        for (position in range) out[ordered[position]] = value
                    }
                } else {
                    ordered.indices.forEach { position ->
                        val members = frameMembers(window, position, ordered, combined, evaluateRow)
                        out[ordered[position]] =
                            if (members.isEmpty()) Value.NullValue else evaluateRow(window.argument!!, combined[ordered[members.last()]])
                    }
                }
            }
            WindowFunctionKind.Ntile -> assignNtileWindowValues(window, ordered, combined, out, evaluateRow)
        }
    }

    private fun assignAggregateWindowValues(
        window: WindowExpression,
        ordered: List<Int>,
        combined: List<List<Value>>,
        out: Array<Value?>,
        evaluateRow: (Expression, List<Value>) -> Value
    ) {
        val frame = window.frame
        if (frame == null) {
            for (range in peerGroupRanges(window.orderBy, ordered, combined, evaluateRow)) {
                val value = aggregateWindowValue(window, (0..range.last).map { combined[ordered[it]] }, evaluateRow)
                for (position in range) out[ordered[position]] = value
            }
        } else {
            ordered.indices.forEach { position ->
                val members = frameMembers(window, position, ordered, combined, evaluateRow)
                out[ordered[position]] = aggregateWindowValue(window, members.map { combined[ordered[it]] }, evaluateRow)
            }
        }
    }

    private fun frameMembers(
        window: WindowExpression,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value
    ): List<Int> {
        val range = frameRange(window, position, ordered, combined, evaluateRow)
        if (range.isEmpty()) return emptyList()
        return when (window.frame!!.exclude) {
            FrameExclude.NoOthers -> range.toList()
            FrameExclude.CurrentRow -> range.filter { it != position }
            FrameExclude.Group -> {
                val peer = peerBoundary(window.orderBy, position, ordered, combined, isStart = true, evaluateRow)..
                    peerBoundary(window.orderBy, position, ordered, combined, isStart = false, evaluateRow)
                range.filter { it !in peer }
            }
            FrameExclude.Ties -> {
                val peer = peerBoundary(window.orderBy, position, ordered, combined, isStart = true, evaluateRow)..
                    peerBoundary(window.orderBy, position, ordered, combined, isStart = false, evaluateRow)
                range.filter { it == position || it !in peer }
            }
        }
    }

    private fun frameRange(
        window: WindowExpression,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value
    ): IntRange {
        val frame = window.frame!!
        if (frame.unit == FrameUnit.Groups) {
            return groupsFrameRange(frame, window.orderBy, position, ordered, combined, evaluateRow)
        }
        val size = ordered.size
        val start = resolveFrameBound(frame, frame.start, window, position, ordered, combined, isStart = true, evaluateRow)
        val end = resolveFrameBound(frame, frame.end, window, position, ordered, combined, isStart = false, evaluateRow)
        val clampedStart = start.coerceAtLeast(0)
        val clampedEnd = end.coerceAtMost(size - 1)
        return if (clampedStart > clampedEnd) IntRange.EMPTY else clampedStart..clampedEnd
    }

    private fun resolveFrameBound(
        frame: WindowFrame,
        bound: FrameBound,
        window: WindowExpression,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        isStart: Boolean,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int = when (bound.type) {
        FrameBoundType.UnboundedPreceding -> 0
        FrameBoundType.UnboundedFollowing -> ordered.size - 1
        FrameBoundType.CurrentRow ->
            if (frame.unit == FrameUnit.Rows) position else peerBoundary(window.orderBy, position, ordered, combined, isStart, evaluateRow)
        FrameBoundType.Preceding ->
            if (frame.unit == FrameUnit.Rows) {
                position - resolveIntegerFrameOffset(bound, combined[ordered[position]], "ROWS", evaluateRow)
            } else {
                rangeBoundary(bound, window.orderBy, position, ordered, combined, isStart, subtract = true, evaluateRow)
            }
        FrameBoundType.Following ->
            if (frame.unit == FrameUnit.Rows) {
                position + resolveIntegerFrameOffset(bound, combined[ordered[position]], "ROWS", evaluateRow)
            } else {
                rangeBoundary(bound, window.orderBy, position, ordered, combined, isStart, subtract = false, evaluateRow)
            }
    }

    private fun resolveIntegerFrameOffset(
        bound: FrameBound,
        row: List<Value>,
        unit: String,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int {
        val value = evaluateRow(bound.offset!!, row)
        val amount = (value as? Value.IntegerValue)?.value ?: throw DiskExecutorException("$unit frame offset must be an integer")
        if (amount < 0) throw DiskExecutorException("$unit frame offset must not be negative")
        return amount.toInt()
    }

    private fun groupsFrameRange(
        frame: WindowFrame,
        orderBy: List<OrderKey>,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value
    ): IntRange {
        val groups = peerGroupRanges(orderBy, ordered, combined, evaluateRow)
        val currentGroup = groups.indexOfFirst { position in it }
        val row = combined[ordered[position]]
        val startGroup = resolveGroupBound(frame.start, currentGroup, groups.size, row, evaluateRow).coerceAtLeast(0)
        val endGroup = resolveGroupBound(frame.end, currentGroup, groups.size, row, evaluateRow).coerceAtMost(groups.size - 1)
        return if (startGroup > endGroup) IntRange.EMPTY else groups[startGroup].first..groups[endGroup].last
    }

    private fun resolveGroupBound(
        bound: FrameBound,
        currentGroup: Int,
        groupCount: Int,
        row: List<Value>,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int = when (bound.type) {
        FrameBoundType.UnboundedPreceding -> 0
        FrameBoundType.UnboundedFollowing -> groupCount - 1
        FrameBoundType.CurrentRow -> currentGroup
        FrameBoundType.Preceding -> currentGroup - resolveIntegerFrameOffset(bound, row, "GROUPS", evaluateRow)
        FrameBoundType.Following -> currentGroup + resolveIntegerFrameOffset(bound, row, "GROUPS", evaluateRow)
    }

    private fun peerBoundary(
        orderBy: List<OrderKey>,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        isStart: Boolean,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int {
        if (orderBy.isEmpty()) return if (isStart) 0 else ordered.size - 1
        fun keyAt(index: Int) = orderBy.map { evaluateRow(it.expression, combined[ordered[index]]) }
        val key = keyAt(position)
        var boundary = position
        if (isStart) {
            while (boundary > 0 && keyAt(boundary - 1) == key) boundary--
        } else {
            while (boundary < ordered.size - 1 && keyAt(boundary + 1) == key) boundary++
        }
        return boundary
    }

    private fun rangeBoundary(
        bound: FrameBound,
        orderBy: List<OrderKey>,
        position: Int,
        ordered: List<Int>,
        combined: List<List<Value>>,
        isStart: Boolean,
        subtract: Boolean,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int {
        if (orderBy.size != 1) {
            throw DiskExecutorException("a RANGE frame with a PRECEDING/FOLLOWING bound requires exactly one ORDER BY column")
        }
        val key = orderBy.single()
        val current = rangeLogicalValue(key, combined[ordered[position]], evaluateRow)
        val offset = resolveRangeFrameOffset(bound, combined[ordered[position]], evaluateRow)
        val threshold = if (subtract) current - offset else current + offset
        return if (isStart) {
            rangeLowerBound(ordered, combined, key, threshold, evaluateRow)
        } else {
            rangeUpperBound(ordered, combined, key, threshold, evaluateRow)
        }
    }

    private fun rangeLowerBound(
        ordered: List<Int>,
        combined: List<List<Value>>,
        key: OrderKey,
        threshold: BigDecimal,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int {
        var low = 0
        var high = ordered.size
        while (low < high) {
            val mid = (low + high) / 2
            if (rangeLogicalValue(key, combined[ordered[mid]], evaluateRow) >= threshold) high = mid else low = mid + 1
        }
        return low
    }

    private fun rangeUpperBound(
        ordered: List<Int>,
        combined: List<List<Value>>,
        key: OrderKey,
        threshold: BigDecimal,
        evaluateRow: (Expression, List<Value>) -> Value
    ): Int {
        var low = 0
        var high = ordered.size
        while (low < high) {
            val mid = (low + high) / 2
            if (rangeLogicalValue(key, combined[ordered[mid]], evaluateRow) <= threshold) low = mid + 1 else high = mid
        }
        return low - 1
    }

    private fun rangeLogicalValue(key: OrderKey, row: List<Value>, evaluateRow: (Expression, List<Value>) -> Value): BigDecimal {
        val magnitude = rangeMagnitude(evaluateRow(key.expression, row))
        return if (key.descending) magnitude.negate() else magnitude
    }

    private fun rangeMagnitude(value: Value): BigDecimal = when (value) {
        is Value.IntegerValue -> BigDecimal(value.value)
        is Value.DecimalValue -> value.value
        is Value.RealValue -> BigDecimal.valueOf(value.value)
        is Value.DateValue -> BigDecimal(value.value.toEpochDay())
        else -> throw DiskExecutorException("a RANGE frame requires the ORDER BY column to be numeric or DATE")
    }

    private fun resolveRangeFrameOffset(bound: FrameBound, row: List<Value>, evaluateRow: (Expression, List<Value>) -> Value): BigDecimal {
        val offset = when (val value = evaluateRow(bound.offset!!, row)) {
            is Value.IntegerValue -> BigDecimal(value.value)
            is Value.DecimalValue -> value.value
            is Value.RealValue -> BigDecimal.valueOf(value.value)
            else -> throw DiskExecutorException("RANGE frame offset must be numeric")
        }
        if (offset.signum() < 0) throw DiskExecutorException("RANGE frame offset must not be negative")
        return offset
    }

    private fun peerGroupRanges(
        orderBy: List<OrderKey>,
        ordered: List<Int>,
        combined: List<List<Value>>,
        evaluateRow: (Expression, List<Value>) -> Value
    ): List<IntRange> {
        if (orderBy.isEmpty()) return listOf(ordered.indices)
        val keys = ordered.map { index -> orderBy.map { key -> evaluateRow(key.expression, combined[index]) } }
        val ranges = mutableListOf<IntRange>()
        var i = 0
        while (i < ordered.size) {
            var j = i
            while (j < ordered.size && keys[j] == keys[i]) j++
            ranges.add(i until j)
            i = j
        }
        return ranges
    }

    private fun aggregateWindowValue(window: WindowExpression, rows: List<List<Value>>, evaluateRow: (Expression, List<Value>) -> Value): Value =
        AggregateFunctions.compute(window.name, window.argument, window.star, rows, evaluateRow)

    private fun assignOffsetWindowValues(
        window: WindowExpression,
        ordered: List<Int>,
        combined: List<List<Value>>,
        out: Array<Value?>,
        direction: Int,
        evaluateRow: (Expression, List<Value>) -> Value
    ) {
        ordered.forEachIndexed { position, index ->
            val offset = resolveOffset(window, combined[index], evaluateRow)
            val targetPosition = position + direction * offset
            out[index] = if (targetPosition in ordered.indices) {
                evaluateRow(window.argument!!, combined[ordered[targetPosition]])
            } else {
                window.defaultValue?.let { evaluateRow(it, combined[index]) } ?: Value.NullValue
            }
        }
    }

    private fun resolveOffset(window: WindowExpression, row: List<Value>, evaluateRow: (Expression, List<Value>) -> Value): Int {
        val expression = window.offset ?: return 1
        val value = evaluateRow(expression, row)
        val amount = (value as? Value.IntegerValue)?.value ?: throw DiskExecutorException("${window.name}() offset must be an integer")
        if (amount < 0) throw DiskExecutorException("${window.name}() offset must not be negative")
        return amount.toInt()
    }

    private fun assignNtileWindowValues(
        window: WindowExpression,
        ordered: List<Int>,
        combined: List<List<Value>>,
        out: Array<Value?>,
        evaluateRow: (Expression, List<Value>) -> Value
    ) {
        val bucketValue = evaluateRow(window.argument!!, combined[ordered.first()])
        val buckets = (bucketValue as? Value.IntegerValue)?.value ?: throw DiskExecutorException("${window.name}() bucket count must be an integer")
        if (buckets <= 0) throw DiskExecutorException("${window.name}() bucket count must be positive")
        val bucketCount = buckets.toInt()
        val base = ordered.size / bucketCount
        val remainder = ordered.size % bucketCount
        var position = 0
        for (bucket in 1..bucketCount) {
            val size = base + if (bucket <= remainder) 1 else 0
            repeat(size) {
                out[ordered[position]] = Value.IntegerValue(bucket.toLong())
                position++
            }
        }
    }

    private fun candidateRows(table: DiskTable, txn: Long, where: Expression?, alias: String?): List<Pair<RecordId, Row>> =
        when (val plan = accessPlanner.plan(table.schema, table.secondaryIndexes, where, alias)) {
            DiskAccessPlan.FullScan -> table.scan(txn)
            is DiskAccessPlan.PrimaryKeyPoint -> listOfNotNull(table.findByPrimaryKey(txn, plan.key))
            is DiskAccessPlan.PrimaryKeyRange -> table.rangeByPrimaryKey(txn, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive)
            is DiskAccessPlan.CompositePrimaryKeyPoint -> listOfNotNull(table.findByPrimaryKey(txn, plan.keys))
            is DiskAccessPlan.CompositePrimaryKeyRange ->
                table.rangeByPrimaryKey(txn, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive, plan.prefix)
            is DiskAccessPlan.Secondary -> table.findBySecondary(txn, plan.indexName, plan.keys)
            is DiskAccessPlan.SecondaryRange ->
                table.rangeBySecondary(txn, plan.indexName, plan.prefix, plan.lower, plan.lowerInclusive, plan.upper, plan.upperInclusive)
        }

    private fun executeUpdate(statement: UpdateStatement, triggerContext: TriggerRowContext? = null): StatementResult {
        catalog.temporaryTableOrNull(statement.tableName)?.let { return executeTemporaryUpdate(statement, it) }
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val assignments = buildAssignments(schema, statement)
        return withStatementTransaction { txn ->
            withTriggerAtomicity(schema.name, txn, triggerContext) {
                executeUpdateInTxn(statement, assignments, table, schema, txn, triggerContext)
            }
        }
    }

    private fun executeTemporaryUpdate(statement: UpdateStatement, table: Table): StatementResult {
        val schema = table.schema
        val assignments = buildAssignments(schema, statement)
        return withStatementTransaction { txn ->
            val subqueries = subqueryRunnerFor(txn)
            val oldRows = table.rows.toList()
            val touchedRows = mutableListOf<Pair<Row, Row>>()
            val newRows = oldRows.map { row ->
                val matches = statement.where == null ||
                    isTrue(Evaluator(composeResolver(rowResolver(schema, row), null), subqueries).evaluate(statement.where))
                if (!matches) return@map row
                val values = row.values.toMutableList()
                val evaluator = Evaluator(composeResolver(rowResolver(schema, row), null), subqueries, this)
                for ((index, expression) in assignments) {
                    val column = schema.columns[index]
                    values[index] = if (expression != null) {
                        coerceForColumn(evaluator.evaluate(expression), column)
                    } else {
                        column.default?.let { coerceForColumn(evaluator.evaluate(it), column) } ?: Value.NullValue
                    }
                }
                applyGeneratedColumns(schema, values, subqueries)
                validateTemporaryNotNull(schema, values)
                val newRow = Row(values)
                touchedRows.add(row to newRow)
                newRow
            }
            for (row in newRows) enforceChecks(schema, row, subqueries)
            enforceTemporaryPrimaryKeyUniqueness(schema, newRows)
            enforceTemporaryUniqueConstraints(schema, newRows)
            table.rows.clear()
            table.rows.addAll(newRows)
            val returning = statement.returning?.let { evaluateReturning(schema, touchedRows.map { it.second }, it, subqueries) }
            UpdateResult(touchedRows.size, returning)
        }
    }

    private fun executeUpdateInTxn(
        statement: UpdateStatement,
        assignments: List<Pair<Int, Expression?>>,
        table: DiskTable,
        schema: TableSchema,
        txn: Long,
        triggerContext: TriggerRowContext?
    ): StatementResult {
        val subqueries = subqueryRunnerFor(txn)
        val matches = candidateRows(table, txn, statement.where, alias = null).filter { (_, row) ->
            statement.where == null ||
                isTrue(Evaluator(composeResolver(rowResolver(schema, row), triggerContext), subqueries).evaluate(statement.where))
        }
        val planned = matches.map { (rid, row) ->
            val evaluator = Evaluator(composeResolver(rowResolver(schema, row), triggerContext), subqueries, this)
            val values = row.values.toMutableList()
            for ((index, expression) in assignments) {
                val column = schema.columns[index]
                values[index] = if (expression != null) {
                    coerceForColumn(evaluator.evaluate(expression), column)
                } else {
                    column.default?.let { coerceForColumn(evaluator.evaluate(it), column) } ?: Value.NullValue
                }
            }
            applyGeneratedColumns(schema, values, subqueries)
            Triple(rid, row, Row(values))
        }
        val newRows = planned.map { it.third }
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, planned.map { it.second to it.third }, txn)
        for ((_, _, newRow) in planned) {
            DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, newRow.values, uncommittedSiblings = newRows)
            enforceChecks(schema, newRow, subqueries)
        }
        for (group in schema.enforcedUniqueKeyGroups()) {
            val columnIndices = group.map { schema.columnIndex(it)!! }
            val changedPairs = planned.map { (_, oldRow, newRow) ->
                columnIndices.map { oldRow.values[it] } to columnIndices.map { newRow.values[it] }
            }
            DiskForeignKeyEnforcer.cascadeUpdate(catalog, txn, schema.name, group, changedPairs)
        }
        for ((rid, _, newRow) in planned) table.update(txn, rid, newRow)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, planned.map { it.second to it.third }, txn)
        val returning = statement.returning?.let { evaluateReturning(schema, planned.map { triple -> triple.third }, it, subqueries) }
        return UpdateResult(planned.size, returning)
    }

    private fun executeDelete(statement: DeleteStatement, triggerContext: TriggerRowContext? = null): StatementResult {
        catalog.temporaryTableOrNull(statement.tableName)?.let { return executeTemporaryDelete(statement, it) }
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        return withStatementTransaction { txn ->
            withTriggerAtomicity(schema.name, txn, triggerContext) {
                executeDeleteInTxn(statement, table, schema, txn, triggerContext)
            }
        }
    }

    private fun executeDeleteInTxn(
        statement: DeleteStatement, table: DiskTable, schema: TableSchema, txn: Long, triggerContext: TriggerRowContext?
    ): StatementResult {
        val subqueries = subqueryRunnerFor(txn)
        val matches = candidateRows(table, txn, statement.where, alias = null).filter { (_, row) ->
            statement.where == null ||
                isTrue(Evaluator(composeResolver(rowResolver(schema, row), triggerContext), subqueries).evaluate(statement.where))
        }
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Delete, matches.map { it.second to null }, txn)
        DiskForeignKeyEnforcer.cascadeDelete(catalog, txn, schema.name, matches.map { it.second })
        for ((rid, _) in matches) table.delete(txn, rid)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Delete, matches.map { it.second to null }, txn)
        val returning = statement.returning?.let { evaluateReturning(schema, matches.map { it.second }, it, subqueries) }
        return DeleteResult(matches.size, returning)
    }

    private fun executeTemporaryDelete(statement: DeleteStatement, table: Table): StatementResult {
        val schema = table.schema
        return withStatementTransaction { txn ->
            val subqueries = subqueryRunnerFor(txn)
            val deletedRows = if (statement.where == null) {
                table.rows.toList()
            } else {
                table.rows.filter { row ->
                    isTrue(Evaluator(composeResolver(rowResolver(schema, row), null), subqueries).evaluate(statement.where))
                }
            }
            table.rows.removeAll(deletedRows)
            val returning = statement.returning?.let { evaluateReturning(schema, deletedRows, it, subqueries) }
            DeleteResult(deletedRows.size, returning)
        }
    }

    private fun executeMerge(statement: MergeStatement): StatementResult {
        val table = catalog.table(statement.targetTable)
        val schema = table.schema
        return withStatementTransaction { txn ->
            withTriggerAtomicity(schema.name, txn, null) {
                executeMergeInner(statement, table, schema, txn)
            }
        }
    }

    private fun executeMergeInner(statement: MergeStatement, table: DiskTable, schema: TableSchema, txn: Long): StatementResult {
        val targetLabel = statement.targetAlias ?: statement.targetTable
        val (sourceSchema, sourceRows) = resolveMergeSource(statement.source, txn)
        val sourceLabel = sourceLabel(statement.source)
        if (targetLabel.equals(sourceLabel, ignoreCase = true)) {
            throw DiskExecutorException("MERGE target and source cannot share the same name or alias: $targetLabel")
        }
        checkMergeColumns(statement, schema, targetLabel, sourceSchema, sourceLabel)

        val subqueries = subqueryRunnerFor(txn)
        val originalTargetRows = table.scan(txn)
        val affectedRids = mutableSetOf<RecordId>()
        val returnedRows = mutableListOf<Pair<Row, String>>()
        var inserted = 0
        var updated = 0
        var deleted = 0

        for (sourceRow in sourceRows) {
            val matchedTargets = originalTargetRows.filter { (_, targetRow) ->
                val resolver = mergeResolver(schema, targetLabel, targetRow, sourceSchema, sourceLabel, sourceRow)
                isTrue(Evaluator(resolver, subqueries).evaluate(statement.onCondition))
            }
            if (matchedTargets.isNotEmpty()) {
                for ((targetRid, targetRow) in matchedTargets) {
                    val resolver = mergeResolver(schema, targetLabel, targetRow, sourceSchema, sourceLabel, sourceRow)
                    val clause = statement.whenClauses.firstOrNull { candidate ->
                        (candidate is MergeMatchedUpdate || candidate is MergeMatchedDelete) &&
                            (candidate.condition == null || isTrue(Evaluator(resolver, subqueries).evaluate(candidate.condition!!)))
                    } ?: continue
                    if (!affectedRids.add(targetRid)) {
                        throw DiskExecutorException("MERGE command cannot affect a target row a second time")
                    }
                    when (clause) {
                        is MergeMatchedUpdate -> {
                            val updatedRow = applyMergeUpdate(schema, table, txn, targetRid, targetRow, clause, resolver, subqueries)
                            returnedRows.add(updatedRow to "UPDATE")
                            updated++
                        }
                        is MergeMatchedDelete -> {
                            val deletedRow = applyMergeDelete(schema, table, txn, targetRid, targetRow)
                            returnedRows.add(deletedRow to "DELETE")
                            deleted++
                        }
                        else -> error("unreachable: filtered to MATCHED clauses above")
                    }
                }
                continue
            }
            val resolver = notMatchedResolver(sourceSchema, sourceLabel, sourceRow, targetLabel)
            val clause = statement.whenClauses.firstOrNull { candidate ->
                candidate is MergeNotMatchedInsert &&
                    (candidate.condition == null || isTrue(Evaluator(resolver, subqueries).evaluate(candidate.condition!!)))
            } as? MergeNotMatchedInsert ?: continue
            val insertedRow = applyMergeInsert(schema, table, txn, clause, resolver, subqueries)
            returnedRows.add(insertedRow to "INSERT")
            inserted++
        }
        val returning = statement.returning?.let { evaluateMergeReturning(schema, returnedRows, it, subqueries) }
        return MergeResult(inserted, updated, deleted, returning)
    }

    private fun evaluateMergeReturning(
        schema: TableSchema,
        actionedRows: List<Pair<Row, String>>,
        projection: Projection,
        subqueries: SubqueryRunner
    ): SelectResult {
        val columns = when (projection) {
            is StarProjection -> schema.columns.map { it.name }
            is ExpressionProjection -> projection.items.mapIndexed { index, item ->
                checkReturningColumns(item.expression, schema)
                columnLabel(item, index)
            }
        }
        val resultRows = actionedRows.map { (row, action) ->
            when (projection) {
                is StarProjection -> row.values.toList()
                is ExpressionProjection -> {
                    val evaluator = Evaluator(rowResolver(schema, row), subqueries, mergeAction = action)
                    projection.items.map { evaluator.evaluate(it.expression) }
                }
            }
        }
        return SelectResult(columns, resultRows)
    }

    private fun applyMergeUpdate(
        schema: TableSchema,
        table: DiskTable,
        txn: Long,
        targetRid: RecordId,
        targetRow: Row,
        clause: MergeMatchedUpdate,
        resolver: ColumnResolver,
        subqueries: SubqueryRunner
    ): Row {
        val assignmentEvaluator = Evaluator(resolver, subqueries, this)
        val values = targetRow.values.toMutableList()
        for (assignment in clause.assignments) {
            val index = schema.columnIndex(assignment.columnName)
                ?: throw DiskExecutorException("no such column: ${assignment.columnName}")
            val column = schema.columns[index]
            rejectGeneratedAssignment(column, explicitValueGiven = assignment.value != null)
            values[index] = if (assignment.value != null) {
                coerceForColumn(assignmentEvaluator.evaluate(assignment.value), column)
            } else {
                column.default?.let { coerceForColumn(assignmentEvaluator.evaluate(it), column) } ?: Value.NullValue
            }
        }
        applyGeneratedColumns(schema, values, subqueries)
        val updatedRow = Row(values)
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, listOf(targetRow to updatedRow), txn)
        DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, updatedRow.values, uncommittedSiblings = emptyList())
        enforceChecks(schema, updatedRow, subqueries)
        for (group in schema.enforcedUniqueKeyGroups()) {
            val columnIndices = group.map { schema.columnIndex(it)!! }
            val oldKey = columnIndices.map { targetRow.values[it] }
            val newKey = columnIndices.map { updatedRow.values[it] }
            DiskForeignKeyEnforcer.cascadeUpdate(catalog, txn, schema.name, group, listOf(oldKey to newKey))
        }
        table.update(txn, targetRid, updatedRow)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, listOf(targetRow to updatedRow), txn)
        return updatedRow
    }

    private fun applyMergeDelete(schema: TableSchema, table: DiskTable, txn: Long, targetRid: RecordId, targetRow: Row): Row {
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Delete, listOf(targetRow to null), txn)
        DiskForeignKeyEnforcer.cascadeDelete(catalog, txn, schema.name, listOf(targetRow))
        table.delete(txn, targetRid)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Delete, listOf(targetRow to null), txn)
        return targetRow
    }

    private fun applyMergeInsert(
        schema: TableSchema,
        table: DiskTable,
        txn: Long,
        clause: MergeNotMatchedInsert,
        resolver: ColumnResolver,
        subqueries: SubqueryRunner
    ): Row {
        val targetIndices = resolveInsertTargets(schema, clause.columns)
        val evaluator = Evaluator(resolver, subqueries, this)
        val values = MutableList<Value>(schema.columns.size) { Value.NullValue }
        val touched = BooleanArray(schema.columns.size)
        for ((position, expression) in clause.values.withIndex()) {
            val columnIndex = targetIndices[position]
            if (expression != null) {
                rejectGeneratedAssignment(schema.columns[columnIndex], explicitValueGiven = true)
                values[columnIndex] = coerceForColumn(evaluator.evaluate(expression), schema.columns[columnIndex])
                touched[columnIndex] = true
            }
        }
        for ((index, column) in schema.columns.withIndex()) {
            val default = column.default
            if (!touched[index] && default != null) {
                values[index] = coerceForColumn(evaluator.evaluate(default), column)
            }
        }
        applyGeneratedColumns(schema, values, subqueries)
        val row = Row(values)
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, listOf(null to row), txn)
        DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, row.values, uncommittedSiblings = emptyList())
        enforceChecks(schema, row, subqueries)
        table.insert(txn, row)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, listOf(null to row), txn)
        return row
    }

    private fun resolveMergeSource(item: FromItem, txn: Long): Pair<TableSchema, List<Row>> {
        val source = resolveFromItem(item, txn)
        return source.schema to source.scan(txn).map { Row(it) }
    }

    private fun mergeResolver(
        targetSchema: TableSchema,
        targetLabel: String,
        targetRow: Row,
        sourceSchema: TableSchema,
        sourceLabel: String,
        sourceRow: Row
    ): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val qualifier = expression.qualifier
            if (qualifier != null) {
                return when {
                    qualifier.equals(sourceLabel, ignoreCase = true) ->
                        sourceRow.values[sourceSchema.columnIndex(expression.name) ?: throw DiskExecutorException("no such column: ${expression.name}")]
                    qualifier.equals(targetLabel, ignoreCase = true) ->
                        targetRow.values[targetSchema.columnIndex(expression.name) ?: throw DiskExecutorException("no such column: ${expression.name}")]
                    else -> throw DiskExecutorException("no such table or alias: $qualifier")
                }
            }
            val targetIndex = targetSchema.columnIndex(expression.name)
            val sourceIndex = sourceSchema.columnIndex(expression.name)
            if (targetIndex != null && sourceIndex != null) {
                throw DiskExecutorException("column reference '${expression.name}' is ambiguous between $targetLabel and $sourceLabel")
            }
            targetIndex?.let { return targetRow.values[it] }
            sourceIndex?.let { return sourceRow.values[it] }
            throw DiskExecutorException("no such column: ${expression.name}")
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? {
            val qualifier = expression.qualifier
            if (qualifier != null) {
                return when {
                    qualifier.equals(sourceLabel, ignoreCase = true) -> sourceSchema.column(expression.name)?.collation
                    qualifier.equals(targetLabel, ignoreCase = true) -> targetSchema.column(expression.name)?.collation
                    else -> null
                }
            }
            val targetColumn = targetSchema.column(expression.name)
            val sourceColumn = sourceSchema.column(expression.name)
            return if (targetColumn != null && sourceColumn == null) targetColumn.collation
            else if (sourceColumn != null && targetColumn == null) sourceColumn.collation
            else null
        }
    }

    private fun notMatchedResolver(
        sourceSchema: TableSchema,
        sourceLabel: String,
        sourceRow: Row,
        targetLabel: String
    ): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val qualifier = expression.qualifier
            if (qualifier != null && qualifier.equals(targetLabel, ignoreCase = true)) {
                throw DiskExecutorException("no target row is available in a WHEN NOT MATCHED clause: $targetLabel.${expression.name}")
            }
            if (qualifier != null && !qualifier.equals(sourceLabel, ignoreCase = true)) {
                throw DiskExecutorException("no such table or alias: $qualifier")
            }
            val index = sourceSchema.columnIndex(expression.name) ?: throw DiskExecutorException("no such column: ${expression.name}")
            return sourceRow.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = sourceSchema.column(expression.name)?.collation
    }

    private fun checkMergeColumns(
        statement: MergeStatement,
        targetSchema: TableSchema,
        targetLabel: String,
        sourceSchema: TableSchema,
        sourceLabel: String
    ) {
        checkMergeExpressionColumns(statement.onCondition, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = true)
        for (clause in statement.whenClauses) {
            when (clause) {
                is MergeMatchedUpdate -> {
                    clause.condition?.let { checkMergeExpressionColumns(it, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = true) }
                    for (assignment in clause.assignments) {
                        if (targetSchema.columnIndex(assignment.columnName) == null) {
                            throw DiskExecutorException("no such column: ${assignment.columnName}")
                        }
                        assignment.value?.let { checkMergeExpressionColumns(it, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = true) }
                    }
                }
                is MergeMatchedDelete ->
                    clause.condition?.let { checkMergeExpressionColumns(it, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = true) }
                is MergeNotMatchedInsert -> {
                    clause.condition?.let { checkMergeExpressionColumns(it, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = false) }
                    val targetIndices = resolveInsertTargets(targetSchema, clause.columns)
                    if (clause.values.size != targetIndices.size) {
                        throw DiskExecutorException(
                            "table ${targetSchema.name} expects ${targetIndices.size} values but ${clause.values.size} were supplied"
                        )
                    }
                    clause.values.forEach { value ->
                        value?.let { checkMergeExpressionColumns(it, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget = false) }
                    }
                }
            }
        }
    }

    private fun checkMergeExpressionColumns(
        expression: Expression,
        targetSchema: TableSchema,
        targetLabel: String,
        sourceSchema: TableSchema,
        sourceLabel: String,
        allowTarget: Boolean
    ) {
        fun recurse(inner: Expression) =
            checkMergeExpressionColumns(inner, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget)
        when (expression) {
            is ColumnExpression -> checkMergeColumnReference(expression, targetSchema, targetLabel, sourceSchema, sourceLabel, allowTarget)
            is BinaryExpression -> { recurse(expression.left); recurse(expression.right) }
            is UnaryExpression -> recurse(expression.operand)
            is FunctionExpression -> throw DiskExecutorException("aggregate functions are not allowed here")
            is WindowExpression -> throw DiskExecutorException("window functions are not allowed here")
            is LiteralExpression -> Unit
            is CallExpression -> expression.arguments.forEach { recurse(it) }
            is IsNullExpression -> recurse(expression.operand)
            is InExpression -> {
                recurse(expression.operand)
                expression.values.forEach { recurse(it) }
            }
            is BetweenExpression -> {
                recurse(expression.operand)
                recurse(expression.low)
                recurse(expression.high)
            }
            is LikeExpression -> {
                recurse(expression.operand)
                recurse(expression.pattern)
            }
            is CaseExpression -> {
                expression.operand?.let { recurse(it) }
                expression.branches.forEach { recurse(it.condition); recurse(it.result) }
                expression.elseExpression?.let { recurse(it) }
            }
            is ScalarSubqueryExpression -> Unit
            is ExistsExpression -> Unit
            is InSubqueryExpression -> recurse(expression.operand)
            is CastExpression -> recurse(expression.operand)
            is CollateExpression -> recurse(expression.operand)
            is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> Unit
        }
    }

    private fun checkMergeColumnReference(
        expression: ColumnExpression,
        targetSchema: TableSchema,
        targetLabel: String,
        sourceSchema: TableSchema,
        sourceLabel: String,
        allowTarget: Boolean
    ) {
        val qualifier = expression.qualifier
        if (qualifier != null) {
            val matchesSource = qualifier.equals(sourceLabel, ignoreCase = true)
            val matchesTargetLabel = qualifier.equals(targetLabel, ignoreCase = true)
            if (matchesTargetLabel && !allowTarget) {
                throw DiskExecutorException("no target row is available in a WHEN NOT MATCHED clause: $targetLabel.${expression.name}")
            }
            if (!matchesSource && !matchesTargetLabel) {
                throw DiskExecutorException("no such table or alias: $qualifier")
            }
            val schema = if (matchesSource) sourceSchema else targetSchema
            if (schema.columnIndex(expression.name) == null) throw DiskExecutorException("no such column: ${expression.name}")
            return
        }
        val inTarget = allowTarget && targetSchema.columnIndex(expression.name) != null
        val inSource = sourceSchema.columnIndex(expression.name) != null
        if (inTarget && inSource) {
            throw DiskExecutorException("column reference '${expression.name}' is ambiguous between $targetLabel and $sourceLabel")
        }
        if (!inTarget && !inSource) throw DiskExecutorException("no such column: ${expression.name}")
    }

    private inline fun <T> runInTransaction(txnId: Long, block: () -> T): T {
        val result = try {
            block()
        } catch (failure: Exception) {
            catalog.abort(txnId)
            throw failure
        }
        catalog.commit(txnId)
        return result
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value

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
            ColumnType.Decimal -> {
                val raw = when (value) {
                    is Value.DecimalValue -> value.value
                    is Value.IntegerValue -> BigDecimal(value.value)
                    is Value.RealValue -> BigDecimal.valueOf(value.value)
                    else -> throw typeMismatch(column, value)
                }
                Value.DecimalValue(DecimalColumns.normalize(raw, column.precision, column.scale, "column ${column.name}"))
            }
            ColumnType.Date -> value as? Value.DateValue ?: throw typeMismatch(column, value)
        }
    }

    private fun typeMismatch(column: Column, value: Value): DiskExecutorException =
        DiskExecutorException("type mismatch: column ${column.name} expects ${column.type.displayName} but got ${value.typeName}")

    companion object {
        private const val MAX_RECURSION_ITERATIONS = 10_000
        private const val MAX_TRIGGER_DEPTH = 100

        private val FRAME_CAPABLE_KINDS = setOf(WindowFunctionKind.Aggregate, WindowFunctionKind.FirstValue, WindowFunctionKind.LastValue)
    }
}

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

    private var triggerDepth = 0

    private val lastSequenceValues = HashMap<Sequence, Long>()

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
            is FunctionExpression, is WindowExpression, is ScalarSubqueryExpression, is ExistsExpression,
            is InSubqueryExpression, is NextValueExpression, is CurrentValueExpression, is MergeActionExpression,
            is ParameterExpression -> Unit
        }
    }

    private fun applyGeneratedColumns(schema: TableSchema, values: MutableList<Value>) {
        if (schema.columns.none { it.generated != null }) return
        val evaluator = Evaluator(schema, Row(values), subqueryRunner)
        for ((index, column) in schema.columns.withIndex()) {
            val generated = column.generated ?: continue
            values[index] = coerceForColumn(evaluator.evaluate(generated), column)
        }
    }

    private fun rejectGeneratedAssignment(column: Column, explicitValueGiven: Boolean) {
        if (explicitValueGiven && column.generated != null) {
            throw KestrelException("cannot assign an explicit value to GENERATED column ${column.name}")
        }
    }

    private fun validateNextValueDefault(default: NextValueExpression, column: Column) {
        catalog.sequence(default.sequenceName)
        if (column.type != ColumnType.Integer) {
            throw KestrelException("NEXTVAL can only be used as a DEFAULT for an INTEGER column, but ${column.name} is ${column.type.displayName}")
        }
    }

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
            !other.equals(tableName, ignoreCase = true) &&
                catalog.tableOrNull(other)!!.schema.foreignKeys.any { fk ->
                    fk.referencedTable.equals(tableName, ignoreCase = true) &&
                        fk.referencedColumns.any { it.equals(action.columnName, ignoreCase = true) }
                }
        }
        if (referencingTables.isNotEmpty()) {
            throw KestrelException(
                "cannot change the type of column ${action.columnName} because table(s) ${referencingTables.joinToString(", ")} " +
                    "have a FOREIGN KEY referencing it; drop the constraint first"
            )
        }
        if (schema.uniqueConstraints.any { unique -> unique.columns.any { it.equals(action.columnName, ignoreCase = true) } }) {
            throw KestrelException(
                "cannot change the type of column ${action.columnName} because it is used by a UNIQUE constraint of $tableName; " +
                    "drop the constraint first"
            )
        }
        val dependentIndexes = catalog.indexesOn(tableName).filter { index ->
            index.definition.columnNames.any { it.equals(action.columnName, ignoreCase = true) }
        }
        if (dependentIndexes.isNotEmpty()) {
            throw KestrelException(
                "cannot change the type of column ${action.columnName} because index(es) " +
                    "${dependentIndexes.joinToString(", ") { it.definition.name }} are defined on it; drop the index first"
            )
        }

        val newColumn = column.copy(type = action.newType, precision = action.precision, scale = action.scale)
        newColumn.default?.let { default ->
            try {
                coerceForColumn(Evaluator().evaluate(default), newColumn)
            } catch (e: KestrelException) {
                throw KestrelException(
                    "cannot change the type of column ${action.columnName}: its DEFAULT expression is not compatible " +
                        "with ${action.newType.displayWith(action.precision, action.scale)} (${e.message})"
                )
            }
        }
        val newSchema = TableSchema(
            schema.name, schema.columns.toMutableList().also { it[columnIndex] = newColumn }, schema.foreignKeys,
            schema.checks, schema.uniqueConstraints, schema.compositePrimaryKey, schema.temporary
        )

        val using = action.using ?: ColumnExpression(action.columnName)
        val newRows = catalog.table(tableName).rows.map { row ->
            val values = row.values.toMutableList()
            values[columnIndex] = coerceForColumn(Evaluator(schema, row, subqueryRunner).evaluate(using), newColumn)
            val newRow = Row(values)
            validateNotNull(newSchema, newRow.values)
            enforceChecks(newSchema, newRow)
            newRow
        }
        catalog.changeColumnType(tableName, action.columnName, newColumn, newRows)
    }

    private fun requireUniqueConstraintName(schema: TableSchema, name: String) {
        val taken = schema.foreignKeys.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.checks.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.uniqueConstraints.any { it.name?.equals(name, ignoreCase = true) == true }
        if (taken) throw KestrelException("constraint '$name' already exists on table ${schema.name}")
    }

    private fun executeAddColumn(tableName: String, column: Column) {
        val table = catalog.table(tableName)
        if (column.generated != null) {
            validateAddGeneratedColumn(table.schema, column)
            catalog.addColumn(tableName, column) { row -> addGeneratedColumnValue(table.schema, column, row) }
            return
        }
        column.default?.let { validateConstantDefault(it) }
        if (column.default is NextValueExpression) {
            throw KestrelException(
                "NEXTVAL cannot be used as an ADD COLUMN DEFAULT - it would give every existing row " +
                    "the same value; CREATE TABLE's DEFAULT evaluates fresh per row instead"
            )
        }
        if (column.notNull && column.default == null && table.rows.isNotEmpty()) {
            throw KestrelException(
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
            throw KestrelException("NOT NULL constraint failed: ${schema.name}.${column.name}")
        }
        return value
    }

    private fun executeCreateIndex(statement: CreateIndexStatement): StatementResult {
        catalog.createIndex(
            IndexDefinition(statement.indexName, statement.tableName, statement.columnNames, statement.unique)
        )
        return CreateIndexResult(statement.indexName)
    }

    private fun executeDropIndex(statement: DropIndexStatement): StatementResult {
        catalog.dropIndex(statement.indexName)
        return DropIndexResult(statement.indexName)
    }

    private fun executeCreateView(statement: CreateViewStatement): StatementResult {
        selectRunner.explain(statement.query)
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
        for (bodyStatement in statement.body) {
            validateTriggerBodyStatementTarget(bodyStatement)
        }
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
            else -> throw KestrelException("trigger body only supports INSERT, UPDATE, or DELETE statements")
        }
    }

    private fun rejectReturning(returning: Projection?) {
        if (returning != null) {
            throw KestrelException("RETURNING is not allowed in a trigger body statement - its result would never be visible to anything")
        }
    }

    private fun validateTriggerRowReferences(expression: Expression, event: TriggerEvent, schema: TableSchema) {
        when (expression) {
            is ColumnExpression -> {
                val qualifier = expression.qualifier
                    ?: throw KestrelException("column '${expression.name}' in trigger WHEN clause must be qualified with NEW or OLD")
                val isNew = qualifier.equals("new", ignoreCase = true)
                val isOld = qualifier.equals("old", ignoreCase = true)
                if (!isNew && !isOld) throw KestrelException("no such table or alias: $qualifier")
                val availableForEvent = when (event) {
                    TriggerEvent.Insert -> isNew
                    TriggerEvent.Delete -> isOld
                    TriggerEvent.Update -> true
                }
                if (!availableForEvent) {
                    throw KestrelException("${if (isNew) "NEW" else "OLD"} is not available in a trigger ON ${event.name.uppercase()}")
                }
                if (schema.columnIndex(expression.name) == null) {
                    throw KestrelException("no such column: ${expression.name}")
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

    private fun executeCreateSequence(statement: CreateSequenceStatement): StatementResult {
        val incrementBy = statement.incrementBy ?: 1L
        if (incrementBy == 0L) throw KestrelException("sequence '${statement.sequenceName}' INCREMENT BY cannot be zero")
        val ascending = incrementBy > 0
        val minValue = statement.minValue ?: if (ascending) 1L else Long.MIN_VALUE
        val maxValue = statement.maxValue ?: if (ascending) Long.MAX_VALUE else -1L
        if (minValue > maxValue) {
            throw KestrelException("sequence '${statement.sequenceName}' MINVALUE ($minValue) must not exceed MAXVALUE ($maxValue)")
        }
        val startValue = statement.startValue ?: if (ascending) minValue else maxValue
        if (startValue < minValue || startValue > maxValue) {
            throw KestrelException(
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
        if (incrementBy == 0L) throw KestrelException("sequence '${statement.sequenceName}' INCREMENT BY cannot be zero")
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
            throw KestrelException("sequence '${statement.sequenceName}' MINVALUE ($minValue) must not exceed MAXVALUE ($maxValue)")
        }
        val cycle = statement.cycle ?: existing.cycle
        val newDefinition = SequenceDefinition(statement.sequenceName, incrementBy, minValue, maxValue, existing.startValue, cycle)
        val restartAt = if (statement.restart) (statement.restartWith ?: existing.startValue) else null
        if (restartAt != null && (restartAt < minValue || restartAt > maxValue)) {
            throw KestrelException("RESTART value ($restartAt) must be between MINVALUE ($minValue) and MAXVALUE ($maxValue)")
        }
        catalog.alterSequence(statement.sequenceName, newDefinition, restartAt)
        return AlterSequenceResult(statement.sequenceName)
    }

    private fun fireRowTriggers(schema: TableSchema, timing: TriggerTiming, event: TriggerEvent, rows: List<Pair<Row?, Row?>>) {
        val triggers = catalog.triggersOn(schema.name, timing, event)
        if (triggers.isEmpty()) return
        for ((oldRow, newRow) in rows) {
            val context = TriggerRowContext(schema, newRow, oldRow)
            for (trigger in triggers) {
                val whenClause = trigger.whenClause
                if (whenClause != null) {
                    val evaluator = Evaluator(composeResolver(null, context), subqueryRunner)
                    if (!isTrue(evaluator.evaluate(whenClause))) continue
                }
                runTriggerBody(trigger, context)
            }
        }
    }

    private fun runTriggerBody(trigger: TriggerDefinition, context: TriggerRowContext) {
        if (triggerDepth >= MAX_TRIGGER_DEPTH) {
            throw KestrelException(
                "trigger recursion limit ($MAX_TRIGGER_DEPTH) exceeded while firing '${trigger.name}' - " +
                    "check for a trigger whose body ends up re-triggering itself"
            )
        }
        triggerDepth++
        try {
            for (bodyStatement in trigger.body) {
                when (bodyStatement) {
                    is InsertStatement -> executeInsert(bodyStatement, context)
                    is UpdateStatement -> executeUpdate(bodyStatement, context)
                    is DeleteStatement -> executeDelete(bodyStatement, context)
                    else -> error("unreachable: trigger bodies only ever contain INSERT/UPDATE/DELETE")
                }
            }
        } finally {
            triggerDepth--
        }
    }

    private fun composeResolver(base: ColumnResolver?, trigger: TriggerRowContext?): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value =
            if (trigger != null && trigger.matches(expression)) trigger.resolve(expression)
            else base?.resolve(expression) ?: throw KestrelException("column '${expression.name}' is not allowed here")

        override fun declaredCollation(expression: ColumnExpression): Collation? =
            if (trigger != null && trigger.matches(expression)) trigger.declaredCollation(expression)
            else base?.declaredCollation(expression)
    }

    private fun rowResolver(schema: TableSchema, row: Row): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val index = schema.columnIndex(expression.name)
                ?: throw KestrelException("no such column: ${expression.name}")
            return row.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
    }

    private fun evaluateReturning(schema: TableSchema, rows: List<Row>, projection: Projection): SelectResult {
        val columns = when (projection) {
            is StarProjection -> schema.columns.map { it.name }
            is ExpressionProjection -> projection.items.mapIndexed { index, item ->
                checkColumns(item.expression, schema)
                returningColumnLabel(item, index)
            }
        }
        val resultRows = rows.map { row ->
            when (projection) {
                is StarProjection -> row.values.toList()
                is ExpressionProjection -> {
                    val evaluator = Evaluator(rowResolver(schema, row), subqueryRunner)
                    projection.items.map { evaluator.evaluate(it.expression) }
                }
            }
        }
        return SelectResult(columns, resultRows)
    }

    private fun returningColumnLabel(item: SelectItem, index: Int): String =
        item.alias ?: when (val expression = item.expression) {
            is ColumnExpression -> expression.name
            is FunctionExpression -> expression.name.lowercase()
            is CallExpression -> expression.name.lowercase()
            is WindowExpression -> expression.name.lowercase()
            else -> "column${index + 1}"
        }

    private fun <T> withStatementAtomicity(tableName: String, triggerContext: TriggerRowContext?, block: () -> T): T {
        if (triggerContext != null || !catalog.hasTriggers(tableName)) return block()
        val snapshot = catalog.snapshot()
        try {
            return block()
        } catch (exception: Throwable) {
            catalog.restore(snapshot)
            throw exception
        }
    }

    private companion object {
        const val MAX_TRIGGER_DEPTH = 100
    }

    private fun executeInsert(statement: InsertStatement, triggerContext: TriggerRowContext? = null): StatementResult =
        withStatementAtomicity(statement.tableName, triggerContext) { executeInsertInner(statement, triggerContext) }

    private fun executeInsertInner(statement: InsertStatement, triggerContext: TriggerRowContext?): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val targetIndices = resolveInsertTargets(schema, statement.columns)
        val evaluator = Evaluator(composeResolver(null, triggerContext), subqueryRunner, this)
        val newRows = statement.rows.map { rawRow -> buildInsertRow(schema, targetIndices, rawRow, evaluator) }
        val onConflict = statement.onConflict
        if (onConflict == null) {
            fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, newRows.map { null to it })
            for (row in newRows) {
                ForeignKeyEnforcer.validateRow(catalog, schema, row.values, uncommittedSiblings = newRows)
                enforceChecks(schema, row)
            }
            enforcePrimaryKeyUniqueness(schema, table.rows + newRows)
            enforceUniqueConstraints(schema, table.rows + newRows)
            enforceUniqueIndexes(schema, table.rows + newRows)
            table.rows.addAll(newRows)
            catalog.reindexTable(schema.name)
            fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, newRows.map { null to it })
            val returning = statement.returning?.let { evaluateReturning(schema, newRows, it) }
            return InsertResult(newRows.size, returning)
        }
        return executeInsertWithConflictHandling(schema, table, newRows, onConflict, statement.returning, triggerContext)
    }

    private fun buildInsertRow(
        schema: TableSchema,
        targetIndices: List<Int>,
        rawRow: List<Expression?>,
        evaluator: Evaluator
    ): Row {
        if (rawRow.size != targetIndices.size) {
            throw KestrelException(
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
        applyGeneratedColumns(schema, values)
        validateNotNull(schema, values)
        return Row(values)
    }

    private class UniqueKey(val columnIndices: List<Int>, val columnNames: List<String>)

    private fun uniqueKeysOf(schema: TableSchema): List<UniqueKey> {
        val keys = mutableListOf<UniqueKey>()
        val pkIndices = schema.primaryKeyIndices
        if (pkIndices.isNotEmpty()) keys.add(UniqueKey(pkIndices, pkIndices.map { schema.columns[it].name }))
        for (unique in schema.uniqueConstraints) {
            val indices = unique.columns.map { schema.columnIndex(it)!! }
            keys.add(UniqueKey(indices, unique.columns))
        }
        for (definition in catalog.indexDefinitions()) {
            if (!definition.unique || !definition.tableName.equals(schema.name, ignoreCase = true)) continue
            val indices = definition.columnNames.mapNotNull { schema.columnIndex(it) }
            if (indices.size != definition.columnNames.size) continue
            keys.add(UniqueKey(indices, definition.columnNames))
        }
        return keys
    }

    private fun resolveConflictTargetKeys(schema: TableSchema, targetColumns: List<String>, keys: List<UniqueKey>): List<UniqueKey> {
        if (targetColumns.isEmpty()) return keys
        val targetIndices = targetColumns.map { name ->
            schema.columnIndex(name) ?: throw KestrelException("no such column: $name")
        }
        val targetSet = targetIndices.toSet()
        val matched = keys.firstOrNull { it.columnIndices.toSet() == targetSet }
            ?: throw KestrelException(
                "no unique or primary key constraint matching ON CONFLICT specification: " +
                    targetColumns.joinToString(", ")
            )
        return listOf(matched)
    }

    private fun findConflictingRow(existingRows: List<Row>, candidate: List<Value>, keys: List<UniqueKey>): Row? {
        for (existing in existingRows) {
            for (key in keys) {
                val candidateKey = key.columnIndices.map { candidate[it] }
                if (candidateKey.any { it is Value.NullValue }) continue
                val existingKey = key.columnIndices.map { existing.values[it] }
                if (candidateKey == existingKey) return existing
            }
        }
        return null
    }

    private fun executeInsertWithConflictHandling(
        schema: TableSchema,
        table: Table,
        candidateRows: List<Row>,
        onConflict: OnConflictClause,
        returning: Projection? = null,
        triggerContext: TriggerRowContext? = null
    ): StatementResult {
        val keys = uniqueKeysOf(schema)
        val targetKeys = resolveConflictTargetKeys(schema, onConflict.targetColumns, keys)
        val hasTriggers = catalog.hasTriggers(schema.name)
        var affected = 0
        val returnedRows = mutableListOf<Row>()
        for (row in candidateRows) {
            val conflicting = findConflictingRow(table.rows, row.values, targetKeys)
            if (conflicting == null) {
                fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, listOf(null to row))
                ForeignKeyEnforcer.validateRow(catalog, schema, row.values, uncommittedSiblings = emptyList())
                enforceChecks(schema, row)
                enforcePrimaryKeyUniqueness(schema, table.rows + row)
                enforceUniqueConstraints(schema, table.rows + row)
                enforceUniqueIndexes(schema, table.rows + row)
                table.rows.add(row)
                affected++
                returnedRows.add(row)
                if (hasTriggers) catalog.reindexTable(schema.name)
                fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, listOf(null to row))
                continue
            }
            when (val action = onConflict.action) {
                ConflictDoNothing -> Unit
                is ConflictDoUpdate -> {
                    val resolver = composeResolver(excludedAwareResolver(schema, conflicting, row), triggerContext)
                    val conditionEvaluator = Evaluator(resolver, subqueryRunner)
                    if (action.where != null && !isTrue(conditionEvaluator.evaluate(action.where))) continue
                    val assignmentEvaluator = Evaluator(resolver, subqueryRunner, this)
                    val values = conflicting.values.toMutableList()
                    for (assignment in action.assignments) {
                        val index = schema.columnIndex(assignment.columnName)
                            ?: throw KestrelException("no such column: ${assignment.columnName}")
                        val column = schema.columns[index]
                        rejectGeneratedAssignment(column, explicitValueGiven = assignment.value != null)
                        values[index] = if (assignment.value != null) {
                            coerceForColumn(assignmentEvaluator.evaluate(assignment.value), column)
                        } else {
                            column.default?.let { coerceForColumn(assignmentEvaluator.evaluate(it), column) } ?: Value.NullValue
                        }
                    }
                    applyGeneratedColumns(schema, values)
                    validateNotNull(schema, values)
                    val updated = Row(values)
                    fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, listOf(conflicting to updated))
                    ForeignKeyEnforcer.validateRow(catalog, schema, updated.values, uncommittedSiblings = emptyList())
                    enforceChecks(schema, updated)
                    val others = table.rows.filter { it !== conflicting }
                    enforcePrimaryKeyUniqueness(schema, others + updated)
                    enforceUniqueConstraints(schema, others + updated)
                    enforceUniqueIndexes(schema, others + updated)
                    for (group in schema.enforcedUniqueKeyGroups()) {
                        val columnIndices = group.map { schema.columnIndex(it)!! }
                        val oldKey = columnIndices.map { conflicting.values[it] }
                        val newKey = columnIndices.map { updated.values[it] }
                        ForeignKeyEnforcer.cascadeUpdate(catalog, schema.name, group, listOf(oldKey to newKey))
                    }
                    val position = table.rows.indexOfFirst { it === conflicting }
                    table.rows[position] = updated
                    affected++
                    returnedRows.add(updated)
                    if (hasTriggers) catalog.reindexTable(schema.name)
                    fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, listOf(conflicting to updated))
                }
            }
        }
        if (!hasTriggers) catalog.reindexTable(schema.name)
        return InsertResult(affected, returning?.let { evaluateReturning(schema, returnedRows, it) })
    }

    private fun excludedAwareResolver(schema: TableSchema, target: Row, excluded: Row): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val qualifier = expression.qualifier
            val useExcluded = qualifier != null && qualifier.equals("excluded", ignoreCase = true)
            if (qualifier != null && !useExcluded && !qualifier.equals(schema.name, ignoreCase = true)) {
                throw KestrelException("no such table or alias: $qualifier")
            }
            val index = schema.columnIndex(expression.name)
                ?: throw KestrelException("no such column: ${expression.name}")
            return if (useExcluded) excluded.values[index] else target.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
    }

    private fun resolveInsertTargets(schema: TableSchema, columns: List<String>?): List<Int> {
        if (columns == null) return schema.columns.indices.toList()
        return columns.map { columnName ->
            schema.columnIndex(columnName)
                ?: throw KestrelException("no such column: $columnName in table ${schema.name}")
        }
    }

    private fun executeDelete(statement: DeleteStatement, triggerContext: TriggerRowContext? = null): StatementResult =
        withStatementAtomicity(statement.tableName, triggerContext) { executeDeleteInner(statement, triggerContext) }

    private fun executeDeleteInner(statement: DeleteStatement, triggerContext: TriggerRowContext?): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val deletedRows = if (statement.where == null) {
            table.rows.toList()
        } else {
            checkColumns(statement.where, schema, triggerContext)
            table.rows.filter {
                isTrue(Evaluator(composeResolver(rowResolver(schema, it), triggerContext), subqueryRunner).evaluate(statement.where))
            }
        }
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Delete, deletedRows.map { it to null })
        ForeignKeyEnforcer.cascadeDelete(catalog, schema.name, deletedRows)
        table.rows.removeAll(deletedRows)
        catalog.reindexTable(schema.name)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Delete, deletedRows.map { it to null })
        val returning = statement.returning?.let { evaluateReturning(schema, deletedRows, it) }
        return DeleteResult(deletedRows.size, returning)
    }

    private fun executeUpdate(statement: UpdateStatement, triggerContext: TriggerRowContext? = null): StatementResult =
        withStatementAtomicity(statement.tableName, triggerContext) { executeUpdateInner(statement, triggerContext) }

    private fun executeUpdateInner(statement: UpdateStatement, triggerContext: TriggerRowContext?): StatementResult {
        val table = catalog.table(statement.tableName)
        val schema = table.schema
        val assignments = statement.assignments.map { assignment ->
            val index = schema.columnIndex(assignment.columnName)
                ?: throw KestrelException("no such column: ${assignment.columnName}")
            assignment.value?.let { checkColumns(it, schema, triggerContext) }
            rejectGeneratedAssignment(schema.columns[index], explicitValueGiven = assignment.value != null)
            index to assignment.value
        }
        statement.where?.let { checkColumns(it, schema, triggerContext) }

        var updated = 0
        val oldRows = table.rows.toList()
        val touchedRows = mutableListOf<Pair<Row, Row>>()
        val newRows = oldRows.map { row ->
            val matches = statement.where == null ||
                isTrue(Evaluator(composeResolver(rowResolver(schema, row), triggerContext), subqueryRunner).evaluate(statement.where))
            if (!matches) return@map row
            val values = row.values.toMutableList()
            val evaluator = Evaluator(composeResolver(rowResolver(schema, row), triggerContext), subqueryRunner, this)
            for ((index, expression) in assignments) {
                val column = schema.columns[index]
                values[index] = if (expression != null) {
                    coerceForColumn(evaluator.evaluate(expression), column)
                } else {
                    column.default?.let { coerceForColumn(evaluator.evaluate(it), column) } ?: Value.NullValue
                }
            }
            applyGeneratedColumns(schema, values)
            validateNotNull(schema, values)
            updated++
            val newRow = Row(values)
            touchedRows.add(row to newRow)
            newRow
        }
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, touchedRows)
        for (row in newRows) {
            ForeignKeyEnforcer.validateRow(catalog, schema, row.values, uncommittedSiblings = newRows)
            enforceChecks(schema, row)
        }
        enforcePrimaryKeyUniqueness(schema, newRows)
        enforceUniqueConstraints(schema, newRows)
        enforceUniqueIndexes(schema, newRows)
        for (group in schema.enforcedUniqueKeyGroups()) {
            val columnIndices = group.map { schema.columnIndex(it)!! }
            val changedPairs = oldRows.indices.map { i ->
                columnIndices.map { oldRows[i].values[it] } to columnIndices.map { newRows[i].values[it] }
            }
            ForeignKeyEnforcer.cascadeUpdate(catalog, schema.name, group, changedPairs)
        }
        table.rows.clear()
        table.rows.addAll(newRows)
        catalog.reindexTable(schema.name)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, touchedRows)
        val returning = statement.returning?.let { evaluateReturning(schema, touchedRows.map { pair -> pair.second }, it) }
        return UpdateResult(updated, returning)
    }

    private fun executeMerge(statement: MergeStatement): StatementResult =
        withStatementAtomicity(statement.targetTable, null) { executeMergeInner(statement) }

    private fun executeMergeInner(statement: MergeStatement): StatementResult {
        val table = catalog.table(statement.targetTable)
        val schema = table.schema
        val targetLabel = statement.targetAlias ?: statement.targetTable
        val (sourceSchema, sourceRows) = selectRunner.resolveMergeSource(statement.source)
        val sourceLabel = selectRunner.mergeSourceLabel(statement.source)
        if (targetLabel.equals(sourceLabel, ignoreCase = true)) {
            throw KestrelException("MERGE target and source cannot share the same name or alias: $targetLabel")
        }
        checkMergeColumns(statement, schema, targetLabel, sourceSchema, sourceLabel)

        val originalTargetRows = table.rows.toList()
        val hasTriggers = catalog.hasTriggers(schema.name)
        val affectedTargets = mutableListOf<Row>()
        val returnedRows = mutableListOf<Pair<Row, String>>()
        var inserted = 0
        var updated = 0
        var deleted = 0

        for (sourceRow in sourceRows) {
            val matchedTargets = originalTargetRows.filter { targetRow ->
                val resolver = mergeResolver(schema, targetLabel, targetRow, sourceSchema, sourceLabel, sourceRow)
                isTrue(Evaluator(resolver, subqueryRunner).evaluate(statement.onCondition))
            }
            if (matchedTargets.isNotEmpty()) {
                for (targetRow in matchedTargets) {
                    val resolver = mergeResolver(schema, targetLabel, targetRow, sourceSchema, sourceLabel, sourceRow)
                    val clause = statement.whenClauses.firstOrNull { candidate ->
                        (candidate is MergeMatchedUpdate || candidate is MergeMatchedDelete) &&
                            (candidate.condition == null || isTrue(Evaluator(resolver, subqueryRunner).evaluate(candidate.condition!!)))
                    } ?: continue
                    if (affectedTargets.any { it === targetRow }) {
                        throw KestrelException("MERGE command cannot affect a target row a second time")
                    }
                    affectedTargets.add(targetRow)
                    when (clause) {
                        is MergeMatchedUpdate -> {
                            val updatedRow = applyMergeUpdate(schema, table, targetRow, clause, resolver, hasTriggers)
                            returnedRows.add(updatedRow to "UPDATE")
                            updated++
                        }
                        is MergeMatchedDelete -> {
                            val deletedRow = applyMergeDelete(schema, table, targetRow, hasTriggers)
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
                    (candidate.condition == null || isTrue(Evaluator(resolver, subqueryRunner).evaluate(candidate.condition!!)))
            } as? MergeNotMatchedInsert ?: continue
            val insertedRow = applyMergeInsert(schema, table, clause, resolver, hasTriggers)
            returnedRows.add(insertedRow to "INSERT")
            inserted++
        }
        if (!hasTriggers) catalog.reindexTable(schema.name)
        val returning = statement.returning?.let { evaluateMergeReturning(schema, returnedRows, it) }
        return MergeResult(inserted, updated, deleted, returning)
    }

    private fun evaluateMergeReturning(schema: TableSchema, actionedRows: List<Pair<Row, String>>, projection: Projection): SelectResult {
        val columns = when (projection) {
            is StarProjection -> schema.columns.map { it.name }
            is ExpressionProjection -> projection.items.mapIndexed { index, item ->
                checkColumns(item.expression, schema)
                returningColumnLabel(item, index)
            }
        }
        val resultRows = actionedRows.map { (row, action) ->
            when (projection) {
                is StarProjection -> row.values.toList()
                is ExpressionProjection -> {
                    val evaluator = Evaluator(rowResolver(schema, row), subqueryRunner, mergeAction = action)
                    projection.items.map { evaluator.evaluate(it.expression) }
                }
            }
        }
        return SelectResult(columns, resultRows)
    }

    private fun applyMergeUpdate(
        schema: TableSchema,
        table: Table,
        targetRow: Row,
        clause: MergeMatchedUpdate,
        resolver: ColumnResolver,
        hasTriggers: Boolean
    ): Row {
        val assignmentEvaluator = Evaluator(resolver, subqueryRunner, this)
        val values = targetRow.values.toMutableList()
        for (assignment in clause.assignments) {
            val index = schema.columnIndex(assignment.columnName)
                ?: throw KestrelException("no such column: ${assignment.columnName}")
            val column = schema.columns[index]
            rejectGeneratedAssignment(column, explicitValueGiven = assignment.value != null)
            values[index] = if (assignment.value != null) {
                coerceForColumn(assignmentEvaluator.evaluate(assignment.value), column)
            } else {
                column.default?.let { coerceForColumn(assignmentEvaluator.evaluate(it), column) } ?: Value.NullValue
            }
        }
        applyGeneratedColumns(schema, values)
        validateNotNull(schema, values)
        val updatedRow = Row(values)
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, listOf(targetRow to updatedRow))
        ForeignKeyEnforcer.validateRow(catalog, schema, updatedRow.values, uncommittedSiblings = emptyList())
        enforceChecks(schema, updatedRow)
        val others = table.rows.filter { it !== targetRow }
        enforcePrimaryKeyUniqueness(schema, others + updatedRow)
        enforceUniqueConstraints(schema, others + updatedRow)
        enforceUniqueIndexes(schema, others + updatedRow)
        for (group in schema.enforcedUniqueKeyGroups()) {
            val columnIndices = group.map { schema.columnIndex(it)!! }
            val oldKey = columnIndices.map { targetRow.values[it] }
            val newKey = columnIndices.map { updatedRow.values[it] }
            ForeignKeyEnforcer.cascadeUpdate(catalog, schema.name, group, listOf(oldKey to newKey))
        }
        val position = table.rows.indexOfFirst { it === targetRow }
        table.rows[position] = updatedRow
        if (hasTriggers) catalog.reindexTable(schema.name)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Update, listOf(targetRow to updatedRow))
        return updatedRow
    }

    private fun applyMergeDelete(schema: TableSchema, table: Table, targetRow: Row, hasTriggers: Boolean): Row {
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Delete, listOf(targetRow to null))
        ForeignKeyEnforcer.cascadeDelete(catalog, schema.name, listOf(targetRow))
        table.rows.removeAll { it === targetRow }
        if (hasTriggers) catalog.reindexTable(schema.name)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Delete, listOf(targetRow to null))
        return targetRow
    }

    private fun applyMergeInsert(
        schema: TableSchema,
        table: Table,
        clause: MergeNotMatchedInsert,
        resolver: ColumnResolver,
        hasTriggers: Boolean
    ): Row {
        val targetIndices = resolveInsertTargets(schema, clause.columns)
        val evaluator = Evaluator(resolver, subqueryRunner, this)
        val row = buildInsertRow(schema, targetIndices, clause.values, evaluator)
        fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Insert, listOf(null to row))
        ForeignKeyEnforcer.validateRow(catalog, schema, row.values, uncommittedSiblings = emptyList())
        enforceChecks(schema, row)
        enforcePrimaryKeyUniqueness(schema, table.rows + row)
        enforceUniqueConstraints(schema, table.rows + row)
        enforceUniqueIndexes(schema, table.rows + row)
        table.rows.add(row)
        if (hasTriggers) catalog.reindexTable(schema.name)
        fireRowTriggers(schema, TriggerTiming.After, TriggerEvent.Insert, listOf(null to row))
        return row
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
                        sourceRow.values[sourceSchema.columnIndex(expression.name) ?: throw KestrelException("no such column: ${expression.name}")]
                    qualifier.equals(targetLabel, ignoreCase = true) ->
                        targetRow.values[targetSchema.columnIndex(expression.name) ?: throw KestrelException("no such column: ${expression.name}")]
                    else -> throw KestrelException("no such table or alias: $qualifier")
                }
            }
            val targetIndex = targetSchema.columnIndex(expression.name)
            val sourceIndex = sourceSchema.columnIndex(expression.name)
            if (targetIndex != null && sourceIndex != null) {
                throw KestrelException("column reference '${expression.name}' is ambiguous between $targetLabel and $sourceLabel")
            }
            targetIndex?.let { return targetRow.values[it] }
            sourceIndex?.let { return sourceRow.values[it] }
            throw KestrelException("no such column: ${expression.name}")
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
                throw KestrelException("no target row is available in a WHEN NOT MATCHED clause: $targetLabel.${expression.name}")
            }
            if (qualifier != null && !qualifier.equals(sourceLabel, ignoreCase = true)) {
                throw KestrelException("no such table or alias: $qualifier")
            }
            val index = sourceSchema.columnIndex(expression.name) ?: throw KestrelException("no such column: ${expression.name}")
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
                            throw KestrelException("no such column: ${assignment.columnName}")
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
                        throw KestrelException(
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
            is FunctionExpression -> throw KestrelException("aggregate functions are not allowed here")
            is WindowExpression -> throw KestrelException("window functions are not allowed here")
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
                throw KestrelException("no target row is available in a WHEN NOT MATCHED clause: $targetLabel.${expression.name}")
            }
            if (!matchesSource && !matchesTargetLabel) {
                throw KestrelException("no such table or alias: $qualifier")
            }
            val schema = if (matchesSource) sourceSchema else targetSchema
            if (schema.columnIndex(expression.name) == null) throw KestrelException("no such column: ${expression.name}")
            return
        }
        val inTarget = allowTarget && targetSchema.columnIndex(expression.name) != null
        val inSource = sourceSchema.columnIndex(expression.name) != null
        if (inTarget && inSource) {
            throw KestrelException("column reference '${expression.name}' is ambiguous between $targetLabel and $sourceLabel")
        }
        if (!inTarget && !inSource) throw KestrelException("no such column: ${expression.name}")
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
        val indices = schema.primaryKeyIndices
        if (indices.isEmpty()) return
        val seen = HashSet<List<Value>>()
        for (row in rows) {
            val key = collationFoldedUniquenessKey(schema, indices, indices.map { row.values[it] })
            if (!seen.add(key)) {
                val columnList = indices.joinToString(", ") { schema.columns[it].name }
                throw KestrelException("UNIQUE constraint failed: ${schema.name}($columnList)")
            }
        }
    }

    private fun enforceUniqueConstraints(schema: TableSchema, rows: List<Row>) {
        for (unique in schema.uniqueConstraints) {
            val columnIndices = unique.columns.map { schema.columnIndex(it)!! }
            val seen = HashSet<List<Value>>()
            for (row in rows) {
                val rawKey = columnIndices.map { row.values[it] }
                if (rawKey.any { it is Value.NullValue }) continue
                if (!seen.add(collationFoldedUniquenessKey(schema, columnIndices, rawKey))) {
                    val label = unique.name?.let { " $it" } ?: ""
                    val columnList = unique.columns.joinToString(", ")
                    throw KestrelException("UNIQUE constraint$label failed: ${schema.name}($columnList)")
                }
            }
        }
    }

    private fun enforceUniqueIndexes(schema: TableSchema, rows: List<Row>) {
        for (definition in catalog.indexDefinitions()) {
            if (!definition.unique || !definition.tableName.equals(schema.name, ignoreCase = true)) continue
            val columnIndices = definition.columnNames.mapNotNull { schema.columnIndex(it) }
            if (columnIndices.size != definition.columnNames.size) continue
            val seen = HashSet<List<Value>>()
            for (row in rows) {
                val rawKey = columnIndices.map { row.values[it] }
                if (rawKey.any { it is Value.NullValue }) continue
                if (!seen.add(collationFoldedUniquenessKey(schema, columnIndices, rawKey))) {
                    throw KestrelException("UNIQUE constraint failed: ${schema.name}(${definition.columnDisplayName})")
                }
            }
        }
    }

    private fun checkColumns(expression: Expression, schema: TableSchema, triggerContext: TriggerRowContext? = null) {
        when (expression) {
            is ColumnExpression -> if (triggerContext != null && triggerContext.matches(expression)) {
                triggerContext.checkColumnExists(expression)
            } else if (schema.columnIndex(expression.name) == null) {
                throw KestrelException("no such column: ${expression.name}")
            }
            is BinaryExpression -> {
                checkColumns(expression.left, schema, triggerContext)
                checkColumns(expression.right, schema, triggerContext)
            }
            is UnaryExpression -> checkColumns(expression.operand, schema, triggerContext)
            is FunctionExpression -> throw KestrelException("aggregate functions are not allowed here")
            is WindowExpression -> throw KestrelException("window functions are not allowed here")
            is LiteralExpression -> Unit
            is CallExpression -> expression.arguments.forEach { checkColumns(it, schema, triggerContext) }
            is IsNullExpression -> checkColumns(expression.operand, schema, triggerContext)
            is InExpression -> {
                checkColumns(expression.operand, schema, triggerContext)
                expression.values.forEach { checkColumns(it, schema, triggerContext) }
            }
            is BetweenExpression -> {
                checkColumns(expression.operand, schema, triggerContext)
                checkColumns(expression.low, schema, triggerContext)
                checkColumns(expression.high, schema, triggerContext)
            }
            is LikeExpression -> {
                checkColumns(expression.operand, schema, triggerContext)
                checkColumns(expression.pattern, schema, triggerContext)
            }
            is CaseExpression -> {
                expression.operand?.let { checkColumns(it, schema, triggerContext) }
                expression.branches.forEach {
                    checkColumns(it.condition, schema, triggerContext)
                    checkColumns(it.result, schema, triggerContext)
                }
                expression.elseExpression?.let { checkColumns(it, schema, triggerContext) }
            }
            is ScalarSubqueryExpression -> Unit
            is ExistsExpression -> Unit
            is InSubqueryExpression -> checkColumns(expression.operand, schema, triggerContext)
            is CastExpression -> checkColumns(expression.operand, schema, triggerContext)
            is CollateExpression -> checkColumns(expression.operand, schema, triggerContext)
            is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> Unit
        }
    }

    private fun isTrue(value: Value): Boolean = value is Value.BooleanValue && value.value
}

package kestrel.persistence

import java.math.BigDecimal
import kestrel.catalog.Catalog
import kestrel.catalog.CheckConstraint
import kestrel.catalog.Column
import kestrel.catalog.ForeignKeyAction
import kestrel.catalog.ForeignKeyConstraint
import kestrel.catalog.Sequence
import kestrel.catalog.SequenceDefinition
import kestrel.catalog.UniqueConstraint
import kestrel.catalog.TriggerDefinition
import kestrel.catalog.sqlKeyword
import kestrel.sql.ast.AddColumn
import kestrel.sql.ast.AddConstraint
import kestrel.sql.ast.AddedCheck
import kestrel.sql.ast.AddedConstraint
import kestrel.sql.ast.AddedForeignKey
import kestrel.sql.ast.AddedUnique
import kestrel.sql.ast.AlterTableStatement
import kestrel.sql.ast.Assignment
import kestrel.sql.ast.BetweenExpression
import kestrel.sql.ast.BinaryExpression
import kestrel.sql.ast.CallExpression
import kestrel.sql.ast.CaseExpression
import kestrel.sql.ast.CastExpression
import kestrel.sql.ast.ChangeColumnType
import kestrel.sql.ast.CollateExpression
import kestrel.sql.ast.ColumnExpression
import kestrel.sql.ast.ConflictDoNothing
import kestrel.sql.ast.ConflictDoUpdate
import kestrel.sql.ast.AlterSequenceStatement
import kestrel.sql.ast.CreateIndexStatement
import kestrel.sql.ast.CreateSequenceStatement
import kestrel.sql.ast.CreateTableStatement
import kestrel.sql.ast.CreateTriggerStatement
import kestrel.sql.ast.CreateViewStatement
import kestrel.sql.ast.CubeSpec
import kestrel.sql.ast.CurrentValueExpression
import kestrel.sql.ast.DeleteStatement
import kestrel.sql.ast.DropColumn
import kestrel.sql.ast.DropConstraint
import kestrel.sql.ast.DropIndexStatement
import kestrel.sql.ast.DropMode
import kestrel.sql.ast.DropSequenceStatement
import kestrel.sql.ast.DropTableStatement
import kestrel.sql.ast.DropTriggerStatement
import kestrel.sql.ast.DropViewStatement
import kestrel.sql.ast.ExistsExpression
import kestrel.sql.ast.Expression
import kestrel.sql.ast.ExpressionProjection
import kestrel.sql.ast.FrameBound
import kestrel.sql.ast.FrameBoundType
import kestrel.sql.ast.FrameExclude
import kestrel.sql.ast.FrameUnit
import kestrel.sql.ast.FromItem
import kestrel.sql.ast.FunctionExpression
import kestrel.sql.ast.GroupingSetsSpec
import kestrel.sql.ast.GroupingSpec
import kestrel.sql.ast.InExpression
import kestrel.sql.ast.InSubqueryExpression
import kestrel.sql.ast.InsertStatement
import kestrel.sql.ast.IsNullExpression
import kestrel.sql.ast.JoinType
import kestrel.sql.ast.LikeExpression
import kestrel.sql.ast.LiteralExpression
import kestrel.sql.ast.MergeActionExpression
import kestrel.sql.ast.ParameterExpression
import kestrel.sql.ast.MergeMatchedDelete
import kestrel.sql.ast.MergeMatchedUpdate
import kestrel.sql.ast.MergeNotMatchedInsert
import kestrel.sql.ast.MergeStatement
import kestrel.sql.ast.MergeWhenClause
import kestrel.sql.ast.NextValueExpression
import kestrel.sql.ast.OnConflictClause
import kestrel.sql.ast.OrderBy
import kestrel.sql.ast.OrderKey
import kestrel.sql.ast.Projection
import kestrel.sql.ast.QueryStatement
import kestrel.sql.ast.RenameColumn
import kestrel.sql.ast.RenameTable
import kestrel.sql.ast.RollupSpec
import kestrel.sql.ast.ScalarSubqueryExpression
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
import kestrel.sql.ast.WithQuery
import kestrel.storage.Collation
import kestrel.storage.Value
import kestrel.storage.displayWith

object SqlWriter {
    fun expressionText(expression: Expression): String = expression(expression)

    fun canPersist(statement: Statement): Boolean = statement is CreateTableStatement ||
        statement is DropTableStatement ||
        statement is TruncateTableStatement ||
        statement is AlterTableStatement ||
        statement is CreateIndexStatement ||
        statement is DropIndexStatement ||
        statement is CreateViewStatement ||
        statement is DropViewStatement ||
        statement is CreateTriggerStatement ||
        statement is DropTriggerStatement ||
        statement is CreateSequenceStatement ||
        statement is DropSequenceStatement ||
        statement is AlterSequenceStatement ||
        statement is InsertStatement ||
        statement is UpdateStatement ||
        statement is DeleteStatement ||
        statement is MergeStatement

    fun write(statement: Statement): String = when (statement) {
        is CreateTableStatement -> "CREATE ${if (statement.temporary) "TEMPORARY " else ""}TABLE ${statement.tableName} (" +
            createTableBodyText(
                statement.columns,
                statement.foreignKeys,
                statement.checks,
                statement.uniqueConstraints,
                statement.compositePrimaryKey
            ) + ");"
        is DropTableStatement -> "DROP TABLE ${statement.tableName}${dropModeText(statement.mode)};"
        is TruncateTableStatement -> writeTruncate(statement)
        is AlterTableStatement -> alterTableText(statement)
        is CreateIndexStatement ->
            "CREATE ${if (statement.unique) "UNIQUE " else ""}INDEX ${statement.indexName} ON " +
                "${statement.tableName} (${statement.columnNames.joinToString(", ")});"
        is DropIndexStatement -> "DROP INDEX ${statement.indexName};"
        is CreateViewStatement ->
            "CREATE ${if (statement.orReplace) "OR REPLACE " else ""}VIEW ${statement.viewName} AS " +
                "${queryText(statement.query)};"
        is DropViewStatement -> "DROP VIEW ${statement.viewName}${dropModeText(statement.mode)};"
        is CreateTriggerStatement -> triggerText(
            statement.triggerName, statement.timing, statement.event, statement.tableName, statement.whenClause, statement.body
        )
        is DropTriggerStatement -> "DROP TRIGGER ${statement.triggerName};"
        is CreateSequenceStatement -> createSequenceText(statement)
        is DropSequenceStatement -> "DROP SEQUENCE ${statement.sequenceName};"
        is AlterSequenceStatement -> alterSequenceText(statement)
        is InsertStatement -> writeInsert(statement)
        is UpdateStatement -> writeUpdate(statement)
        is DeleteStatement -> writeDelete(statement)
        is MergeStatement -> writeMerge(statement)
        else -> throw IllegalArgumentException("statement is not persistable")
    }

    fun triggerText(definition: TriggerDefinition): String =
        triggerText(definition.name, definition.timing, definition.event, definition.tableName, definition.whenClause, definition.body)

    private fun triggerText(
        name: String,
        timing: TriggerTiming,
        event: TriggerEvent,
        tableName: String,
        whenClause: Expression?,
        body: List<Statement>
    ): String = buildString {
        append("CREATE TRIGGER $name ")
        append(if (timing == TriggerTiming.Before) "BEFORE" else "AFTER")
        append(" ")
        append(
            when (event) {
                TriggerEvent.Insert -> "INSERT"
                TriggerEvent.Update -> "UPDATE"
                TriggerEvent.Delete -> "DELETE"
            }
        )
        append(" ON $tableName FOR EACH ROW")
        whenClause?.let { append(" WHEN ${expression(it)}") }
        append(" BEGIN ")
        for (statement in body) {
            append(write(statement))
            append(" ")
        }
        append("END;")
    }

    private fun createSequenceText(statement: CreateSequenceStatement): String = buildString {
        append("CREATE SEQUENCE ${statement.sequenceName}")
        statement.incrementBy?.let { append(" INCREMENT BY $it") }
        statement.minValue?.let { append(" MINVALUE $it") }
        statement.maxValue?.let { append(" MAXVALUE $it") }
        statement.startValue?.let { append(" START WITH $it") }
        if (statement.cycle) append(" CYCLE")
        append(";")
    }

    private fun alterSequenceText(statement: AlterSequenceStatement): String = buildString {
        append("ALTER SEQUENCE ${statement.sequenceName}")
        statement.incrementBy?.let { append(" INCREMENT BY $it") }
        when {
            statement.clearMinValue -> append(" NO MINVALUE")
            statement.minValue != null -> append(" MINVALUE ${statement.minValue}")
        }
        when {
            statement.clearMaxValue -> append(" NO MAXVALUE")
            statement.maxValue != null -> append(" MAXVALUE ${statement.maxValue}")
        }
        if (statement.restart) {
            append(" RESTART")
            statement.restartWith?.let { append(" WITH $it") }
        }
        when (statement.cycle) {
            true -> append(" CYCLE")
            false -> append(" NO CYCLE")
            null -> Unit
        }
        append(";")
    }

    /**
     * Bakes the sequence's CURRENT position into `START WITH`, rather than dumping
     * [SequenceDefinition.startValue] as written at CREATE time, so a reload resumes NEXTVAL
     * exactly where this session left off instead of reissuing already-handed-out values - see
     * [Sequence]'s own doc on why the counter (unlike the CREATE/DROP/ALTER SEQUENCE structure
     * every other part of [dump] is free to just replay literally) needs this special treatment.
     */
    private fun sequenceDumpText(sequence: Sequence): String {
        val definition = sequence.definition
        val cycle = if (definition.cycle) " CYCLE" else ""
        return "CREATE SEQUENCE ${definition.name} INCREMENT BY ${definition.incrementBy} " +
            "MINVALUE ${definition.minValue} MAXVALUE ${definition.maxValue} START WITH ${sequence.peekNext()}$cycle;"
    }

    fun dump(catalog: Catalog): String {
        val builder = StringBuilder()
        // Sequences are dumped FIRST, strictly before every table - a table's own column DEFAULT
        // can read `NEXTVAL('seq')` (see kestrel.engine.Executor.executeCreateTable's eager
        // `catalog.sequence(...)` existence check), so replaying that CREATE TABLE on reload would
        // fail with "no such sequence" if the sequence it names did not already exist yet.
        for (definition in catalog.sequenceDefinitions()) {
            builder.append("${sequenceDumpText(catalog.sequence(definition.name))}\n")
        }
        for (tableName in catalog.tableNames) {
            val table = catalog.table(tableName)
            // A TEMP/TEMPORARY table (see kestrel.catalog.TableSchema.temporary) is session-scoped by
            // definition - dumping it (or its rows below) into a checkpoint would make it survive a
            // reload, which is exactly what "temporary" promises it never does.
            if (table.schema.temporary) continue
            builder.append("CREATE TABLE ${table.schema.name} (")
            builder.append(
                createTableBodyText(
                    table.schema.columns,
                    table.schema.foreignKeys,
                    table.schema.checks,
                    table.schema.uniqueConstraints,
                    table.schema.compositePrimaryKey
                )
            )
            builder.append(");\n")
            for (row in table.rows) {
                builder.append("INSERT INTO ${table.schema.name} VALUES (")
                // A GENERATED column's position is dumped as the literal `DEFAULT` keyword rather
                // than its already-computed value - the executor rejects an explicit value for a
                // GENERATED column outright (see Executor.rejectGeneratedAssignment), and `DEFAULT`
                // here triggers the exact same fresh-recompute path an omitted column would, which
                // reproduces the same value on replay since the expression is a pure function of
                // this row's other (literal, already-dumped) columns.
                builder.append(
                    row.values.mapIndexed { index, value ->
                        if (table.schema.columns[index].generated != null) "DEFAULT" else literal(value)
                    }.joinToString(", ")
                )
                builder.append(");\n")
            }
        }
        for (definition in catalog.indexDefinitions()) {
            // An index defined on a TEMP table never gets a CREATE TABLE to attach to above, so
            // dumping it would replay as "no such table" - skip it right along with its table.
            if (catalog.tableOrNull(definition.tableName)?.schema?.temporary == true) continue
            builder.append(
                "CREATE ${if (definition.unique) "UNIQUE " else ""}INDEX ${definition.name} ON " +
                    "${definition.tableName} (${definition.columnDisplayName});\n"
            )
        }
        // A VIEW whose query reads a TEMP table is NOT specially excluded here (unlike the index/
        // trigger cases above, its dependency can be buried arbitrarily deep in a join/subquery, not
        // one flat tableName field) - dumping it and replaying it against a reload that never
        // recreated that TEMP table fails loudly with "no such table", the same honest failure mode
        // this engine already accepts for e.g. a prepared statement that does not survive reopen,
        // rather than silently materializing an empty or wrong view.
        for (viewName in catalog.viewNames) {
            val view = catalog.viewOrNull(viewName)!!
            builder.append("CREATE VIEW ${view.name} AS ${queryText(view.query)};\n")
        }
        // Triggers are dumped LAST, strictly after every row above has already been inserted as
        // plain data - a trigger created before its table's INSERTs would re-fire (duplicating an
        // AFTER trigger's side effects, or re-running a BEFORE trigger's validation) the moment
        // this very dump is replayed on reload, since replay re-executes each of these statements
        // for real. Creating the trigger only once the table is already fully populated means
        // those historical rows never pass through it, while every INSERT/UPDATE/DELETE a user
        // issues after reload still does.
        for (definition in catalog.triggerDefinitions()) {
            // Same reasoning as the index skip above: a trigger ON a TEMP table has no table left to
            // attach to after reload.
            if (catalog.tableOrNull(definition.tableName)?.schema?.temporary == true) continue
            builder.append("${triggerText(definition)}\n")
        }
        return builder.toString()
    }

    private fun writeInsert(statement: InsertStatement): String {
        val columns = statement.columns?.let { " (${it.joinToString(", ")})" } ?: ""
        val rows = statement.rows.joinToString(", ") { row ->
            "(" + row.joinToString(", ") { it?.let { expression(it) } ?: "DEFAULT" } + ")"
        }
        val onConflict = statement.onConflict?.let { " ${onConflictText(it)}" } ?: ""
        return "INSERT INTO ${statement.tableName}$columns VALUES $rows$onConflict;"
    }

    private fun onConflictText(clause: OnConflictClause): String {
        val target = if (clause.targetColumns.isEmpty()) "" else " (${clause.targetColumns.joinToString(", ")})"
        val action = when (val action = clause.action) {
            ConflictDoNothing -> "DO NOTHING"
            is ConflictDoUpdate -> {
                val assignments = action.assignments.joinToString(", ") { assignment(it) }
                val where = action.where?.let { " WHERE ${expression(it)}" } ?: ""
                "DO UPDATE SET $assignments$where"
            }
        }
        return "ON CONFLICT$target $action"
    }

    private fun writeUpdate(statement: UpdateStatement): String {
        val assignments = statement.assignments.joinToString(", ") { assignment(it) }
        val where = statement.where?.let { " WHERE ${expression(it)}" } ?: ""
        return "UPDATE ${statement.tableName} SET $assignments$where;"
    }

    private fun writeTruncate(statement: TruncateTableStatement): String {
        val identity = if (statement.restartIdentity) " RESTART IDENTITY" else ""
        return "TRUNCATE TABLE ${statement.tableNames.joinToString(", ")}$identity${dropModeText(statement.mode)};"
    }

    private fun writeDelete(statement: DeleteStatement): String {
        val where = statement.where?.let { " WHERE ${expression(it)}" } ?: ""
        return "DELETE FROM ${statement.tableName}$where;"
    }

    /**
     * Persisted literally like every other DML statement (see [writeInsert]/[writeUpdate]): by the
     * time this redo-log entry replays, every earlier statement - including whatever populated a
     * table-shaped `USING` source - has already replayed too, so re-running the exact same MERGE
     * text against the freshly rebuilt catalog reproduces the same result deterministically.
     */
    private fun writeMerge(statement: MergeStatement): String {
        val targetAlias = statement.targetAlias?.let { " AS $it" } ?: ""
        val whenClauses = statement.whenClauses.joinToString(" ") { mergeWhenClauseText(it) }
        return "MERGE INTO ${statement.targetTable}$targetAlias USING ${fromItemText(statement.source)} " +
            "ON ${expression(statement.onCondition)} $whenClauses;"
    }

    private fun mergeWhenClauseText(clause: MergeWhenClause): String {
        val condition = clause.condition?.let { " AND ${expression(it)}" } ?: ""
        return when (clause) {
            is MergeMatchedUpdate ->
                "WHEN MATCHED$condition THEN UPDATE SET ${clause.assignments.joinToString(", ") { assignment(it) }}"
            is MergeMatchedDelete -> "WHEN MATCHED$condition THEN DELETE"
            is MergeNotMatchedInsert -> {
                val columns = clause.columns?.let { " (${it.joinToString(", ")})" } ?: ""
                val values = clause.values.joinToString(", ") { it?.let { value -> expression(value) } ?: "DEFAULT" }
                "WHEN NOT MATCHED$condition THEN INSERT$columns VALUES ($values)"
            }
        }
    }

    private fun assignment(assignment: Assignment): String =
        "${assignment.columnName} = ${assignment.value?.let { expression(it) } ?: "DEFAULT"}"

    private fun columnDefinition(column: Column): String = buildString {
        append(column.name)
        append(" ")
        append(column.type.displayWith(column.precision, column.scale))
        when {
            column.primaryKey -> append(" PRIMARY KEY")
            column.notNull -> append(" NOT NULL")
        }
        if (column.collation != Collation.Binary) append(" COLLATE ${column.collation.sqlName}")
        column.default?.let { append(" DEFAULT ${expression(it)}") }
        column.generated?.let { append(" GENERATED ALWAYS AS (${expression(it)}) STORED") }
    }

    private fun alterTableText(statement: AlterTableStatement): String {
        val action = when (val action = statement.action) {
            is AddColumn -> "ADD COLUMN ${columnDefinition(action.column)}"
            is DropColumn -> "DROP COLUMN ${action.columnName}${dropModeText(action.mode)}"
            is RenameColumn -> "RENAME COLUMN ${action.oldName} TO ${action.newName}"
            is RenameTable -> "RENAME TO ${action.newName}"
            is AddConstraint -> "ADD ${addedConstraintText(action.constraint)}"
            is DropConstraint -> "DROP CONSTRAINT ${action.name}${dropModeText(action.mode)}"
            is ChangeColumnType -> {
                val using = action.using?.let { " USING ${expression(it)}" } ?: ""
                "ALTER COLUMN ${action.columnName} TYPE ${action.newType.displayWith(action.precision, action.scale)}$using"
            }
        }
        return "ALTER TABLE ${statement.tableName} $action;"
    }

    private fun addedConstraintText(constraint: AddedConstraint): String = when (constraint) {
        is AddedCheck -> constraintText(constraint.check.name, "CHECK (${expression(constraint.check.expression)})")
        is AddedUnique -> constraintText(constraint.unique.name, "UNIQUE (${constraint.unique.columns.joinToString(", ")})")
        is AddedForeignKey -> constraintText(constraint.foreignKey.name, foreignKeyText(constraint.foreignKey))
    }

    private fun constraintText(name: String?, body: String): String =
        if (name != null) "CONSTRAINT $name $body" else body

    private fun createTableBodyText(
        columns: List<Column>,
        foreignKeys: List<ForeignKeyConstraint>,
        checks: List<CheckConstraint>,
        uniqueConstraints: List<UniqueConstraint>,
        compositePrimaryKey: List<String> = emptyList()
    ): String {
        val parts = columns.map { columnDefinition(it) } +
            (if (compositePrimaryKey.isNotEmpty()) listOf("PRIMARY KEY (${compositePrimaryKey.joinToString(", ")})") else emptyList()) +
            foreignKeys.map { constraintText(it.name, foreignKeyText(it)) } +
            checks.map { constraintText(it.name, "CHECK (${expression(it.expression)})") } +
            uniqueConstraints.map { constraintText(it.name, "UNIQUE (${it.columns.joinToString(", ")})") }
        return parts.joinToString(", ")
    }

    private fun foreignKeyText(fk: ForeignKeyConstraint): String = buildString {
        append("FOREIGN KEY (${fk.columns.joinToString(", ")}) REFERENCES ${fk.referencedTable} (${fk.referencedColumns.joinToString(", ")})")
        if (fk.onDelete != ForeignKeyAction.NoAction) append(" ON DELETE ${fk.onDelete.sqlKeyword}")
        if (fk.onUpdate != ForeignKeyAction.NoAction) append(" ON UPDATE ${fk.onUpdate.sqlKeyword}")
    }

    private fun dropModeText(mode: DropMode): String = if (mode == DropMode.Cascade) " CASCADE" else ""

    private fun expression(expression: Expression): String = when (expression) {
        is LiteralExpression -> literal(expression.value)
        is ColumnExpression -> expression.qualifier?.let { "$it.${expression.name}" } ?: expression.name
        is FunctionExpression -> "${expression.name}(${functionArgumentsText(expression)})${filterClauseText(expression)}"
        is BinaryExpression ->
            "(${expression(expression.left)} ${expression.operator.symbol} ${expression(expression.right)})"
        is UnaryExpression -> "(${expression.operator.symbol} ${expression(expression.operand)})"
    }

    private fun literal(value: Value): String = when (value) {
        is Value.IntegerValue -> value.value.toString()
        is Value.RealValue -> realText(value.value)
        is Value.TextValue -> "'" + value.value.replace("'", "''") + "'"
        is Value.BooleanValue -> value.value.toString()
        is Value.NullValue -> "NULL"
    }

    private fun realText(value: Double): String {
        val plain = value.toString()
        return if (plain.contains('E') || plain.contains('e')) BigDecimal(value).toPlainString() else plain
    }
}

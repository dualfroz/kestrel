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

/**
 * Executes a deliberately narrow subset of the SQL AST directly against
 * [DiskCatalog]/[DiskTable]: CREATE/DROP TABLE, INSERT, SELECT (WHERE/ORDER
 * BY/LIMIT/GROUP BY/HAVING/aggregates, window functions (ROW_NUMBER/RANK/
 * DENSE_RANK/aggregate/LAG/LEAD/FIRST_VALUE/LAST_VALUE/NTILE OVER
 * PARTITION BY/ORDER BY, including an explicit ROWS/RANGE/GROUPS frame with
 * EXCLUDE), INNER/LEFT/RIGHT/FULL/CROSS JOIN across any number of table, derived-table
 * (`FROM (SELECT ...) AS alias`) or LATERAL derived-table sources,
 * scalar/EXISTS/IN subqueries anywhere an expression is allowed - including
 * correlated to an outer row - UNION/UNION ALL/INTERSECT/INTERSECT ALL/
 * EXCEPT/EXCEPT ALL and WITH/WITH RECURSIVE (CTE, fixed-point with an
 * iteration limit)), UPDATE and DELETE, each top-level statement running inside its own disk
 * transaction (commit on success, abort on any exception so a failure never
 * leaves a partially applied statement); a subquery or nested query (set
 * operation branch, CTE definition) runs inside the same transaction as the
 * statement that contains it rather than opening one of its own.
 *
 * This is additive, not a replacement for [kestrel.engine.Executor]: it
 * reuses the same AST ([kestrel.sql.ast]), the same [Evaluator] and its
 * [SubqueryRunner] hook, the same [GroupAggregation]/[AggregateFunctions]
 * (both operate purely on `List<Value>` rows, so they need no disk-specific
 * counterpart), the same [StatementResult] types and the same
 * [Column]/[TableSchema] the in-memory engine already uses (both
 * [DiskCatalog] and [DiskTable] were built against those exact types for
 * this reason), so no translation layer sits between the parser and this
 * executor - a query that resolves against the disk backend evaluates
 * expressions with exactly the same semantics as the in-memory one. Window
 * function evaluation itself (frame resolution, peer groups, RANGE distance
 * search) is deliberately duplicated from [kestrel.engine.SelectRunner]
 * rather than shared, for the same free-standing-isolation reason as every
 * other validation this executor duplicates (see [validateUniqueDefinitions]).
 * `CREATE [OR REPLACE] VIEW`/`DROP VIEW [CASCADE]` are supported (materialized fresh on every
 * reference - as a plain FROM/JOIN source, inside a CTE body, or on either side of a set operation,
 * since all three already resolve a name through the same [resolveFromSource] - view-over-view,
 * recursive-definition and RESTRICT/CASCADE dependent-view detection, mirroring
 * [kestrel.engine.Executor]/[kestrel.catalog.Catalog]'s own view handling; see
 * [DiskCatalog.createView]/[DiskCatalog.dropView]). A derived table (`FROM (SELECT ...) AS alias`,
 * see [resolveFromItem]) runs eagerly, once, since it cannot correlate to anything; a LATERAL derived
 * table (`JOIN LATERAL (SELECT ...) AS alias ON ...`) is instead re-run once per row already
 * accumulated from earlier FROM/JOIN sources, correlated to that row exactly like a WHERE-clause
 * correlated subquery (see [executeLateralJoin]) - mirroring [kestrel.engine.SelectRunner]'s own
 * handling of both.
 * Table-level composite PRIMARY KEY, UNIQUE (inline/table-level `UNIQUE`
 * constraints, any number of columns), `CREATE [UNIQUE] INDEX`
 * (single-column or composite), column-level/table-level CHECK constraints (validated column
 * references/no subquery-aggregate-window at CREATE TABLE time via [validateCheckDefinitions],
 * enforced on every INSERT/UPDATE via [enforceChecks], persisted as rendered expression text - see
 * [CheckConstraintEntry]), and column-level/table-level FOREIGN KEY constraints - including
 * composite keys, self-reference, and `ON DELETE`/`ON UPDATE` `CASCADE`/`SET NULL`/`RESTRICT` (default
 * `NO ACTION` behaves like `RESTRICT`), enforced via [DiskForeignKeyEnforcer] and blocking `DROP
 * TABLE` (RESTRICT by default, `CASCADE` strips the constraint from every referencing table without
 * dropping it - see [DiskCatalog.dropTable]) - are supported, mirroring
 * [kestrel.engine.Executor]'s own handling of them. `ALTER TABLE ... ADD/DROP/RENAME COLUMN/RENAME
 * TO` (see [executeAlterTable]/[DiskCatalog.addColumn]/[DiskCatalog.dropColumn]/
 * [DiskCatalog.renameColumn]/[DiskCatalog.renameTable]) is supported with the same RESTRICT/CASCADE
 * and FOREIGN KEY/UNIQUE/CHECK/index propagation rules as [kestrel.engine.Executor]/
 * [kestrel.catalog.Catalog]. `ALTER TABLE ... ADD CONSTRAINT name CHECK/UNIQUE/FOREIGN KEY` and
 * `DROP CONSTRAINT name` (see [executeAddConstraint]/[DiskCatalog.dropConstraint]) validate every
 * existing row before installing a new constraint (via a probe transaction, mirroring
 * [executeAddColumn]'s own `probeTxn`) and give a UNIQUE constraint the same RESTRICT/CASCADE
 * treatment against a dependent FOREIGN KEY that [DiskCatalog.dropTable] already gives a whole table,
 * and so is `DROP TABLE ... CASCADE` itself (see [executeDropTable]),
 * which additionally drops every transitively dependent view - no DDL mode remains unsupported.
 * `BEGIN`/`COMMIT`/`ROLLBACK` (see [executeBegin]/[executeCommit]/
 * [executeRollback]) group any number of INSERT/SELECT/UPDATE/DELETE statements into one disk
 * transaction with one commit/abort boundary, backed directly by the storage engine's own WAL/2PL
 * transaction rather than a copy of [kestrel.engine.Executor]'s whole-catalog snapshot/restore -
 * DDL is deliberately rejected while a transaction is open (see [isDdl] in [execute]) because there
 * is no equivalent way to undo a [DiskCatalog] schema change by aborting a storage-engine
 * transaction. `CREATE`/`DROP`/`ALTER SEQUENCE` and `NEXTVAL`/`CURRVAL` (see [executeCreateSequence]/
 * [executeDropSequence]/[executeAlterSequence]/[nextValue]/[currentValue]) are supported with the
 * same Postgres-mirroring semantics as [kestrel.engine.Executor] - NEXTVAL alone survives a ROLLBACK
 * that undoes everything else the rolled-back statement did (see [DiskCatalog.nextValue]), CURRVAL is
 * scoped to this [DiskExecutor] instance the same way it is scoped to one [kestrel.engine.Executor],
 * and both are restricted to exactly the same three positions (INSERT VALUES/DEFAULT, UPDATE SET/
 * DEFAULT) for the same volatile-function reason - CHECK constraints and `WHERE` reject them outright.
 * `SAVEPOINT`/`RELEASE [SAVEPOINT]`/`ROLLBACK TO SAVEPOINT` (see [executeSavepoint]/
 * [executeReleaseSavepoint]/[executeRollbackToSavepoint]) are supported inside an explicit transaction,
 * with the same stack semantics (reusing a name shadows the older savepoint of that name; releasing or
 * rolling back to one forgets every savepoint established after it, but rolling back keeps the target
 * itself usable again) as [kestrel.engine.Executor]. Unlike that whole-catalog-snapshot
 * implementation, this one partially rolls back the storage engine's own WAL transaction - see
 * [kestrel.store.wal.LogRecord.CompensationUpdate] and [kestrel.store.wal.RecoveryManager.rollbackTo]
 * for the ARIES-style compensation log records that make undoing only a suffix of a transaction safe
 * to repeat and safe to crash through, and [kestrel.store.engine.StorageEngine.savepoint]/
 * [kestrel.store.engine.StorageEngine.rollbackToSavepoint] for how a B+Tree's root pointer (plain
 * in-memory state no log record tracks) and the buffer pool's cache of pages the undo just overwrote
 * on disk are kept in sync with it.
 * `CREATE`/`DROP TRIGGER` `BEFORE`/`AFTER INSERT`/`UPDATE`/`DELETE FOR EACH ROW` (see
 * [executeCreateTrigger]/[executeDropTrigger]/[fireRowTriggers]/[runTriggerBody]) mirror
 * [kestrel.engine.Executor]'s own trigger semantics 1:1 (optional `WHEN`, `NEW`/`OLD` resolved via
 * the shared [kestrel.engine.TriggerRowContext], cascading fires across tables up to
 * [MAX_TRIGGER_DEPTH]) with one deliberate difference in how atomicity is achieved: instead of a
 * whole-catalog snapshot/restore, a whole statement plus every trigger it cascades into is undone by
 * [withTriggerAtomicity] taking one internal storage-engine savepoint (see [DiskCatalog.savepoint])
 * at the outermost call and rolling back to it on any failure - reusing the exact same primitive
 * `SAVEPOINT` is built on, just invisibly. `INSERT ... ON CONFLICT` on a table with a trigger defined
 * fires that table's own INSERT triggers for a row that lands fresh and its own UPDATE triggers for a
 * row folded into `DO UPDATE` (never both for the same row, and never anything for `DO NOTHING`) - see
 * [executeInsertWithConflictHandling] - mirroring the in-memory engine's own resolution of the same gap.
 * `TRUNCATE TABLE t1 [, t2, ...] [RESTART IDENTITY | CONTINUE IDENTITY] [CASCADE | RESTRICT]` (see
 * [executeTruncate]/[DiskCatalog.truncateTables]) empties every listed table - and, under CASCADE,
 * every table transitively reaching it via a FOREIGN KEY - without firing a single row-level trigger
 * or consulting any FOREIGN KEY's `onDelete` action, sharing the calling statement's transaction
 * exactly like a WHERE-less DELETE; `RESTART IDENTITY` additionally restarts every sequence backing a
 * `DEFAULT NEXTVAL(...)` column of a truncated table, the one part of this statement that is NOT
 * covered by that same transaction - see [executeTruncate]'s own doc for why, mirroring
 * [DiskCatalog.nextValue]'s already-established non-transactional sequence position.
 * `MERGE INTO target USING source ON onCondition WHEN [NOT] MATCHED [AND cond] THEN ...` (see
 * [executeMerge]/[executeMergeInner]) mirrors [kestrel.engine.Executor]'s own MERGE 1:1 - `source` is
 * a table or derived-table subquery resolved once, up front, into a fixed snapshot via
 * [resolveMergeSource]/[resolveFromItem], `ON` is matched against a snapshot of the target table taken
 * before any mutation (not the live, mutating [DiskTable]), and a target row already affected earlier
 * in the same statement throws rather than silently applying a second action - the one difference from
 * the in-memory engine is that "already affected"/"this pair's target row" is tracked by
 * [kestrel.store.heap.RecordId] rather than [Row] object identity, since a disk row is decoded fresh
 * on every read and so is never `===` to an earlier read of "the same" row.
 * `PREPARE name AS <SELECT|INSERT|UPDATE|DELETE|MERGE>`/`EXECUTE name(args...)`/`DEALLOCATE [PREPARE]
 * name|ALL` (see [executePrepare]/[resolveExecute]/[executeDeallocate]) are ported 1:1 from
 * [kestrel.engine.Executor]'s own registry - session-scoped, kept outside [DiskCatalog] for the same
 * reason ([explicitTxn] `COMMIT`/`ROLLBACK` never touches it), sharing the same [ParameterSubstitution]
 * walkers so `$1`, `$2`, ... resolve identically on both engines.
 */
class DiskExecutor(private val catalog: DiskCatalog) : SequenceAccess {
    private val accessPlanner = DiskIndexAccessPlanner()
    private val activeCtes = mutableListOf<CteScope>()
    private val expandingViews = mutableSetOf<String>()

    /** Guards against a trigger whose body (directly or transitively) fires itself forever - mirrors [kestrel.engine.Executor.triggerDepth]. */
    private var triggerDepth = 0

    /**
     * Postgres-session-scoped CURRVAL cache, ported 1:1 from [kestrel.engine.Executor.
     * lastSequenceValues] - see that field's own doc for why it is keyed by [Sequence] identity
     * rather than by name.
     */
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

    /**
     * Session-scoped, ported 1:1 from [kestrel.engine.Executor.preparedStatements] - deliberately
     * outside [DiskCatalog], since a prepared statement carries no data of its own to undo: it is
     * unaffected by `COMMIT`/`ROLLBACK` on [explicitTxn] the same way it is unaffected by
     * catalog-snapshot rollback on the in-memory engine.
     */
    private val preparedStatements = linkedMapOf<String, PreparableStatement>()

    val preparedStatementNames: List<String> get() = preparedStatements.keys.toList()

    /** The disk transaction opened by a `BEGIN` still awaiting `COMMIT`/`ROLLBACK`, or null outside one. */
    private var explicitTxn: Long? = null

    /** Whether an explicit `BEGIN` is currently open - mirrors [kestrel.engine.Executor.inTransaction]. */
    val inTransaction: Boolean get() = explicitTxn != null

    /**
     * Named savepoints established inside the currently open [explicitTxn], oldest first, each paired
     * with the storage engine LSN [DiskCatalog.savepoint] captured for it - mirrors
     * [kestrel.engine.Executor]'s own `savepoints` stack (catalog snapshots there, WAL positions here)
     * including reuse-shadows-the-older-one-of-the-same-name semantics via [indexOfLastSavepoint].
     */
    private val savepoints = mutableListOf<Pair<String, Long>>()

    /**
     * A temp table's rows live in a plain [Table.rows] list outside [DiskCatalog]'s own WAL/2PL
     * transaction (see [DiskCatalog]'s KDoc on [DiskCatalog.temporaryTableValues]), so `ROLLBACK`/
     * `ROLLBACK TO SAVEPOINT` cannot undo a temp table's INSERT/UPDATE/DELETE the way they undo a
     * real [DiskTable]'s writes just by aborting/rewinding [explicitTxn] - this stack is what makes
     * them do it anyway. Index 0 is the snapshot taken at `BEGIN`; index `i + 1` is the snapshot
     * taken when `savepoints[i]` was established, so `ROLLBACK` restores index 0 and `ROLLBACK TO
     * SAVEPOINT` restores `temporaryTableSnapshots[index + 1]` - kept in lockstep with [savepoints]
     * by every method below. Only *row contents* ever need restoring, never which temp tables exist:
     * `CREATE`/`DROP TEMPORARY TABLE` are DDL, already rejected inside an explicit transaction (see
     * [isDdl]), so the set of temp tables is fixed for the whole lifetime of one [explicitTxn].
     */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.resolveExecute] - looks [ExecuteStatement.name] up,
     * evaluates every argument as a CONSTANT (a bare [Evaluator] with no resolver/subquery/sequence
     * access, exactly like a DEFAULT expression), and substitutes the results for every `$1`, `$2`,
     * ... via the same [ParameterSubstitution] both engines share. The result is always a concrete
     * [PreparableStatement] - never [ExecuteStatement] itself.
     */
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

    /**
     * Establishing a savepoint costs nothing beyond capturing the storage engine's current WAL
     * position and every open index's root pointer (see [DiskCatalog.savepoint]) plus a temp table
     * row snapshot (see [temporaryTableSnapshots]) - unlike [kestrel.engine.Executor], there is no
     * whole-CATALOG snapshot to take, since DDL (the only thing that mutates [catalog] itself rather
     * than a table's data) is already rejected inside an explicit transaction on this executor.
     */
    private fun executeSavepoint(statement: SavepointStatement): StatementResult {
        val txn = explicitTxn ?: throw DiskExecutorException("SAVEPOINT can only be used inside a transaction")
        savepoints.add(statement.name to catalog.savepoint(txn))
        temporaryTableSnapshots.add(snapshotTemporaryTables())
        return SavepointResult(statement.name)
    }

    /** Forgets the named savepoint and every savepoint established after it, without undoing anything. */
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

    /**
     * Undoes every write made since the named savepoint, but - matching [kestrel.engine.Executor] and
     * real SQL savepoint semantics - keeps that savepoint itself usable for a later rollback, only
     * discarding the ones established after it.
     */
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

    /**
     * Runs [block] against the transaction opened by a `BEGIN` still in progress so several
     * statements share one commit/abort boundary, or against a fresh transaction scoped to just this
     * statement (committed on success, aborted on failure via [runInTransaction]) when there isn't
     * one - the same per-statement auto-commit [kestrel.engine.Executor] gets for free from running
     * directly against an in-memory [kestrel.catalog.Catalog] with no transaction concept at all.
     * Locks taken by [block] are held by the shared transaction until that later COMMIT/ROLLBACK per
     * strict two-phase locking (see [kestrel.store.txn.LockManager]), so a SELECT inside an open
     * transaction can block a concurrent writer the way a real multi-statement transaction should.
     */
    private fun <T> withStatementTransaction(block: (Long) -> T): T {
        val active = explicitTxn
        if (active != null) return block(active)
        val txn = catalog.beginTransaction()
        return runInTransaction(txn) { block(txn) }
    }

    /**
     * Savepoint-based analog of [kestrel.engine.Executor.withStatementAtomicity]: without this, a
     * BEFORE trigger's own INSERT/UPDATE/DELETE would survive even when the statement that fired it
     * goes on to fail its own constraint check (or an AFTER trigger fails after the row already
     * landed) - a real database treats a whole statement plus everything its triggers do as one
     * atomic unit. Only the OUTERMOST statement in a trigger cascade takes the savepoint
     * ([triggerContext] is null exactly there, mirroring the in-memory engine's own `triggerContext ==
     * null` check): [catalog.savepoint]/[catalog.rollbackToSavepoint] are keyed purely by WAL LSN,
     * entirely independent of - and safely nestable inside - the executor's own named `SAVEPOINT`
     * stack ([savepoints]), so this composes with an explicit transaction the user is already inside
     * without disturbing it. Skipped entirely when [tableName] has no trigger at all, so the
     * overwhelmingly common trigger-free INSERT/UPDATE/DELETE pays no savepoint cost.
     */
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

    /**
     * Resolves `NEW.col`/`OLD.col` against [trigger] first (when both the qualifier and that side of
     * the row are present); everything else - including a bare column with no qualifier at all -
     * falls through to [base] exactly as it would with no trigger in play. Ported 1:1 from
     * [kestrel.engine.Executor.composeResolver], just with [DiskExecutorException] for the fallthrough.
     */
    private fun composeResolver(base: ColumnResolver?, trigger: TriggerRowContext?): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value =
            if (trigger != null && trigger.matches(expression)) trigger.resolve(expression)
            else base?.resolve(expression) ?: throw DiskExecutorException("column '${expression.name}' is not allowed here")

        override fun declaredCollation(expression: ColumnExpression): Collation? =
            if (trigger != null && trigger.matches(expression)) trigger.declaredCollation(expression)
            else base?.declaredCollation(expression)
    }

    /** Ported 1:1 from [kestrel.engine.Executor.rowResolver] - a [ColumnResolver] [composeResolver] can layer a [TriggerRowContext] on top of. */
    private fun rowResolver(schema: TableSchema, row: Row): ColumnResolver = object : ColumnResolver {
        override fun resolve(expression: ColumnExpression): Value {
            val index = schema.columnIndex(expression.name) ?: throw DiskExecutorException("no such column: ${expression.name}")
            return row.values[index]
        }

        override fun declaredCollation(expression: ColumnExpression): Collation? = schema.column(expression.name)?.collation
    }

    /**
     * Ported 1:1 from [kestrel.engine.Executor.evaluateReturning]: evaluates an INSERT/UPDATE/
     * DELETE statement's `RETURNING` clause against the rows it actually affected - the freshly
     * inserted/updated row for INSERT/UPDATE, the pre-deletion row for DELETE. [checkReturningColumns]
     * validates every projected expression up front so a bad column name is reported even when zero
     * rows matched, not just lazily the first time [rowResolver] happens to see one.
     */
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

    /**
     * Fires every trigger registered for ([schema], [timing], [event]) in creation order, once per
     * (oldRow, newRow) pair in [rows] - mirrors [kestrel.engine.Executor.fireRowTriggers] exactly,
     * including that a `WHEN` clause evaluating to false/NULL skips only that trigger for that row.
     */
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


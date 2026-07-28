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

    /**
     * Runs a fired trigger's body statements in order, directly against [txn] - NOT through
     * [executeInsert]/[executeUpdate]/[executeDelete] (which would re-select a transaction via
     * [withStatementTransaction] and re-take a trigger-atomicity savepoint via
     * [withTriggerAtomicity]) - so the whole cascade shares exactly one disk transaction and exactly
     * one savepoint, taken once by the outermost caller. Mirrors [kestrel.engine.Executor.
     * runTriggerBody], including [MAX_TRIGGER_DEPTH].
     */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.executeCreateSequence] - see that method's own doc
     * for the ascending/descending default resolution and the one thing [SequenceDefinition]'s own
     * `init` cannot check itself (START WITH falling inside [MINVALUE, MAXVALUE]).
     */
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

    /** Ported 1:1 from [kestrel.engine.Executor.executeAlterSequence] - see that method's own doc. */
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

    /**
     * Runs [CreateViewStatement.query] once, discarding the result, purely to fail fast on a bad
     * column/table reference before anything is persisted - mirrors [kestrel.engine.Executor.
     * executeCreateView] calling `selectRunner.explain` for the same reason, just via a real
     * execution rather than a plan-only walk (this executor has no `explain`).
     */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.executeCreateTrigger]: body statements are
     * deliberately NOT deep-validated here (no eager check that every column referenced in a body
     * statement's WHERE/SET/VALUES actually exists) - a trigger body can target a different table per
     * statement, and this executor already defers exactly this kind of check to the moment the
     * trigger actually fires, same as SQLite. What IS checked eagerly: the trigger's own table and
     * every body statement's target table both exist right now, and the WHEN clause - evaluated
     * directly by this executor rather than through a nested statement run, so nothing else will ever
     * validate it - contains no subquery/aggregate and only ever references NEW/OLD columns that both
     * exist on this table and are actually available for this trigger's event.
     */
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

    /** Ported 1:1 from [kestrel.engine.Executor.rejectReturning]: a trigger body statement's own [runTriggerBody] discards whatever [StatementResult] it returns, so a RETURNING clause there would compute output nobody could ever see. */
    private fun rejectReturning(returning: Projection?) {
        if (returning != null) {
            throw DiskExecutorException("RETURNING is not allowed in a trigger body statement - its result would never be visible to anything")
        }
    }

    /** Ported 1:1 from [kestrel.engine.Executor.validateTriggerRowReferences]. */
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

    /**
     * `CREATE TEMP TABLE`/`CREATE TEMPORARY TABLE` - the disk-backed executor's counterpart of
     * [executeCreateTable], but building a [kestrel.storage.Table] via [DiskCatalog.createTemporaryTable]
     * instead of a [DiskTable]: a real, live table for the rest of this process's lifetime (INSERT/
     * SELECT/UPDATE/DELETE, WHERE/JOIN/ORDER BY/subqueries, NOT NULL/PRIMARY KEY/UNIQUE/CHECK/DEFAULT/
     * GENERATED all enforced exactly like a permanent table - see [executeTemporaryInsert]/
     * [executeTemporaryUpdate]/[executeTemporaryDelete] and [resolveFromSource]), simply never
     * touching the pager/WAL and never surviving a reopen. FOREIGN KEY is rejected outright rather
     * than silently ignored: [DiskForeignKeyEnforcer] and its cascade bookkeeping are wired to
     * [DiskTable]/[DiskCatalog]'s directory, which a temp table deliberately has no entry in, so
     * honoring the constraint would need a parallel enforcement path this session scoped out - a
     * named, documented scope-cut rather than a constraint that looks declared but is never checked.
     */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.validateNextValueDefault]: checked statically rather
     * than by evaluating the DEFAULT for real, since a bare `CREATE TABLE` must not itself consume a
     * sequence value before a single row exists.
     */
    private fun validateNextValueDefault(default: NextValueExpression, column: Column) {
        catalog.sequence(default.sequenceName)
        if (column.type != ColumnType.Integer) {
            throw DiskExecutorException("NEXTVAL can only be used as a DEFAULT for an INTEGER column, but ${column.name} is ${column.type.displayName}")
        }
    }

    /**
     * Deliberately duplicated from [kestrel.engine.Executor.validateForeignKeyDefinitions] (same
     * reasoning as [coerceForColumn]): [columns] is already normalized by [applyCompositePrimaryKey],
     * matching the in-memory engine's own call order. A self-reference (`referencedTable` equal to
     * the table being created) is checked against a [TableSchema] built from [columns] rather than
     * the catalog, because the table doesn't exist there yet while it's being created.
     */
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

    /**
     * Deliberately duplicated from [kestrel.engine.Executor.validateCheckDefinitions] (same
     * reasoning as [coerceForColumn]): every CHECK expression must only reference a real column of
     * the table being created and must contain no subquery, aggregate, or window function - there is
     * no row yet to run one against, and this executor's [Evaluator] has no aggregation pass of its
     * own outside [GroupAggregation].
     */
    private fun validateCheckDefinitions(statement: CreateTableStatement) {
        val schema = TableSchema(statement.tableName, statement.columns)
        for (check in statement.checks) {
            checkColumns(check.expression, schema)
            requireNoSubquery(check.expression)
        }
    }

    /** Ported 1:1 from [kestrel.engine.Executor.checkColumns] - see [validateCheckDefinitions]. */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.checkColumns] (the general-purpose variant used for
     * WHERE/SET/RETURNING) - NOT [checkColumns] above, which is CHECK-constraint specific and
     * rejects NEXTVAL/CURRVAL outright. Used only by [evaluateReturning]: validates every column
     * reference in a RETURNING projection item exists on [schema] and that no aggregate/window
     * function sneaks in.
     */
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
            // Structurally harmless (no column reference to check) - whether MERGE_ACTION() is
            // actually legal at this particular call site (MERGE's own RETURNING: yes; plain INSERT/
            // UPDATE/DELETE's RETURNING: no) is enforced by whether the Evaluator this feeds into was
            // actually given a mergeAction, exactly like NEXTVAL/CURRVAL is enforced by SequenceAccess -
            // see evaluateMergeReturning, the one call site that binds one.
            is NextValueExpression, is CurrentValueExpression, is MergeActionExpression, is ParameterExpression -> Unit
        }
    }

    /** Ported 1:1 from [kestrel.engine.Executor.requireNoSubquery] - see [validateCheckDefinitions]. */
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

    /** Ported 1:1 from [kestrel.engine.Executor.validateGeneratedDefinitions] - see that method's own doc. */
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

    /** Ported 1:1 from [kestrel.engine.Executor.requireNoGeneratedColumnReference]. */
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

    /** Ported 1:1 from [kestrel.engine.Executor.applyGeneratedColumns]. */
    private fun applyGeneratedColumns(schema: TableSchema, values: MutableList<Value>, subqueries: SubqueryRunner) {
        if (schema.columns.none { it.generated != null }) return
        val evaluator = Evaluator(schema, Row(values), subqueries)
        for ((index, column) in schema.columns.withIndex()) {
            val generated = column.generated ?: continue
            values[index] = coerceForColumn(evaluator.evaluate(generated), column)
        }
    }

    /** Ported 1:1 from [kestrel.engine.Executor.rejectGeneratedAssignment]. */
    private fun rejectGeneratedAssignment(column: Column, explicitValueGiven: Boolean) {
        if (explicitValueGiven && column.generated != null) {
            throw DiskExecutorException("cannot assign an explicit value to GENERATED column ${column.name}")
        }
    }

    /**
     * Ported 1:1 from [kestrel.engine.Executor.enforceChecks]: a CHECK expression that evaluates to
     * SQL `FALSE` rejects the row; `TRUE` or `NULL` (three-valued "unknown", e.g. a comparison against
     * a NULL column) both accept it, per standard SQL.
     */
    private fun enforceChecks(schema: TableSchema, row: Row, subqueries: SubqueryRunner) {
        for (check in schema.checks) {
            val result = Evaluator(schema, row, subqueries).evaluate(check.expression)
            if (result is Value.BooleanValue && !result.value) {
                val label = check.name?.let { " $it" } ?: ""
                throw DiskExecutorException("CHECK constraint$label failed: ${schema.name}")
            }
        }
    }

    /**
     * NOT NULL enforcement for a temp table row - [DiskTable.insert]/[DiskTable.update] get this for
     * free from their own private `requireArity`, but a temp table's plain [kestrel.storage.Table]
     * has no such gate, so [executeTemporaryInsert]/[executeTemporaryUpdate] call this directly.
     * Ported 1:1 from [kestrel.engine.Executor.validateNotNull].
     */
    private fun validateTemporaryNotNull(schema: TableSchema, values: List<Value>) {
        for ((index, column) in schema.columns.withIndex()) {
            if (column.notNull && values[index] is Value.NullValue) {
                throw DiskExecutorException("NOT NULL constraint failed: ${schema.name}.${column.name}")
            }
        }
    }

    /**
     * PRIMARY KEY uniqueness for a temp table, checked by full scan over [rows] (the whole candidate
     * set post-mutation, not just the newly touched rows - same reasoning as
     * [kestrel.engine.Executor.enforcePrimaryKeyUniqueness]) since a temp table has no B+Tree to
     * enforce it structurally the way [DiskTable.rejectDuplicatePrimaryKey] does. Ported 1:1 from
     * that same in-memory method, [DiskExecutorException] aside.
     */
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

    /** UNIQUE-constraint counterpart of [enforceTemporaryPrimaryKeyUniqueness] - ported 1:1 from [kestrel.engine.Executor.enforceUniqueConstraints]. */
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

    /**
     * Deliberately duplicated from [kestrel.engine.Executor.validateUniqueDefinitions] (same
     * reasoning as [coerceForColumn]): only checks that every referenced column exists, mirroring
     * the in-memory engine exactly - it does not reject a constraint naming the same column twice
     * (`UNIQUE (a, a)`), because the in-memory engine does not either.
     */
    private fun validateUniqueDefinitions(statement: CreateTableStatement) {
        for (unique in statement.uniqueConstraints) {
            for (columnName in unique.columns) {
                if (statement.columns.none { it.name.equals(columnName, ignoreCase = true) }) {
                    throw DiskExecutorException("UNIQUE constraint refers to unknown column: $columnName")
                }
            }
        }
    }

    /**
     * A table-level `PRIMARY KEY (a, b, ...)` implies NOT NULL on every listed column, exactly
     * like the inline `col TYPE PRIMARY KEY` spelling already does. Deliberately duplicated from
     * [kestrel.engine.Executor.applyCompositePrimaryKey] (same reasoning as [coerceForColumn]):
     * this executor reuses the in-memory engine's AST/types but must stay free-standing so a
     * change to the in-memory engine's error handling can never silently affect the disk-backed
     * path or vice versa.
     */
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

    /**
     * Deliberately NOT in [isDdl] - like DELETE, TRUNCATE's row-clearing is fully covered by the
     * storage engine's own WAL/2PL transaction, so it may run inside an explicit `BEGIN` and roll
     * back cleanly with everything else that transaction did. `RESTART IDENTITY` is the one
     * exception: [DiskCatalog.alterSequence] always opens and commits its own transaction (mirroring
     * [DiskCatalog.nextValue]'s already-non-transactional sequence position - see that method's own
     * doc), so a sequence restarted here stays restarted even if the enclosing statement transaction
     * later rolls back. This is a deliberate, bounded asymmetry with [kestrel.engine.Executor]'s
     * in-memory engine, where RESTART IDENTITY *is* undone by ROLLBACK because ALTER SEQUENCE there
     * mutates a map entry covered by the whole-catalog snapshot/restore (see
     * [kestrel.catalog.Catalog.truncateTables]).
     */
    private fun executeTruncate(statement: TruncateTableStatement): StatementResult {
        val truncated = withStatementTransaction { txn ->
            catalog.truncateTables(txn, statement.tableNames, statement.mode == DropMode.Cascade)
        }
        if (statement.restartIdentity) {
            for (name in truncated) restartOwnedSequences(catalog.table(name).schema)
        }
        return TruncateTableResult(truncated)
    }

    /** Ported 1:1 from [kestrel.catalog.Catalog.restartOwnedSequences] - see that method's own doc for why a NEXTVAL DEFAULT is this engine's only signal for "this column is identity-backed". */
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

    /** Mirrors [kestrel.engine.Executor.executeAddConstraint] - see that method's own doc. */
    private fun executeAddConstraint(tableName: String, added: AddedConstraint) {
        when (added) {
            is AddedCheck -> executeAddCheckConstraint(tableName, added.check)
            is AddedUnique -> executeAddUniqueConstraint(tableName, added.unique)
            is AddedForeignKey -> executeAddForeignKeyConstraint(tableName, added.foreignKey)
        }
    }

    /**
     * `ALTER TABLE t ADD CONSTRAINT name CHECK (...)` - mirrors
     * [kestrel.engine.Executor.executeAddCheckConstraint]: validated exactly like a CREATE TABLE-time
     * CHECK ([validateCheckDefinitions]: known columns only, no subquery), PLUS every existing row is
     * evaluated against it up front (under its own probe transaction, mirroring [executeAddColumn]'s
     * `probeTxn`) before the constraint is installed via [DiskCatalog.addCheckConstraint].
     */
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

    /**
     * `ALTER TABLE t ADD CONSTRAINT name UNIQUE (...)` - mirrors
     * [kestrel.engine.Executor.executeAddUniqueConstraint]: every existing row is checked for a
     * duplicate combination of [unique]'s columns (folded through [collationFoldedUniquenessKey], NULL
     * exempt per standard SQL) under its own probe transaction before the constraint is installed.
     */
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

    /**
     * `ALTER TABLE t ADD CONSTRAINT name FOREIGN KEY (...) REFERENCES ...` - mirrors
     * [kestrel.engine.Executor.executeAddForeignKeyConstraint]: validated the same way
     * [validateForeignKeyDefinitions] validates one at CREATE TABLE time, PLUS every existing row of
     * [tableName] is checked against it via [DiskForeignKeyEnforcer.validateRow] (under its own probe
     * transaction) against a throwaway [TableSchema] carrying [foreignKey] alongside every FOREIGN KEY
     * [tableName] already has, so a self-referencing FOREIGN KEY can find its own sibling rows.
     */
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

    /**
     * Mirrors [kestrel.engine.Executor.executeChangeColumnType] - same RESTRICT-only dependency
     * checks (PRIMARY KEY/FOREIGN KEY on either side/UNIQUE constraint/secondary index/GENERATED,
     * all rejected outright rather than threading a type change through them) and the same DEFAULT
     * compatibility pre-check. The one structural difference: [DiskCatalog.changeColumnType]'s
     * `valueForRow` callback runs DURING the real per-row rewrite (via [DiskTable.rewriteRows]), not
     * before it - see that method's own doc for why a NOT NULL/CHECK failure discovered mid-rewrite
     * is still safe (transaction abort unwinds it), the same contract a NOT NULL `GENERATED ALWAYS
     * AS` backfill already relies on.
     */
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

    /** Mirrors [kestrel.engine.Executor.requireUniqueConstraintName] - see that method's own doc. */
    private fun requireUniqueConstraintName(schema: TableSchema, name: String) {
        val taken = schema.foreignKeys.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.checks.any { it.name?.equals(name, ignoreCase = true) == true } ||
            schema.uniqueConstraints.any { it.name?.equals(name, ignoreCase = true) == true }
        if (taken) throw DiskExecutorException("constraint '$name' already exists on table ${schema.name}")
    }

    /**
     * A NOT NULL column added to a table that already has rows must carry a DEFAULT (there is no
     * value to backfill existing rows with otherwise) - an empty table has no such requirement.
     * Mirrors [kestrel.engine.Executor.executeAddColumn] (same reasoning as [coerceForColumn]):
     * duplicated rather than shared, since this executor's [DiskTable] has no equivalent of the
     * in-memory engine's `Table.rows` to check emptiness against without a transaction of its own.
     * GENERATED ALWAYS AS sidesteps the NOT NULL/DEFAULT requirement entirely - see
     * [addGeneratedColumnValue].
     */
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

    /** Mirrors [kestrel.engine.Executor.validateAddGeneratedColumn] - see that method's own doc. */
    private fun validateAddGeneratedColumn(schema: TableSchema, column: Column) {
        val generated = column.generated!!
        checkColumns(generated, schema)
        requireNoSubquery(generated)
        val generatedNames = schema.columns.filter { it.generated != null }.map { it.name.lowercase() }.toSet()
        requireNoGeneratedColumnReference(generated, generatedNames, column.name)
    }

    /** Mirrors [kestrel.engine.Executor.addGeneratedColumnValue] - see that method's own doc. */
    private fun addGeneratedColumnValue(schema: TableSchema, column: Column, row: Row): Value {
        val value = coerceForColumn(Evaluator(schema, row).evaluate(column.generated!!), column)
        if (column.notNull && value is Value.NullValue) {
            throw DiskExecutorException("NOT NULL constraint failed: ${schema.name}.${column.name}")
        }
        return value
    }

    /**
     * A DEFAULT must be a constant expression: no column reference (there is no row to read one
     * from when backfilling `ALTER TABLE ... ADD COLUMN`), no subquery, and no aggregate. Ported
     * 1:1 from [kestrel.engine.Executor.validateConstantDefault] - see [checkColumns] for why this
     * executor duplicates rather than shares this kind of validation.
     */
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

    /**
     * Builds every row's final values first (in [newRows], none written yet) and only then validates
     * and inserts them one at a time - not inline in a single pass - so that a self-referencing FK
     * can see sibling rows from the same multi-row INSERT regardless of their order in the statement,
     * exactly like [kestrel.engine.Executor.executeInsert]'s own `uncommittedSiblings = newRows`.
     */
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

    /**
     * INSERT into a temp table's plain [Table.rows] list - ported from the same validate-the-whole-
     * batch-before-mutating-anything shape as [executeInsertInTxn]/[kestrel.engine.Executor.
     * executeInsertInner], minus [DiskForeignKeyEnforcer]/trigger firing (both rejected outright at
     * `CREATE TEMP TABLE` time - see [executeCreateTemporaryTable]) and minus ON CONFLICT (a separate,
     * documented scope-cut: [executeInsertWithConflictHandling]'s `findConflictingRow` scans a live
     * [DiskTable] via [DiskTable.scan], which a temp table has none of). Still opens a real disk
     * transaction via [withStatementTransaction] purely so a DEFAULT/CHECK/GENERATED expression's
     * scalar subquery (via [subqueryRunnerFor]) or a NEXTVAL default can read/advance the disk
     * catalog consistently - nothing about the temp table's own row list ever touches it.
     */
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
            // Fires before any constraint is even checked, matching SQLite/Postgres: a BEFORE
            // trigger can reject the whole statement (by having its body throw) before this
            // executor's own FOREIGN KEY/CHECK/UNIQUE validation ever runs.
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

    /** Ported 1:1 from [kestrel.engine.Executor.uniqueKeysOf]: PRIMARY KEY, then every declared UNIQUE constraint, then every UNIQUE secondary index - the same three sources a conflict target may name. */
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

    /** Ported 1:1 from [kestrel.engine.Executor.resolveConflictTargetKeys]: empty [targetColumns] (bare `ON CONFLICT DO ...`) matches against any of [keys]. */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.findConflictingRow], but scanning the live
     * [DiskTable] (via [DiskTable.scan]) rather than an in-memory `List<Row>` snapshot - the
     * transactional heap already reflects any row this same statement inserted or updated
     * earlier in [executeInsertWithConflictHandling]'s per-row loop, giving the same
     * read-your-own-writes semantics the in-memory engine gets from folding sibling rows into
     * its candidate list by hand. NULLs never collide with each other (standard SQL).
     */
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.executeInsertWithConflictHandling]: each candidate
     * row is processed as its own micro-insert against the table's current state (not a
     * batch-validated snapshot), so a later row in the same multi-row INSERT correctly sees an
     * earlier row's insert or update. [triggerContext] can be non-null here for two independent
     * reasons: [schema] itself may have triggers of its own (fired per row below, exactly like
     * [kestrel.engine.Executor] does), and/or this INSERT ... ON CONFLICT may itself be a trigger
     * body statement targeting a *different* table, so `NEW`/`OLD` from the outer firing trigger
     * must still resolve in its DO UPDATE assignment values - both compose without conflict since
     * [fireRowTriggers] below builds its own fresh [TriggerRowContext] from [schema]'s own triggers,
     * independent of [triggerContext]. Unlike the batch INSERT/UPDATE paths, no separate reindex step
     * is needed around trigger firing here: [DiskTable]'s secondary indexes are B+Trees maintained
     * incrementally by [table.insert]/[table.update] themselves, not rebuilt in a batch pass like
     * [kestrel.catalog.Catalog.reindexTable], so a trigger body's own query against this table always
     * sees a consistent index the instant this row's own mutation returns.
     */
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
        // DO NOTHING contributes no row, and a DO UPDATE row whose WHERE guard fails `continue`s
        // before reaching the append below - both absent from RETURNING, matching Postgres, same
        // as the in-memory engine.
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
                    // NEXTVAL/CURRVAL are deliberately usable in the assignment values below but not
                    // in this WHERE-shaped guard, matching the in-memory engine's own two-Evaluator split.
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
                    // Fires before any constraint below is checked, same as executeUpdateInTxn's own
                    // BEFORE trigger - a BEFORE UPDATE trigger's body can reject this row's update
                    // before this executor's own validation ever runs.
                    fireRowTriggers(schema, TriggerTiming.Before, TriggerEvent.Update, listOf(conflictingRow to updated), txn)
                    DiskForeignKeyEnforcer.validateRow(catalog, txn, schema, updated.values, uncommittedSiblings = emptyList())
                    enforceChecks(schema, updated, subqueries)
                    // Same as executeUpdate: any table with a FK pointing at one of this table's
                    // enforced-unique key groups needs a cascade/RESTRICT pass when that group's
                    // value actually changes, not just the PRIMARY KEY.
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

    /**
     * Ported 1:1 from [kestrel.engine.Executor.excludedAwareResolver]: bare column names resolve
     * against [target] (the existing conflicting row, matching plain UPDATE semantics), while
     * `EXCLUDED.column` resolves against [excluded] (the row proposed by this INSERT that failed
     * to land) - the same pseudo-table Postgres exposes in DO UPDATE.
     */
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

    /**
     * Closes the "this executor has no `explain`" scope cut documented on [executeCreateView]:
     * describes the plan [runSelect]/[runQuery] would use for [statement.select] without running
     * it, mirroring [kestrel.engine.SelectRunner.explain]'s shape (Seq Scan/Index Scan header per
     * driving source, Nested Loop per JOIN, GroupAggregate/Aggregate, Sort, Limit, CTE/UNION/WITH
     * RECURSIVE nesting) with two differences forced by this executor's disk-backed row supply
     * rather than the in-memory engine's already-resident rows:
     *
     * - A materialized CTE, a VIEW, or a non-LATERAL derived table in FROM still runs its defining
     *   query for real via [resolveFromItem]/[buildCteScope] (exactly what [runSelect] itself would
     *   do, and exactly what [kestrel.engine.SelectRunner.explain] does too through `resolveSource`)
     *   since only running it produces a real schema/row set to plan further nesting against. The
     *   query's own driving table(s) are never scanned this way, though: [resolveFromItem] only
     *   wraps a real [DiskTable] in a [DiskTableSource] (schema/index metadata, no I/O) - the
     *   [DiskTableSource.scan] a real [runSelect] would call to actually read rows is deliberately
     *   never invoked here, so `EXPLAIN SELECT * FROM huge_table` costs nothing proportional to
     *   `huge_table`'s size the way `SELECT * FROM huge_table` would.
     * - [applyJoin] has no hash-join optimization at all (see its own doc), so "Nested Loop" is the
     *   only join header this ever prints - "Hash Join" (which the in-memory engine's EXPLAIN can
     *   print for an equality ON condition) never appears here.
     *
     * Deliberately narrower than [kestrel.engine.SelectRunner.explain] in one respect: it reuses
     * every validation [runSelect] performs *structurally*, independent of row data - duplicate
     * alias detection ([buildJoinSources]), window-function placement ([rejectWindowFunction]/
     * [validateWindows]), and GROUP BY/aggregate correctness ([validateAggregation], itself
     * structural since [resolveIndex] only consults [JoinSource.schema], never row data) - but does
     * NOT eagerly resolve WHERE/ON column references the way [kestrel.engine.SelectRunner.explain]'s
     * `Plan.validate()` does. [runSelect] itself only resolves those against real row data inside
     * `combined.filter { ... }`/[applyJoin], so on an empty table a bad WHERE/ON column reference
     * never surfaces there either (a pre-existing, documented gap versus the in-memory engine, not
     * a new one introduced here) - EXPLAIN staying exactly as permissive keeps its guarantees no
     * stronger than what actually running the statement would give you.
     */
    private fun executeExplain(statement: ExplainStatement): StatementResult =
        withStatementTransaction { txn -> ExplainResult(explainQuery(statement.select, txn)) }

    private fun explainQuery(query: QueryStatement, txn: Long): List<String> = when (query) {
        is SelectStatement -> explainSelect(query, txn)
        is SetOperationStatement -> explainSetOperation(query, txn)
        is WithQuery -> explainWith(query, txn)
    }

    /** Title-case labels for an EXPLAIN header - deliberately separate from [setOperatorKeyword]'s ALL-CAPS SQL-keyword text used in error messages. */
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

    /**
     * Materializes every CTE for real via [buildCteScope] (needed so a query nested under this WITH
     * can itself be explained against a real schema/row set), then re-walks each CTE's own query a
     * second time purely to render its plan text - the same double pass [kestrel.engine.SelectRunner.
     * explainWith] already accepts for the in-memory engine, including the same cost of re-running a
     * *nested* recursive CTE's fixed point twice over if one WITH RECURSIVE is nested inside another.
     */
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

    /** The driving source's scan line: an indexed [DiskAccessPlan] when there is no JOIN and it is a real disk table, a full scan otherwise - mirrors [initialRows]'s own choice of when to push down. */
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

    /** Ported 1:1 from [kestrel.engine.SelectRunner.describeExpression] - renders any expression as SQL text for an EXPLAIN header (currently only GROUP BY keys reach this). */
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

    /** Runs [select] within [txn] and hands the result straight back to whichever [Evaluator] asked for it. */
    private fun subqueryRunnerFor(txn: Long): SubqueryRunner = SubqueryRunner { select, outer -> runSelect(select, txn, outer) }

    /**
     * Dispatches [query] to the runner matching its concrete shape, mirroring
     * [kestrel.engine.SelectRunner.runQuery] 1:1: a plain SELECT runs directly, a
     * UNION/INTERSECT/EXCEPT chain combines two independently-run sides, and a WITH/WITH RECURSIVE
     * query first materializes its common table expressions (visible only for the duration of this
     * call, via [activeCtes]) before running its body.
     */
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

    /**
     * Deliberately mirrors [kestrel.engine.SelectRunner]'s own `runSetOperation`: both sides run
     * independently (each may itself be a nested set operation, WITH query, or plain SELECT), then
     * combined by multiset/set semantics (UNION/INTERSECT/EXCEPT dedupe via `List<Value>` structural
     * equality, the `ALL` variants preserve duplicate counts through [multisetFilter]), with ORDER
     * BY/LIMIT applying only to the combined result - never to either side individually, which is
     * why [kestrel.sql.parser.Parser] never lets an inner SELECT-core in a compound query own its own
     * ORDER BY/LIMIT.
     */
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

    /**
     * Mirrors [kestrel.engine.SelectRunner]'s own `declaredSetOperationCollation` 1:1 (separate
     * copy, historical reasons like the rest of this class): the declared `COLLATE` of a set
     * operation's combined output column, traced back to a real table column in [statement]'s LEFT
     * branch only, resolving only for a plain `SELECT ... FROM realTable [JOIN realTable ...]` with
     * no CTE, view, or derived table anywhere in its FROM clause. Everything else - an expression,
     * a nested set operation, a subquery source - reports no declared collation, same as before this
     * function existed.
     */
    private fun declaredSetOperationCollation(statement: SetOperationStatement, columns: List<String>): (ColumnExpression) -> Collation? =
        (statement.left as? SelectStatement)?.let { declaredCollationForSelectOutput(it, columns) } ?: { null }

    /**
     * Mirrors [kestrel.engine.SelectRunner]'s own `declaredCollationForSelectOutput` 1:1 (separate
     * copy, historical reasons like the rest of this class): factored out of
     * [declaredSetOperationCollation] so [declaredRecursiveCteCollation] can apply the identical
     * "plain SELECT over real tables" resolution to a recursive CTE's own anchor (base) term.
     */
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

    /** Mirrors [kestrel.engine.SelectRunner]'s own `declaredRecursiveCteCollation` 1:1 (separate copy, historical reasons like the rest of this class). */
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

    /** Resolves ORDER BY columns of a combined UNION/INTERSECT/EXCEPT result purely by output column name (never qualified). */
    private fun evaluateOutputRow(columns: List<String>, expression: Expression, row: List<Value>, txn: Long): Value {
        val resolver = ColumnResolver { column ->
            if (column.qualifier != null) throw DiskExecutorException("no such column: ${column.qualifier}.${column.name}")
            val index = columns.indexOfFirst { it.equals(column.name, ignoreCase = true) }
            if (index == -1) throw DiskExecutorException("no such column: ${column.name}")
            row[index]
        }
        return Evaluator(resolver, subqueryRunnerFor(txn)).evaluate(expression)
    }

    /**
     * Pushes every CTE of [query] onto [activeCtes] (in definition order, so a later CTE may
     * reference an earlier one, mirroring [kestrel.engine.SelectRunner.runWith]), runs [query]'s
     * body, then pops them back off - a CTE is scoped to the single query that defines it, unlike a
     * VIEW, which lives in the catalog.
     */
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

    /**
     * Materializes one CTE's rows. A non-recursive CTE just runs its query once. A recursive one
     * (`WITH RECURSIVE name AS (base UNION [ALL] recursiveTerm)`, split by [splitRecursiveCte])
     * repeatedly re-runs [RecursiveCtePlan.recursiveTerm] with [CteScope.rows] set to only the
     * *previous iteration's new rows* (the standard fixed-point working-set semantics, not the
     * whole accumulated set - mirrors [kestrel.engine.SelectRunner.buildCteScope] exactly, including
     * the [MAX_RECURSION_ITERATIONS] safety cap against a query that never reaches a fixed point).
     */
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

    /**
     * Recognizes `base UNION [ALL] recursiveTerm` where exactly one side names [cte] in its own
     * FROM/JOIN - structurally, on the AST, without running anything - mirroring
     * [kestrel.engine.SelectRunner.splitRecursiveCte] 1:1. Both sides referencing it, or neither,
     * are rejected/treated as a plain (non-recursive) compound respectively, exactly like the
     * in-memory engine.
     */
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

    /**
     * A synthetic schema for a CTE's materialized rows: same idea as
     * [kestrel.engine.SelectRunner.derivedSchema] - each column's type is inferred from the first
     * non-NULL value seen in it (defaulting to TEXT when every row is NULL there), since a CTE has
     * no backing [TableSchema] of its own to borrow one from.
     */
    private fun derivedSchema(name: String, result: SelectResult, labels: List<String> = result.columns): TableSchema {
        val columns = labels.mapIndexed { index, columnName ->
            val type = result.rows.asSequence().map { it[index] }.firstOrNull { !it.isNull }?.type ?: ColumnType.Text
            Column(columnName, type, notNull = false, primaryKey = false)
        }
        return TableSchema(name, columns)
    }

    private class CteScope(val name: String, val schema: TableSchema, var rows: List<List<Value>>)

    /** One FROM/JOIN source's row supply: either a real disk table or a CTE's already-materialized rows. */
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

    /**
     * Resolves one FROM/JOIN [name] to its row supply: an active CTE shadows a same-named real
     * table or view (checked innermost-first via [MutableList.lastOrNull], exactly like
     * [kestrel.engine.SelectRunner.resolveNamedSource]'s own CTE lookup), then a temp table, then a
     * real disk table, and only then a view - materialized fresh by running its defining query
     * inside the same [txn], with [expandingViews] guarding against a view (directly or
     * transitively) referencing itself, mirroring [kestrel.engine.SelectRunner.resolveNamedSource]
     * exactly. A temp table's current rows are captured into a [CteRowSource] snapshot exactly like
     * a CTE's or view's - a plain SELECT never mutates its own FROM source mid-query, so this is
     * exactly as fresh as scanning a live [DiskTable] would be, and it is what gives a temp table
     * full JOIN/subquery/aggregate participation for free instead of needing its own parallel query
     * pipeline. A name that is neither falls through to [DiskCatalog.table], solely for its
     * "no such table" [DiskCatalogException] - already confirmed absent from
     * [DiskCatalog.temporaryTableOrNull]/[DiskCatalog.tableOrNull]/[DiskCatalog.viewOrNull], so this
     * always throws, preserving the exact exception type callers relied on before views existed.
     */
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

    /**
     * Resolves one FROM/JOIN [item] to its row supply, mirroring
     * [kestrel.engine.SelectRunner.resolveSource]/`buildSources`: a [TableSource] falls through to
     * [resolveFromSource] by name (CTE/table/view, unchanged); a non-LATERAL [SubquerySource] (a
     * derived table) runs its query eagerly, right here, inside the same [txn] - it cannot correlate
     * to anything, so its rows are fixed for the whole statement, exactly like a materialized view. A
     * LATERAL [SubquerySource] is different: its rows depend on whichever row of an earlier FROM/JOIN
     * source it correlates with, which does not exist yet at this point - only its column *names* are
     * needed here (via [lateralSchema], computed structurally from the AST without running anything)
     * for offset bookkeeping; its real rows are produced later, per left row, in [executeLateralJoin].
     */
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

    /**
     * The output column names of [query], derived purely from its AST (no execution, no catalog row
     * data) - needed to give a LATERAL derived table a stable schema before any row of the source it
     * correlates with is known. Mirrors [kestrel.engine.SelectRunner.structuralColumnNames] 1:1.
     */
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

    /**
     * Runs [statement] against the disk backend inside the already-open [txn]. [outer] is
     * non-null exactly when this call is itself a scalar/EXISTS/IN subquery nested inside another
     * statement's expression, reached through [Evaluator]'s [SubqueryRunner] hook - it lets a
     * column [statement]'s own FROM/JOIN sources cannot resolve fall back to the row of whichever
     * statement contains it, mirroring [kestrel.engine.SelectRunner.run]'s own `outer` parameter.
     * A subquery therefore runs inside the same disk transaction as its containing statement
     * rather than opening (and committing/aborting) one of its own.
     */
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

    /**
     * `ORDER BY` may name a `SELECT`-list alias instead of repeating its expression (e.g.
     * `SELECT SUM(amount) AS total ... ORDER BY total`) - resolved here, once, before either
     * [GroupAggregation] or [sortCombinedRows] ever sees the order keys, mirroring
     * [kestrel.engine.SelectRunner]'s own `resolveOrderAlias`. A qualified column
     * (`ORDER BY t.total`) can never be an alias reference, so it is left untouched.
     */
    private fun resolvedOrderKeys(statement: SelectStatement): List<OrderKey> =
        statement.orderBy?.keys.orEmpty().map { key -> OrderKey(resolveOrderAlias(statement.projection, key.expression), key.descending, key.nullsFirst) }

    private fun resolveOrderAlias(projection: Projection, expression: Expression): Expression {
        if (expression is ColumnExpression && expression.qualifier == null && projection is ExpressionProjection) {
            val aliased = projection.items.firstOrNull { it.alias?.equals(expression.name, ignoreCase = true) == true }
            if (aliased != null) return aliased.expression
        }
        return expression
    }

    /** One joined table's schema plus the offset of its first column within a combined `List<Value>` row. */
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

    /**
     * The candidate rows a query's FIRST source starts from: an indexed lookup narrowed by WHERE
     * via [candidateRows] when there is no JOIN and the source is a real disk table (preserving the
     * same pushdown a plain single-table SELECT already gets), or a full scan otherwise - mirroring
     * [kestrel.engine.SelectRunner]'s own choice to fall back to a full scan for the driving table
     * as soon as more than one source is involved, since WHERE may combine columns from sources
     * that are not open yet at this point in the plan. A CTE-backed source has no index to push
     * down into regardless of JOIN, so it always scans its already-materialized rows; either way
     * [statement.where] is re-applied in full immediately after by the caller, so skipping pushdown
     * here only costs performance, never correctness.
     */
    private fun initialRows(statement: SelectStatement, txn: Long, item: FromItem, fromSource: DiskFromSource): List<List<Value>> =
        if (statement.joins.isEmpty() && fromSource is DiskTableSource) {
            candidateRows(fromSource.table, txn, statement.where, (item as TableSource).alias).map { it.second.values }
        } else {
            fromSource.scan(txn)
        }

    /**
     * Nested-loop join: for every row already combined from earlier sources, scans the whole of
     * [rightRows] evaluating [JoinClause.on] against each pairing. A LEFT/FULL JOIN row left
     * unmatched still contributes one row, padded with NULLs for [rightSource]'s columns; a RIGHT/
     * FULL JOIN additionally emits every [rightRows] entry that never matched any [left] row, padded
     * with NULLs for everything [rightSource.offset] counts as coming before it. Deliberately no
     * hash-join optimization for an equality condition (unlike [kestrel.engine.SelectRunner]'s
     * `hashJoinKeys`) - a documented scope cut: this JOIN exists to be correct first, in a solo
     * session without peer review, and every disk-backed table already pays a full heap scan for
     * any predicate an index cannot serve, so nested loop is not a new order-of-magnitude cost here.
     */
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

    /**
     * Unlike every other join, a LATERAL derived table has no fixed row set: it is re-evaluated
     * once per row already accumulated on the left, correlated to that row via [lateralOuterResolver]
     * exactly like a correlated subquery in WHERE/SELECT (same [SubqueryRunner]/[Evaluator] plumbing,
     * just invoked from FROM instead) - mirrors [kestrel.engine.SelectRunner.executeLateralJoin] 1:1.
     * Hash-joining is meaningless here (there is no static right-hand row set to build buckets from),
     * so this is always a nested loop, matching how real engines execute LATERAL.
     */
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

    /**
     * Resolves a column against [sourcesSoFar] - everything to the left of a LATERAL subquery in the
     * FROM clause - and nothing else: a name that does not resolve there means the expression names a
     * source not joined yet (or the LATERAL item itself), which SQL forbids, reported with a clear
     * LATERAL-specific message rather than the generic "no such column".
     */
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

    /** [expression]'s declared `COLLATE` from whichever [sources] entry it resolves against - mirrors [resolveIndexOrNull]'s own matching, `null` on no match or an ambiguous one. */
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

    /**
     * Resolves [expression]'s columns against [sources] first, falling back to [outer] (the
     * enclosing statement's row, when this call is itself part of a correlated subquery) exactly
     * once a name cannot be found locally - mirroring [kestrel.engine.SelectRunner]'s own
     * `rowResolver`. The resulting [Evaluator] carries [subqueries] so any [ScalarSubqueryExpression]/
     * [ExistsExpression]/[InSubqueryExpression] nested inside [expression] can itself run, correlated
     * back to this very row through that same resolver.
     */
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

    /**
     * When [windowExpressions] is non-empty, [row] may carry extra trailing values appended to
     * [combined][runSelect] (one per entry, right after the real columns) - rewrites any
     * [WindowExpression] node in [expression] into a [LiteralExpression] reading straight from
     * that trailing slot before [evaluateCombinedRow] ever sees it, mirroring
     * [kestrel.engine.SelectRunner]'s own `evaluateRow`/`substituteWindowValues`.
     */
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
            // rows may carry trailing window-function values appended in runSelect (only reachable
            // here if ORDER BY names one directly, since SELECT * cannot spell an OVER clause
            // itself) - trimmed back off so the row width matches the schema's own columns.
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

    /**
     * Deliberately duplicated from [kestrel.engine.SelectRunner]'s own `containsAggregate`/
     * `requireGrouped` (same reasoning as [coerceForColumn]): this executor must stay free-standing
     * from the in-memory engine's own validation, so a change to one can never silently affect the
     * other. `else -> false` covers every [Expression] variant this executor gives no
     * aggregation-aware meaning to (a literal, a window function - rejected whenever it is actually
     * evaluated, by [Evaluator] itself - or a subquery, whose own aggregates belong to that nested
     * [SelectStatement]'s independent [runSelect] call, never to this one).
     */
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

    /** See [kestrel.engine.SelectRunner]'s `Plan.validateDistinctOn` for why both checks exist. */
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

    /** Window functions are only legal in the SELECT list and ORDER BY - never WHERE/GROUP BY/HAVING/ON. */
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

    /** Every distinct window-function call reachable from [expression] (nested inside arithmetic, CASE, ...). */
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

    /**
     * Every distinct window-function call in the SELECT list or (post alias-resolution) ORDER BY -
     * the only two clauses where one is allowed, mirroring [kestrel.engine.SelectRunner]'s own
     * `windowExpressions` property.
     */
    private fun collectWindowExpressions(projection: Projection, orderKeys: List<OrderKey>): List<WindowExpression> {
        val projectionWindows = (projection as? ExpressionProjection)?.items?.flatMap { collectWindowExpressions(it.expression) } ?: emptyList()
        val orderWindows = orderKeys.flatMap { collectWindowExpressions(it.expression) }
        return (projectionWindows + orderWindows).distinct()
    }

    /**
     * Window functions are rejected outright alongside GROUP BY/aggregates ([aggregated]) -
     * windowing over an already-grouped result is valid SQL but requires running
     * [GroupAggregation] first and then a second pass over its output rows, which this executor
     * does not implement (same scope cut as [kestrel.engine.SelectRunner]). RANK/DENSE_RANK need an
     * explicit tie-break (unlike ROW_NUMBER, which falls back to partition arrival order when OVER
     * has no ORDER BY) and nesting one window function inside another's argument/PARTITION BY/
     * ORDER BY/frame offset is invalid SQL in the first place.
     */
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

    /**
     * With no ORDER BY in OVER, the whole partition is one frame (every row gets the same value).
     * With an ORDER BY, the default SQL frame is `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT
     * ROW`: a running aggregate where rows tied on the order key (a "peer group") share one value -
     * mirroring [kestrel.engine.SelectRunner]'s own `assignAggregateWindowValues` exactly.
     */
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

    /**
     * The frame's base [frameRange], narrowed by its [FrameExclude] mode (default
     * [FrameExclude.NoOthers], which leaves the range untouched) - mirrors
     * [kestrel.engine.SelectRunner]'s own `frameMembers` exactly.
     */
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


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


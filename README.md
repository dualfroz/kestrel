# Kestrel

Kestrel is a relational SQL database written from scratch in Kotlin, with no runtime dependencies
beyond the Kotlin standard library. It has a hand-written lexer and recursive-descent parser, a
query executor with joins, grouping sets, window functions, `MERGE`, triggers and foreign keys,
and two interchangeable storage backends: an in-memory engine that persists a SQL snapshot plus a
statement log, and a disk engine built on slotted heap pages, B+tree indexes, a clock buffer pool,
a write-ahead log with ARIES-style recovery, and a strict two-phase lock manager. Both are driven
from the same command-line shell.

## Example

```
$ kestrel --disk ./db
CREATE TABLE departments (id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE);
Table departments created
CREATE TABLE employees (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  dept_id INTEGER REFERENCES departments (id) ON DELETE CASCADE,
  salary DECIMAL(10, 2) CHECK (salary > 0)
);
Table employees created
CREATE INDEX employees_dept ON employees (dept_id);
Index employees_dept created
CREATE TABLE salary_log (employee_id INTEGER, old_salary DECIMAL(10, 2), new_salary DECIMAL(10, 2));
Table salary_log created
CREATE TRIGGER log_raise AFTER UPDATE ON employees FOR EACH ROW
WHEN NEW.salary <> OLD.salary
BEGIN
  INSERT INTO salary_log VALUES (NEW.id, OLD.salary, NEW.salary);
END;
Trigger log_raise created
INSERT INTO departments VALUES (1, 'Engineering'), (2, 'Sales');
2 rows inserted
INSERT INTO employees VALUES
  (1, 'Ada', 1, 9500.00), (2, 'Linus', 1, 8700.00),
  (3, 'Grace', 2, 7200.00), (4, 'Ken', 2, 6900.00);
4 rows inserted
SELECT name, dept_id, salary,
       RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS dept_rank,
       SUM(salary) OVER (PARTITION BY dept_id) AS dept_total
FROM employees ORDER BY dept_id, dept_rank;
+-------+---------+---------+-----------+------------+
| name  | dept_id | salary  | dept_rank | dept_total |
+-------+---------+---------+-----------+------------+
| Ada   | 1       | 9500.00 | 1         | 18200.00   |
| Linus | 1       | 8700.00 | 2         | 18200.00   |
| Grace | 2       | 7200.00 | 1         | 14100.00   |
| Ken   | 2       | 6900.00 | 2         | 14100.00   |
+-------+---------+---------+-----------+------------+
4 rows
CREATE TABLE raises (employee_id INTEGER, amount DECIMAL(10, 2));
Table raises created
INSERT INTO raises VALUES (2, 500.00), (4, 300.00);
2 rows inserted
MERGE INTO employees e USING raises r ON e.id = r.employee_id
WHEN MATCHED THEN UPDATE SET salary = e.salary + r.amount;
0 rows inserted, 2 rows updated, 0 rows deleted
SELECT * FROM salary_log;
+-------------+------------+------------+
| employee_id | old_salary | new_salary |
+-------------+------------+------------+
| 2           | 8700.00    | 9200.00    |
| 4           | 6900.00    | 7200.00    |
+-------------+------------+------------+
2 rows
EXPLAIN SELECT name FROM employees WHERE dept_id = 2;
Index Scan using employees_dept on employees (dept_id = 2)
BEGIN;
Transaction started
DELETE FROM departments WHERE id = 2;
1 row deleted
SELECT COUNT(*) AS remaining FROM employees;
+-----------+
| remaining |
+-----------+
| 2         |
+-----------+
1 row
ROLLBACK;
Transaction rolled back
SELECT COUNT(*) AS remaining FROM employees;
+-----------+
| remaining |
+-----------+
| 4         |
+-----------+
1 row
.quit
```

Reopening the same directory later shows the committed state, recovered from the data file and
the write-ahead log.

## Supported SQL

Keywords and identifiers are case-insensitive. Statements end with `;` and may span several lines.
Comments use `-- ...` and `/* ... */`.

### Types and literals

- `INTEGER` (64-bit), `REAL` (double), `DECIMAL(p, s)` (exact, arbitrary precision, rounded to
  the declared scale), `TEXT`, `BOOLEAN`, `DATE`.
- Literals: integers, decimals such as `1.5`, `'text'` with `''` as the escaped quote, `TRUE`,
  `FALSE`, `NULL`. Dates are written as `CAST('2024-01-01' AS DATE)`.
- Three-valued logic for `NULL` throughout.

### Data definition

- `CREATE [TEMP | TEMPORARY] TABLE` with column constraints `PRIMARY KEY`, `NOT NULL`, `UNIQUE`,
  `DEFAULT expr`, `CHECK (expr)`, `REFERENCES t (col)`, `COLLATE name` and
  `GENERATED ALWAYS AS (expr) STORED`; table constraints `PRIMARY KEY (a, b)`, `UNIQUE (...)`,
  `CHECK (...)` and `FOREIGN KEY (...) REFERENCES t (...)`, optionally named with
  `CONSTRAINT name`.
- Foreign keys with `ON DELETE` / `ON UPDATE` actions `NO ACTION`, `RESTRICT`, `CASCADE` and
  `SET NULL`, including composite and self-referencing keys.
- `ALTER TABLE`: `ADD COLUMN`, `DROP COLUMN ... [RESTRICT | CASCADE]`, `RENAME TO`,
  `RENAME COLUMN a TO b`, `ALTER [COLUMN] c TYPE t [USING expr]`,
  `ADD CONSTRAINT name {CHECK | UNIQUE | FOREIGN KEY} ...`,
  `DROP CONSTRAINT name [RESTRICT | CASCADE]`. Renames propagate into constraints, foreign keys
  of other tables, indexes and generated columns; table renames also rewrite view definitions.
- `DROP TABLE t [RESTRICT | CASCADE]`,
  `TRUNCATE [TABLE] t1, t2 [RESTART IDENTITY | CONTINUE IDENTITY] [RESTRICT | CASCADE]`.
- `CREATE [UNIQUE] INDEX name ON t (a, b, ...)`, `DROP INDEX name`.
- `CREATE [OR REPLACE] VIEW v AS query`, `DROP VIEW v [RESTRICT | CASCADE]`.
- `CREATE SEQUENCE s [INCREMENT BY n] [MINVALUE n] [MAXVALUE n] [START WITH n] [CYCLE]`,
  `ALTER SEQUENCE` (including `RESTART [WITH n]`, `NO MINVALUE`, `NO MAXVALUE`, `NO CYCLE`),
  `DROP SEQUENCE`, and `NEXTVAL('s')` / `CURRVAL('s')` in `INSERT` and `UPDATE` expressions and
  column defaults.
- `CREATE TRIGGER name {BEFORE | AFTER} {INSERT | UPDATE | DELETE} ON t FOR EACH ROW
  [WHEN expr] BEGIN stmt; ... END`, with `NEW.col` and `OLD.col` in the condition and body.
  `DROP TRIGGER name`.

### Queries

- `SELECT [DISTINCT | DISTINCT ON (...)] ... FROM ... [WHERE] [GROUP BY] [HAVING] [ORDER BY]
  [LIMIT n] [OFFSET n]`, with `ORDER BY ... [ASC | DESC] [NULLS FIRST | NULLS LAST]`.
- Joins: `INNER`, `LEFT`, `RIGHT`, `FULL [OUTER]`, `CROSS`, and `[LEFT] JOIN LATERAL (subquery)`.
  The in-memory engine executes equality joins as hash joins.
- Derived tables in `FROM`, scalar subqueries, `EXISTS`, `IN (subquery)`, including correlated
  subqueries.
- `UNION`, `INTERSECT`, `EXCEPT`, each with optional `ALL`.
- `WITH [RECURSIVE] name (cols) AS (...)` common table expressions.
- `GROUP BY ROLLUP (...)`, `CUBE (...)`, `GROUPING SETS (...)` and the `GROUPING()` function.
- Aggregates `COUNT(*)`, `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, `STRING_AGG(expr, sep ORDER BY ...)`,
  each with optional `DISTINCT` and `FILTER (WHERE ...)`.
- Window functions `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `NTILE`, `LAG` and `LEAD` (with offset and
  default), `FIRST_VALUE`, `LAST_VALUE`, and every aggregate except `STRING_AGG` used with
  `OVER (PARTITION BY ... ORDER BY ... frame)`. Frames are `ROWS`, `RANGE` or `GROUPS`
  `BETWEEN ... AND ...` with `UNBOUNDED PRECEDING`, `n PRECEDING`, `CURRENT ROW`,
  `n FOLLOWING`, `UNBOUNDED FOLLOWING`, and `EXCLUDE NO OTHERS | CURRENT ROW | GROUP | TIES`.
- `EXPLAIN query` prints the plan: sequential or index scans, hash or nested-loop joins,
  aggregation, sort, distinct, limit and offset.

### Data modification

- `INSERT INTO t [(cols)] VALUES (...), (...)` with `DEFAULT` in value lists.
- `INSERT ... ON CONFLICT (cols) DO NOTHING` and
  `ON CONFLICT (cols) DO UPDATE SET ... [WHERE ...]`, with `excluded.col` for the proposed row.
- `UPDATE t SET ... [WHERE ...]`, `DELETE FROM t [WHERE ...]`.
- `MERGE INTO target [alias] USING source ON cond` with any number of
  `WHEN MATCHED [AND cond] THEN UPDATE SET ... | DELETE` and
  `WHEN NOT MATCHED [AND cond] THEN INSERT [(cols)] VALUES (...)` clauses; the source may be a
  table or a subquery.
- `RETURNING` on `INSERT`, `UPDATE`, `DELETE` and `MERGE`; `MERGE_ACTION()` reports which
  action produced each returned row.

### Transactions and prepared statements

- `BEGIN [TRANSACTION]`, `COMMIT [TRANSACTION]`, `ROLLBACK [TRANSACTION]`, `SAVEPOINT name`, `RELEASE [SAVEPOINT] name`,
  `ROLLBACK TO [SAVEPOINT] name`.
- `PREPARE name AS statement` with positional parameters `$1`, `$2`, ...,
  `EXECUTE name (args)`, `DEALLOCATE [PREPARE] name | ALL`.

### Expressions and functions

- Operators: `+ - * /`, `= <> != < <= > >=`, `AND`, `OR`, `NOT`, `IS [NOT] NULL`,
  `[NOT] IN (...)`, `[NOT] BETWEEN`, `[NOT] LIKE` with `%` and `_`, `CASE` (simple and searched),
  `CAST(expr AS type)`, `expr COLLATE {BINARY | NOCASE | RTRIM}`. `DATE - DATE` gives a day count.
- Numeric: `ABS`, `CEIL`, `CEILING`, `FLOOR`, `ROUND`, `TRUNC`, `MOD`, `POWER`, `SIGN`.
- Text: `LENGTH`, `UPPER`, `LOWER`, `TRIM`, `LTRIM`, `RTRIM`, `SUBSTR`, `SUBSTRING`, `LEFT`,
  `RIGHT`, `LPAD`, `RPAD`, `REPEAT`, `REPLACE`, `REVERSE`, `INSTR`, `CONCAT`.
- Date: `CURRENT_DATE`, `YEAR`, `MONTH`, `DAY`, `DAYOFWEEK`, `DATE_ADD`, `DATE_SUB`, `DATEDIFF`.
- Other: `COALESCE`, `IFNULL`, `NULLIF`, `GREATEST`, `LEAST`, `TYPEOF`.

Column collations (`COLLATE` on a column) apply to comparisons, `ORDER BY`, `GROUP BY`,
`DISTINCT`, unique constraints and indexes.

## Storage engines

**In-memory** (default, or `--data DIR` to persist). Tables live in memory, with sorted indexes
used for equality, prefix and range lookups. Transactions snapshot the catalog, so schema changes
can be rolled back too. With `--data`, every committed statement is appended as SQL text to
`wal.N.log` and fsynced; on exit the whole database is written to `snapshot.N.sql` and an atomic
`manifest` switch starts the next generation. Startup replays the snapshot and then the log.

**Disk** (`--disk DIR`). Data lives in `DIR/kestrel.data` and `DIR/kestrel.wal`:

- The data file is a sequence of 4 KiB pages, each stored with a CRC32C checksum, with a header
  slot and an on-disk free list for reusing freed pages.
- A clock (second-chance) buffer pool of 64 pages caches pages and writes dirty ones back on
  eviction.
- Rows are stored in heap files made of slotted pages; primary keys and secondary indexes are
  B+trees over an order-preserving key encoding, with linked leaves for range scans and
  redistribution or merging on underflow.
- Every write is logged as whole-page before and after images. A commit appends a commit record
  and fsyncs the log before returning.
- On open, recovery runs an analysis pass, redoes the updates of committed and in-flight
  transactions, then undoes in-flight ones along their `prevLsn` chain. Savepoint rollback writes
  compensation log records, so undo is safe to repeat and to crash through.
- A lock manager provides shared and exclusive locks under strict two-phase locking, with
  deadlock detection on a wait-for graph before a request blocks. Tables are the lock unit.
- The system catalog (tables, indexes, views, sequences, triggers) is stored in a directory heap
  at a fixed page, so the whole schema is rebuilt from disk on open.

## Build and run

Requires JDK 17 or newer. The Gradle wrapper downloads everything else.

```
./gradlew installDist
build/install/kestrel/bin/kestrel                 # in-memory, nothing saved
build/install/kestrel/bin/kestrel --data ./mem    # in-memory, persisted as SQL text
build/install/kestrel/bin/kestrel --disk ./db     # disk engine
build/install/kestrel/bin/kestrel --disk ./db script.sql
```

Without a script argument the shell reads statements from standard input. `--data` and `--disk`
are mutually exclusive. Shell commands:

| Command | Effect |
|---|---|
| `.tables`, `.indexes`, `.views`, `.triggers`, `.sequences` | list objects |
| `.prepared` | list prepared statements |
| `.schema <table>` | print the table's `CREATE` statements, indexes and triggers |
| `.exit`, `.quit` | leave the shell |

## Tests

```
./gradlew test
```

The suite has 2227 tests: about 1300 for the lexer, parser, SQL semantics and the shell, and about
900 for the storage layer (codecs, pages, buffer pool, heap, B+tree, WAL, recovery, locking and
the disk executor).

## Project layout

```
src/main/kotlin/kestrel/
  cli/            command-line shell, meta-commands, table output
  sql/lexer/      tokens and lexer
  sql/parser/     recursive-descent parser
  sql/ast/        statement and expression tree
  catalog/        in-memory catalog: schemas, constraints, sorted indexes, views, sequences,
                  triggers, dependency tracking and rename propagation
  engine/         in-memory executor: SELECT pipeline, expression evaluator, aggregates,
                  grouping sets, window functions, foreign key enforcement, index planner,
                  prepared statement parameters, scalar functions
  storage/        values, column types, collations, value ordering
  persistence/    SQL snapshot and statement log for the in-memory engine, AST to SQL writer
  store/page/     disk manager with checksummed pages and free list, buffer pool
  store/heap/     slotted pages and heap files
  store/btree/    B+tree
  store/codec/    row encoding, order-preserving key encoding, CRC32C
  store/wal/      write-ahead log, log records, recovery
  store/txn/      lock manager, transaction manager
  store/engine/   storage engine facade, transactional heap and index wrappers
  store/sql/      disk catalog, disk tables, disk executor and planner
src/test/kotlin/kestrel/   tests, mirroring the layout above
```

## Limitations

- The shell is the only client. Locking is implemented in the storage layer, but there is no
  network server and no way to run concurrent SQL connections.
- No `INSERT ... SELECT`, `UPDATE ... FROM`, `IF [NOT] EXISTS`, quoted identifiers, `||` or `%`
  operators, or `SELECT` without `FROM`.
- Triggers are row-level only; there are no statement-level or `INSTEAD OF` triggers.
- On the disk engine, schema changes are rejected inside an explicit transaction, and temporary
  tables do not support foreign keys or `ON CONFLICT`.
- The disk engine never writes checkpoints, so the write-ahead log grows without bound and
  recovery replays it from the beginning. Page allocation is not logged.
- Index keys of mixed numeric types (`INTEGER` against `REAL` or `DECIMAL`) are ordered by type
  before value.
- Sequence values are not transactional: `NEXTVAL` advances survive a rollback, as in PostgreSQL.
- Prepared statements and temporary tables last only until the shell exits.

## License

MIT, see [LICENSE](LICENSE).

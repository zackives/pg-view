# CLAUDE.md — pg-view (PGVIEW)

## Project Overview

PGVIEW is a prototype system for **views over property graphs**, published at SIGMOD 2024. It translates property graph view definitions (expressed as graph transformation rules) into Datalog, then rewrites Datalog queries into SQL executed against a pluggable backend store. Supports materialized and virtual views, with optional Incremental View Maintenance (IVM) via database triggers.

**Paper:** [SIGMOD 2024](https://dl.acm.org/doi/abs/10.1145/3654949)

---

## Build & Run

**Prerequisites:** Java 11 (OpenJDK), Maven, Z3 4.8.7 (installed to local Maven repo), LogicBlox 4.41.0 (optional), PostgreSQL 14.

```bash
# Compile
mvn compile

# Start interactive console
mvn exec:java@console
```

Configuration lives in `conf/graphview.conf` (backend connection parameters).

---

## Architecture

### Layer Model

```
User (graph query / view definition)
        ↓
    CommandParser / QueryParser / ViewParser / TransRuleParser
        ↓
    GraphTransServer  (central coordinator)
        ↓
    DatalogProgram  (Datalog IR + magic-set rewriting)
        ↓
    Store  (pluggable SQL/Datalog backend)
```

### Key Packages

| Package | Role |
|---|---|
| `graphtrans.parser` | Parse graph queries, view defs, transformation rules |
| `graphtrans.store` | `Store` interface + backend implementations |
| `graphtrans.catalog` | Schema/catalog for nodes, edges, views |
| `graphtrans.typechecker` | SMT-based type checking (Z3) |
| `datalog` | Datalog IR, parser, magic-set rewriter, SSR |
| `ConjunctiveQuery` | Conjunctive query representation (Atom, Predicate, Term) |

### Store Interface (`store/Store.java`)

All backends implement this interface:

```java
connect() / disconnect() / initialize()
createDatabase(name) / deleteDatabase(name) / useDatabase(name) / listDatabases()
createSchema(dbname, predicate)
addTuple(rel, terms) / importFromCSV(relName, filePath)
addTableIndex(name, cols)
createView(name, clauses, isMaterialized)   // virtual or materialized
createView(program, transRuleList)           // IVM path (materialized + triggers)
getQueryResult(clauses)
createConstructors()   // skolem/GENNEWID functions for view node ID generation
```

### Backend Implementations

| Key | Class | Notes |
|---|---|---|
| `pg` | `PostgresStore` | Primary backend. Translates Datalog → SQL via `getSqlForDatalogClause()`. IVM via PL/pgSQL triggers. |
| `duck` | `DuckDBStore` | DuckDB backend. File-per-database under `duckdb.dbdir`. Hash-based Skolem IDs. No IVM (one-shot materialization only). |
| `lb` | `LogicBloxStore` | LogicBlox 4.x (Datalog-native DBMS). Legacy. |
| `n4` | `Neo4jStore` | Embedded Neo4j 4.1.11 with Cypher translation. |
| `sd` | `SimpleDatalogStore` | Pure in-memory Datalog engine. |

Backend is selected by `StoreFactory.getStore(storeType)`.

### Datalog → SQL Translation (`PostgresStore.getSqlForDatalogClause`)

Each `DatalogClause` (head ← body) becomes a `SELECT ... FROM ... WHERE ...` query:
- Positive body atoms → `FROM` tables with `CROSS JOIN` or `INNER JOIN`
- Variable bindings across atoms → `WHERE` equality constraints
- Interpreted atoms (e.g., `a = 5`) → additional `WHERE` predicates
- Negated atoms → `LEFT JOIN ... WHERE col IS NOT NULL`
- Head atom → `SELECT` projections with column aliases `_0, _1, ...`

Multiple clauses for the same head predicate are combined with `UNION`.

### GENNEWID (Skolem IDs)

New node IDs for view nodes are generated via a PL/pgSQL function `GENNEWID_CONST(viewruleid, VARIADIC int[])` backed by a `GENNEWID_MAP` table with a `SERIAL` primary key. Created in `createConstructors()`.

### IVM (Incremental View Maintenance)

When view type is `materialized`, PostgresStore installs PostgreSQL triggers (`AFTER INSERT ON n_g/e_g`) that propagate insertions into view tables via PL/pgSQL stored procedures.

### Property Graph Schema

The base property graph is stored as two tables:
- `N_g(_0 INT, _1 VARCHAR)` — nodes: (id, label)
- `E_g(_0 INT, _1 INT, _2 INT, _3 VARCHAR)` — edges: (edge_id, src_id, dst_id, label)

View nodes/edges follow the same schema with names like `N_v0`, `E_v0`.

---

### DuckDB-specific notes

- Each logical database → `<duckdb.dbdir>/<name>.duckdb` file; DuckDB creates the file on first connect.
- Materialized views → `CREATE TABLE name AS SELECT ...` (DuckDB has no `CREATE MATERIALIZED VIEW`).
- Skolem IDs (GENNEWID) → inline `hash(...)` expression (deterministic, content-addressed); no PL/pgSQL function required.
- IVM is not supported: triggers require PL/pgSQL. A warning is printed; views are materialized once.
- `ANALYZE` calls are omitted (DuckDB manages statistics automatically).
- `importFromCSV` uses `COPY rel FROM 'path' (FORMAT CSV, HEADER TRUE)`.

## Configuration (`conf/graphview.conf`)

```ini
[postgres]
ip = 127.0.0.1
port = 5432
username = postgres
password = postgres@

[logicblox]
ip = 127.0.0.1
port = 5518

[neo4j]
embedded = true
dbdir = neo4jdata
```

---

## Experiments

Five benchmark datasets: LSQB, OAG, PROV, SOC, WORD (see `docs/workload.md`).

```bash
cd experiment
./setup.sh          # download & prepare datasets
./run.sh -i 1 -p lb -v mv -d word   # -p: platform, -v: view type, -d: dataset
```

---

## Dependencies (pom.xml)

- ANTLR4 4.8 (grammar files in `src/main/antlr4/`)
- PostgreSQL JDBC 42.6.0
- Z3 4.8.7 (local Maven install required)
- Log4j2
- Apache Commons Lang3
- Protobuf 3.8.0 (LogicBlox protocol)
- Neo4j embedded (via APOC)

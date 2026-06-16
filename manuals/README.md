# pg-view Developer Manuals

These manuals explain how the pg-view prototype represents property graphs, defines transformation views, rewrites graph queries, and experiments with similarity-aware multi-step reasoning over relational data.

The codebase comprises:

1. The original Han and Ives [SIGMOD 2024](https://dl.acm.org/doi/abs/10.1145/3654949) pg-view engine for property graph views. It stores graphs in relations such as `N_g`, `E_g`, `NP_g`, and `EP_g`, compiles view definitions into Datalog, unfolds virtual views at query time, and supports materialized and hybrid variants.

2. [Additional extensions for Similarity Edges and LLM Extensions](06_similarity_and_llm_extensions.md)
   - Similarity path syntax, UDF calls, embedding resolution, and SQL generation.
   - Where tests exercise the extension layer.

## Recommended Reading Order

1. [Guide 1: GQL and View Language Specification](01_language_specification.md)
   - Command grammar and supported GQL/Cypher-like patterns.
   - View definition syntax, `MATCH`, `CONSTRUCT`, `MAP FROM`, `SET`, `DELETE`, and `WITH DEFAULT MAP`.
   - How `ViewParser`, `TransRuleParser`, and `QueryParser` turn text into Datalog atoms.

2. [Guide 2: DBMS Connection and Schema Mapping](02_dbms_connection_and_mapping.md)
   - Store abstraction and platform codes: `lb`, `pg`, `dd`, `n4`, and `sd`.
   - Base graph relations versus mapped physical tables.
   - `schema_mapping.yaml`, `SchemaMapping`, and the current integration boundary.

3. [Guide 3: View Compilation to TGDs and Datalog](03_view_compilation_tgd_datalog.md)
   - How transformation rules correspond to TGDs with Skolemized object creation.
   - How `ViewRule` emits match, construct, delete, mapping, and default-copy rules.
   - The role of `TransRule`, `TransRuleList`, and `DatalogProgram`.

4. [Guide 4: Query Rewriting and Unfolding](04_query_rewriting_and_unfolding.md)
   - Query parsing into view-suffixed Datalog atoms.
   - SSR substitution before unfolding.
   - Positive IDB unfolding, disjunctive unfolding, UDF handling, and negated IDB handling.

5. [Guide 5: Indexing and Optimizations](05_indexing_and_optimizations.md)
   - Implemented SSR index creation and query substitution.
   - ASR as a useful design concept, with implementation status.

6. [Guide 6: Similarity Edges and LLM Extensions](06_similarity_and_llm_extensions.md)
   - Similarity path syntax, UDF calls, embedding resolution, and SQL generation.
   - Where tests exercise the extension layer.

7. [Guide 7: Type Checking, Constraints, and Well-Behaved Views](07_typechecking_and_constraints.md)
   - EGDs, schema constraints, Z3/SMT encoding, rule-overlap checking, and output compliance.

8. [Guide 8: Graph DBMS Execution, Materialization, and Maintenance](08_graph_dbms_and_materialization.md)
   - Neo4j/Cypher execution, update-in-place vs overlay strategies, SQL materialization, and IVM hooks.

9. [Guide 9: Experiments, Datasets, and Workloads](09_experiments_and_workloads.md)
   - Dataset preparation, workload JSONs, experiment command generation, platform/view-type loops, and performance logging.

10. [Guide 10: Catalog and Runtime State](10_catalog_and_runtime_state.md)
   - Runtime registries, persistent catalog relations, schema/view metadata, and lifecycle of a console session.

## Critical Source Map

| Area | Primary files |
| --- | --- |
| Command and language grammar | `src/main/antlr4/edu/upenn/cis/db/graphtrans/GraphQueryParser/GraphTransQuery.g4` |
| Console entry point | `src/main/java/edu/upenn/cis/db/graphtrans/Client.java`, `CommandExecutor.java`, `parser/CommandParser.java` |
| Query and view parsers | `parser/QueryParser.java`, `parser/ViewParser.java`, `parser/TransRuleParser.java`, `parser/ParserHelper.java` |
| Transformation rule structures | `datastructure/TransRule.java`, `datastructure/TransRuleList.java` |
| Datalog view generation | `graphdb/datalog/BaseRuleGen.java`, `graphdb/datalog/ViewRule.java` |
| Datalog rewriting | `datalog/rewriter/Rewriter.java`, `Handler.java`, `Unfolder.java`, `Helper.java` |
| SSR indexes | `datalog/SSR.java`, `datalog/SSRHelper.java`, `datalog/QueryRewriterSubstitution.java` |
| Storage backends | `store/Store.java`, `store/StoreFactory.java`, `store/postgres/PostgresStore.java`, `store/duckdb/DuckDBStore.java`, `store/logicblox/LogicBloxStore.java`, `store/neo4j/Neo4jStore.java`, `store/simpledatalog/SimpleDatalogStore.java` |
| Schema mapping and vector extensions | `catalog/SchemaMapping.java`, `schema_mapping.yaml`, `src/test/java/edu/upenn/cis/db/graphtrans/EmbeddingExtensionsTest.java` |
| Constraints and type checking | `typechecker/RuleOverlapCheck.java`, `OutputViewCheck.java`, `SMTConstraint.java`, `parser/EgdParser.java` |
| Neo4j and materialization | `graphdb/neo4j/TranslatorToCypher.java`, `QueryToCypherParser.java`, `UpdatedViewNeo4jGraph.java`, `OverlayViewNeo4jGraph.java` |
| Experiments | `experiment/run.sh`, `experiment/workload/*.json`, `experiment/datasetlib/*`, `experiment/ExpStarterSecond.java`, `docs/workload.md` |
| Catalog/runtime state | `GraphTransServer.java`, `Config.java`, `catalog/Catalog.java`, `catalog/Schema.java`, `graphdb/datalog/BaseRuleGen.java` |

## Implementation Status Notes

- The interactive CLI does not currently load `schema_mapping.yaml` by itself. Tests load it using `SchemaMapping.load("schema_mapping.yaml")` and register it with `Config.setSchemaMapping(mapping)`.
- `create ssr on <view>` is wired through `CommandParser` and `CommandExecutor.createIndex(IndexType.SSR, ...)`.
- ASR syntax exists in the view type grammar and concepts, but the user-facing ASR index command is not wired like SSR.
- The grammar includes `SIM_EDGE` syntax and UDF parsing. SQL compilation for those constructs is implemented in the mapped-table path of `PostgresStore`.

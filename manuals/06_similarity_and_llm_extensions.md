# Guide 6: Similarity Edges and LLM Extensions

This manual documents the repository's extensions for similarity-aware graph queries.

The extension layer supports top-k multi-step reasoning queries over structured attributes, multiple embedding spaces, exact joins, and semantic joins. 

- grammar support for similarity edges such as `<=>` and `<->`;
- grammar support for function calls in `WHERE`;
- Datalog atoms for similarity edges and UDF calls;
- YAML schema mapping for application tables and embedding columns;
- PostgreSQL and DuckDB SQL generation for vector predicates;
- tests that verify the generated SQL.

## 1. Why Similarity Belongs in the Query Layer

Modern agentic data-engineering workloads often decompose a natural-language task into a structured query over a local DBMS. These queries combine:

- exact joins, for example document id to paragraph id;
- structured predicates, for example `type = 'claim'`;
- vector lookup, for example a molecule embedding near a query embedding;
- vector joins, for example paragraphs semantically similar to molecule descriptions;
- top-k scoring across several signals.

Traditional vector databases are good at one-vector lookup but weak at cross-table joins and multi-signal ranking. A relational DBMS has joins and predicates, but standard vector-index support is usually not enough for M-to-N semantic joins. A semantic-join index would materialize close vector pairs so multi-hop similarity paths can be executed as relational joins.

In pg-view, the current extension layer focuses on representing these predicates in the graph query language and compiling them to SQL. It is the front-end and SQL-lowering foundation on which a semantic-join index could be added.

## 2. Grammar Support

The relevant grammar rules are in `GraphTransQuery.g4`:

```antlr
SIM_OP : '<=>' | '<->' | '<+>';

edge_term_body
  : var? (':' (labelRegEx | star))? properties?
  | var? ':' SIM_OP properties?
  ;

funcCall
  : ID ('.' ID)* '(' funcArg (',' funcArg)* ')';

where_condition
  : lop operator rop;
```

Example similarity edge:

```gql
MATCH (e:Entity)-[r:<=> {threshold: 0.8}]->(t:Tag)
RETURN (e), (t);
```

Example UDF predicate:

```gql
MATCH (e:Entity)
WHERE cosine_similarity(e.detail, 'vaccine side effects', 'gemini') > 0.85
RETURN (e);
```

`FLOAT` support allows thresholds such as `0.8` and `0.85`.

## 3. QueryParser Translation

`QueryParser.visitMatch_clause` detects similarity edges when `edgeCtx.SIM_OP() != null`.

Instead of producing:

```text
E(r, e, t, "SomeLabel")
```

it produces:

```text
SIM_EDGE(r, e, t, "<=>", 0.8)
```

The predicate signature is initialized in `Config.initialize`:

```java
predSimEdge = new Predicate("SIM_EDGE");
predSimEdge.addArg("edge_var", Type.String);
predSimEdge.addArg("from", Type.String);
predSimEdge.addArg("to", Type.String);
predSimEdge.addArg("op", Type.String);
predSimEdge.addArg("threshold", Type.String);
```

`QueryParser.parseFuncCall` handles UDF-style calls. It creates a generated return variable such as `f_0`, emits a Datalog atom with all input arguments plus the return variable, and returns `f_0` to the surrounding interpreted comparison.

For:

```gql
WHERE cosine_similarity(e.detail, 'vaccine side effects', 'gemini') > 0.85
```

the parser emits the equivalent of:

```text
NP(e, "detail", e_detail_val),
cosine_similarity(e_detail_val, 'vaccine side effects', 'gemini', f_0),
f_0 > 0.85
```

Property extraction is shared with normal graph predicates through `ParserHelper`.

## 4. Physical Embedding Resolution

The mapped-table compiler needs to know which physical column stores each embedding. This is handled by `SchemaMapping`.

The example `schema_mapping.yaml` contains:

```yaml
nodes:
  Entity:
    table: entities
    primary_key: entity_id
    default_embedding: gemini_detail
    properties:
      detail:
        column: entity_detail
        embedding:
          column: entity_embed
          dimension: 1536
          model: text-embedding-3-small
      gemini_detail:
        column: entity_detail
        embedding:
          column: gem_embed
          dimension: 3072
          model: gemini-embedding

  Tag:
    table: entity_tags
    primary_key: entity_id
    default_embedding: gemini_value
    properties:
      gemini_value:
        column: tag_value
        embedding:
          column: gem_embed
          dimension: 3072
          model: gemini-embedding

path_query_overrides:
  - source: Entity
    target: Tag
    source_embedding: detail
    target_embedding: gemini_value
```

`SchemaMapping.getEmbedding` resolves an embedding in this order:

1. Match an explicit model parameter, such as `'gemini'`.
2. Use the property referenced in the query, such as `detail`.
3. Use the node's `default_embedding`.
4. Fall back to any property with an embedding block.

`SchemaMapping.getPathQueryOverride` handles source/target-specific overrides for similarity edges. This is the repository's current mechanism for choosing different embedding spaces along different reasoning hops.

## 5. SQL Compilation

Mapped SQL generation lives in `PostgresStore.getSqlForDatalogClauseWithMapping`.

### 5.1 Similarity Edges

For a `SIM_EDGE` atom, the compiler:

1. Looks up source and target node labels from surrounding `N` atoms.
2. Resolves source and target embedding columns, using `path_query_overrides` if available.
3. Emits a dialect-specific predicate.

PostgreSQL:

```sql
1 - (e.entity_embed <=> t.gem_embed) > 0.8
```

DuckDB:

```sql
array_cosine_similarity(e.entity_embed, t.gem_embed) > 0.8
```

For non-`<=>` operators, the compiler currently uses a distance comparison:

PostgreSQL:

```sql
e.entity_embed <-> t.gem_embed < 0.8
```

DuckDB:

```sql
array_distance(e.entity_embed, t.gem_embed) < 0.8
```

### 5.2 UDF Similarity

For `cosine_similarity` and `l2_distance`, the compiler recognizes the function atom and maps it to a SQL expression. It assumes the second argument is a query literal and calls:

```sql
get_embedding(<literal>)
```

PostgreSQL cosine example:

```sql
1 - (e.gem_embed <=> get_embedding('vaccine side effects'))
```

DuckDB cosine example:

```sql
array_cosine_similarity(e.gem_embed, get_embedding('vaccine side effects'))
```

The comparison against the generated return variable is then handled as an interpreted atom:

```sql
... > 0.85
```

The database must provide `get_embedding`. pg-view only generates the SQL call.

## 6. Multi-Step Reasoning Walkthrough

The test `EmbeddingExtensionsTest.testMultiStepReasoningWalkthrough` demonstrates the intended query style:

```gql
MATCH
  (c:Entity {type: 'claim'})-[:PARENT_OF]->(doc:Entity),
  (doc)-[citation:LINKED_TO {type: 'citation'}]->(citing:Entity),
  (citing)-[:HAS_TAG]->(t:Tag)
WHERE
  cosine_similarity(c.detail, 'vaccine side effects', 'gemini') > 0.85
  AND t.name = 'field'
  AND t.value = 'Immunology'
RETURN (doc), (citing)
```

The compiler combines:

- inline node property predicates: `c.type = 'claim'`;
- inline edge property predicates: `citation.type = 'citation'`;
- self-join edge mapping: `doc.entity_parent = c.entity_id`;
- edge-table joins: `citation.from_id = doc.entity_id` and `citation.to_id = citing.entity_id`;
- tag joins: `entity_tags` as both edge and target node table;
- embedding similarity: `c.gem_embed <=> get_embedding(...)`;
- structured filters on tag name and value.

This is a chain of joins and semantic filters over local structured and unstructured-derived data.

## 7. Tests

`src/test/java/edu/upenn/cis/db/graphtrans/EmbeddingExtensionsTest.java` verifies the extension layer without requiring live query execution:

- `testUdfAndFunctionalSimilarity` checks PostgreSQL and DuckDB SQL for `cosine_similarity`.
- `testPathSimilarity` checks similarity edge compilation and `path_query_overrides`.
- `testSelfJoinParentOf` checks inline self-join edge mapping.
- `testMultiStepReasoningWalkthrough` checks a multi-hop structured and semantic query.

The test setup is also the reference for enabling mapped compilation:

```java
Config.initialize();
SchemaMapping mapping = SchemaMapping.load("schema_mapping.yaml");
Config.setSchemaMapping(mapping);
PostgresStore store = new PostgresStore();
```

## 9. Extension Plan for Semantic-Join Indexes

A semantic-join index implementation could fit naturally into the current architecture:

1. Add semantic-index metadata to the catalog, including labels, properties, embedding columns, operator, threshold, and distance column.
2. Add a command such as `create semantic index on Entity.detail, Tag.gemini_value threshold 0.8`.
3. Generate SQL to materialize `(src_id, dst_id, distance)` pairs.
4. Add a pre-unfolding rewrite step that replaces matching `SIM_EDGE` atoms with the materialized semantic-join relation.
5. Keep direct vector SQL as fallback when no semantic-join index covers the predicate.
6. Extend the result model to carry distance variables for top-k scoring.

That design would extend pg-view's existing parser, Datalog, mapping, and store infrastructure without changing the user-facing similarity syntax.

# Guide 3: View Compilation to TGDs and Datalog

This manual explains how pg-view compiles transformation views into Datalog. Conceptually, each transformation rule is a tuple-generating dependency (TGD): if a pattern exists in the input graph, then a pattern exists in the output graph, possibly with new Skolemized ids.

The implementation is concentrated in:

- `parser/ViewParser.java`
- `parser/TransRuleParser.java`
- `datastructure/TransRule.java`
- `datastructure/TransRuleList.java`
- `graphdb/datalog/ViewRule.java`
- `datalog/DatalogProgram.java`

## 1. Logical Model

A transformation rule has the form:

```text
body(input graph atoms, predicates)
  ->
head(output graph atoms, mappings, deletions, generated ids)
```

For graph views, the body is a conjunction over:

```text
N_base(id, label)
E_base(edge, src, dst, label)
NP_base(id, key, value)
EP_base(edge, key, value)
interpreted predicates such as =, >, !=
```

The head may create output graph atoms:

```text
N_view(id, label)
E_view(edge, src, dst, label)
NP_view(id, key, value)
EP_view(edge, key, value)
```

and auxiliary relations:

```text
MATCH_view_i(...)
MAP_view(src, dst)
DMAP_view(src, dst)
N_deleted_view(id)
E_deleted_view(id)
GENNEWID_*(...)
```

Skolem functions from `SET x = SK("name", args...)` replace existential variables. The generated id is deterministic for a given function name and argument tuple, which allows multiple rule firings to agree on the same constructed object.

## 2. Running Example

```gql
CREATE virtual VIEW v ON g WITH DEFAULT MAP (
  MATCH (a:Entity)-[r:LINKED_TO]->(b:Entity)
  WHERE r.type = "sameAs"
  CONSTRUCT (m:Entity)
  SET m = SK("mergeEntity", a, b)
  MAP FROM a, b TO m
  DELETE r
);
```

The rule says:

- Match a `sameAs` edge between two `Entity` nodes.
- Construct one merged `Entity` node.
- Generate the merged id using `mergeEntity(a,b)`.
- Map both source nodes to the merged node.
- Do not copy the matched edge.
- Because `WITH DEFAULT MAP` is present, copy everything else and reconnect copied edges through `DMAP_v`.

## 3. Parser Output Structures

`ViewParser.visitCreate_view` creates a `TransRuleList`:

```text
viewName: v
baseName: g
viewType: virtual | materialized | hybrid | asr
isDefaultMap: true/false
TransRuleList: one TransRule per UNION branch
```

For each rule, `TransRuleParser` fills a `TransRule`:

| Field | Meaning |
| --- | --- |
| `patternMatch` | Datalog atoms for the rule body. |
| `patternConstruct` | Output atoms explicitly mentioned in `CONSTRUCT`. |
| `patternBefore` | Match pattern used for type checking and indexing. |
| `patternAdd` | Constructed nodes/edges not present in the input match. |
| `newNodeVars`, `newEdgeVars` | Variables that need generated ids. |
| `matchNodeVars`, `matchEdgeVars` | Existing input objects. |
| `SkolemFunctionMap` | New variable -> Skolem function name and arguments. |
| `mapFromToMap` | New output variable -> input variables it represents. |
| `nodeVarsToDelete`, `edgeVarsToDelete` | Matched variables explicitly deleted. |

`TransRule.computePatterns()` derives affected and after-pattern information. This is especially relevant for SSR indexing and type checking.

## 4. Datalog Generation Entry Point

`CommandExecutor.populateDatalogProgramForView` calls:

```java
ViewRule.addViewRuleToProgram(program, transRuleList, true, true);
store.createConstructors();
populateIndexSet(program, viewName);
populateEDBs(program, transRuleList, viewName);
```

The generated rules are added to the global `GraphTransServer.getProgram()` Datalog program. The active `Store` later installs these rules as LogicBlox rules, PostgreSQL views/materialized views, DuckDB views/tables, or simple Datalog rules.

## 5. Match Rules

`ViewRule.addMatchRule` creates one match relation per transformation rule:

```text
MATCH_v_0(vars...) <- N_g(...), E_g(...), NP_g(...), interpreted predicates.
```

Key implementation details:

- Relation names in the body are suffixed with the base graph/view name, for example `N_g`, `E_g`, or `N_parentView`.
- Negated match atoms are skipped when collecting match head variables.
- Regex/repeated labels can create auxiliary recursive relations named `REC_<id>`.
- The match relation is indexed on each head term in the `DatalogProgram`.

The match relation decouples expensive pattern evaluation from downstream construction rules. For materialized and hybrid views, it can also be treated as an EDB/materialized intermediate.

## 6. Construct and Skolem Rules

`ViewRule.addMapRules` handles constructed output atoms and generated ids.

For each output atom in `patternConstruct`, it emits a rule like:

```text
N_v(m, m_label) <-
  m_label = "Entity",
  MATCH_v_0(...),
  GENNEWID_MAP_v_mergeEntity(a, b, m).
```

or:

```text
E_v(e, src, dst, e_label) <-
  e_label = "SomeEdge",
  MATCH_v_0(...),
  GENNEWID_MAP_v_edgeFunc(args..., e).
```

For each Skolem function, it also emits constructor relations:

```text
GENNEWID_CONST_v_mergeEntity(a, b, m),
GENNEWID_v(m)
  <- MATCH_v_0(...).
```

These relations make generated ids visible to both view creation and query unfolding. The actual id generation is backend-specific:

- LogicBlox receives constructor declarations through `DatalogProgram.addConstructorForLB`.
- PostgreSQL can compile `GENNEWID_MAP_*` to a deterministic SQL expression in `PostgresStore.handleHeadAtom`.

## 7. Mapping Rules

When a rule contains:

```gql
MAP FROM a, b TO m
```

`ViewRule.addMapRules` emits `MAP_v(a, m)` and `MAP_v(b, m)` rules guarded by `MATCH_v_0` and the generated-id relation for `m`.

The `MAP_v` relation means "the input object on the left is explicitly represented by the output object on the right." It is not the final copy map. The final copy map is `DMAP_v`, generated by default-map rules.

## 8. Delete Rules

`ViewRule.addAddRemoveRules` emits deletion markers:

```text
N_deleted_v(a) <- MATCH_v_0(...).
E_deleted_v(r) <- MATCH_v_0(...).
```

These relations do not physically delete from the base graph. They only prevent default-copy rules from carrying the object into the view.

## 9. Default Mapping Rules

If the view definition includes `WITH DEFAULT MAP`, `ViewRule.addViewRules` emits the second stratum of rules:

```text
DMAP_v(id, id), N_v(id, label) <-
  N_g(id, label),
  not MAP_v(id, _),
  not N_deleted_v(id).

DMAP_v(src, dst) <-
  MAP_v(src, dst),
  not N_deleted_v(src).

E_v(id, src2, dst2, label) <-
  E_g(id, src, dst, label),
  DMAP_v(src, src2),
  DMAP_v(dst, dst2),
  not E_deleted_v(id).
```

This is the core logic behind localized transformation views. Nodes that are neither mapped nor deleted survive with identity mapping. Edges survive if not deleted, but their endpoints are reconnected through `DMAP`, so edges incident to merged nodes point to the merged object.

The rules are stratified because default copying uses negation over `MAP_v`, `N_deleted_v`, and `E_deleted_v`, all of which are produced by earlier rules.

## 10. View Types

The grammar supports four view types:

| Type | Implementation behavior |
| --- | --- |
| `virtual` | Rules are available for query unfolding. They are not necessarily materialized unless the store chooses to create intermediate views. |
| `materialized` | `populateEDBs` marks match, mapping, add/delete, and output relations as EDBs/materialized relations. Stores create materialized views/tables where supported. |
| `hybrid` | Match and mapping relations can be materialized while final output can still be unfolded. |
| `asr` | The compiler creates match rules only when selectively requested; user-facing ASR index creation is not wired like SSR. |

The backend decides how `createView(DatalogProgram, TransRuleList)` realizes rules:

- `PostgresStore` groups clauses by head relation and emits `CREATE VIEW` or `CREATE MATERIALIZED VIEW`.
- `DuckDBStore` emits `CREATE VIEW` or `CREATE TABLE AS`.
- `LogicBloxStore` creates LogicBlox blocks/rules.
- `SimpleDatalogStore` stores rules in the simple engine.

## 11. Type Checking Hooks

`CommandExecutor.createView` calls `checkRuleValidity` unless the view is loaded from persisted metadata. That invokes:

- `typechecker/RuleOverlapCheck.java`
- `typechecker/OutputViewCheck.java`

The type checker uses `TransRule.patternBefore`, `patternAffected`, and `patternAfter` plus schema and EGD metadata. The manuals focus on compilation, but developers changing rule semantics should inspect these classes before changing parser or `TransRule.computePatterns` behavior.

## 12. Relation Naming Conventions

`Config.java` centralizes relation names:

```text
N, E, NP, EP
MATCH
MAP
DMAP
GENNEWID
_g
```

The compiler constructs relation names as:

```text
N_<view>
E_<view>
NP_<view>
EP_<view>
MATCH_<view>_<ruleIndex>
MAP_<view>
DMAP_<view>
N_deleted_<view>
E_deleted_<view>
GENNEWID_MAP_<view>_<skolemName>
GENNEWID_CONST_<view>_<skolemName>
```

When debugging a query or view, printing the `DatalogProgram` through the console `program;` command is often the fastest way to understand what was generated.

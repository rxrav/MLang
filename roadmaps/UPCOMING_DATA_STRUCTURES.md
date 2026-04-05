# Upcoming: Array and Map Data Structures

Planned implementation of native array and map literals backed by `java.util.ArrayList`
and `java.util.HashMap`.

---

## Syntax Design

**Array** (backed by `ArrayList`):
```m
let nums = [1, 2, 3]        // literal
let x = nums[0]             // read
nums[0] = 99                // write
```

**Map** (backed by `HashMap`):
```m
let ages = {"alice": 30, "bob": 25}   // literal
let x = ages["alice"]                 // read
ages["carol"] = 40                    // write
```

---

## Work Required Per Layer

### 1. Lexer — minimal
- Add `LBRACKET` (`[`) and `RBRACKET` (`]`) tokens — they don't exist yet
- `:` (colon) already exists, nothing new needed for map

### 2. AST — 4 new nodes

| Node | Represents |
|---|---|
| `ArrayLiteralNode(List<Node> elements)` | `[1, 2, 3]` |
| `MapLiteralNode(List<Node> keys, List<Node> values)` | `{"a": 1}` |
| `IndexAccessNode(Node target, Node index)` | `arr[0]`, `map["key"]` |
| `IndexAssignNode(String name, Node index, Node value)` | `arr[0] = 5` |

### 3. Parser — 4 touch points
- **`parsePrimary()`**: `[` → parse array literal (comma-separated until `]`)
- **`parsePrimary()`**: map disambiguation — when `{` is followed by a string/ident then `:`, treat as map literal, not a block. This is a 2-token lookahead.
- **`parsePostfix()`**: `[` after an expression → parse index access
- **`parseAssignOrExpr()`**: `IDENT [ expr ] = expr` → `IndexAssignNode`

> **Disambiguation note**: `{}` stays as an empty block. An empty map literal needs a
> decision — simplest is to require at least one entry or fall back to Java interop via
> `staticCall`.

### 4. Semantic Analyzer — type inference
- `[...]` infers type `List`
- `{"k": v}` infers type `Map`
- Index access result infers `Any` (values are `Object` at runtime — generic erasure)
- No new scope rules needed

### 5. TypeMap
- Add `List` → `Ljava/util/ArrayList;`
- Add `Map`  → `Ljava/util/HashMap;`

### 6. Codegen — the main work

**Array literal** → `new ArrayList()` + loop of `.add(element)`:
- Primitives must be **boxed** before `.add(Object)` — requires a new `boxIfPrimitive()`
  helper (the inverse of the existing `unboxIfPrimitive()`).

**Map literal** → `new HashMap()` + loop of `.put(key, value)`:
- Both key and value must be boxed if primitive.

**Index read** (`arr[i]` / `map["k"]`):

| Inferred target type | Emitted bytecode |
|---|---|
| `List` | `ArrayList.get(int) → Object` |
| `Map`  | `HashMap.get(Object) → Object` |
| `Any`  | Fall back to `dynCall` (reflection — already exists) |

**Index write** (`arr[i] = v`):

| Inferred target type | Emitted bytecode |
|---|---|
| `List` | `ArrayList.set(int, Object) → Object` (box value, discard return) |
| `Map`  | `HashMap.put(Object, Object) → Object` (box value, discard return) |

**For-loop compatibility** — `for x in list` already works because `ArrayList` implements
`Iterable`, and that codepath already emits `iterator()` / `hasNext()` / `next()`. Free.

---

## Key Decisions Upfront

1. **Boxing helper** is the critical new piece of infrastructure — every `.add()` and
   `.put()` call needs primitives wrapped in their boxed equivalents (`Integer`, `Long`, etc.).
2. **Map literal disambiguation** is the trickiest parser change — a 2-token peek at `{`
   followed by a string/ident and then `:`.
3. **Index type is always `Any`** coming out — no generics needed, same as Java raw types.
4. **`arr.size()`, `arr.contains()`, `map.containsKey()`** etc. all work for free via the
   existing `dynCall` reflection path once the object exists.

---

## Estimated Scope

| Layer | Approx. size |
|---|---|
| Lexer | +2 tokens, ~10 lines |
| AST nodes | 4 records, ~20 lines |
| Parser | ~60 lines |
| Semantic analyzer | ~20 lines |
| TypeMap | +2 entries, ~5 lines |
| Codegen | ~150 lines (boxing helper + 4 emit methods) |
| Tests | ~10–12 new tests |

Comparable in complexity to the exception-handling step — well-scoped and incremental.

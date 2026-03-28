# The Crucible: Worldbuilding Debugger Spec (v2.0)

---

## 1. Intent & Scope

**Project Goal:** A computational framework for stress-testing narrative settings — a logic compiler that catches worldbuilding errors before they become plot holes.

**Core Thesis:** Most worldbuilding fails not because the author lacks imagination, but because they work top-down (aesthetics first) and never validate the systemic implications of their Level II decisions. A "cool rule" introduced at Level II is a *promise* to the reader. The Crucible holds the author to that promise.

**Objective:** Transition a world from **Level I–III** (Aesthetic / Surface) to **Level V–VII** (Generative / Deep Systemic) by identifying logical "Null Pointers" and "Dependency Hell" in world logic.

---

## 2. The Level Hierarchy (Explicit Definition)

The seven levels form a **dependency lattice**: each level presupposes all levels below it. A world that is coherent at Level VII is valid. Most published fantasy operates at Level III. Most *memorable* fantasy operates at Level V or above.

| Level | Name | What it Defines | Failure Mode |
|---|---|---|---|
| **I** | **Physical Substrate** | Geography, climate, raw materials, carrying capacity | Terrain that has no consequence on culture or logistics |
| **II** | **Power Hook** | Magic, technology, divine mechanics — the *differentiator* | An ability with no cost, no scarcity, no systemic implication |
| **III** | **Surface Culture** | Aesthetic signals: dress, ritual, language, architecture | Costume without cause; cultures that don't emerge from I and II |
| **IV** | **Economic Layer** | Trade, resource extraction, labor, surplus and deficit | Thriving cities with no visible means of feeding themselves |
| **V** | **Institutional Memory** | History, ideology, taboo, law — culture *over time* | Institutions that have no founding crisis; laws with no origin conflict |
| **VI** | **Infrastructure** | Logistics, communication, banking, enforcement, sanitation | Empires that couldn't functionally communicate or move armies |
| **VII** | **Systemic Emergence** | The live simulation: institutions competing, adapting, breaking | A "stable" world that has no active tension or pending failure state |

> **Design Principle:** When a world is described as "ancient" or "in balance," that is almost always a failure to reach Level VII. Real systems are never in balance — they are in *managed disequilibrium.*

---

## 3. Theoretical Framework: The Algebra of Worldbuilding

We treat a setting as a **Closed-Loop System**. Every entity has a type. Every interaction between entities is a *transformation* — a morphism from one state to another. The system's job is to verify that these morphisms are total (no null outputs) and consistent (no contradictory outputs).

### 3.1 The Core Types

The system operates on an ontology of four primary types. These are the *base types* of the world's type system. Everything else is a parameterized or composite type derived from these.

| Type | Definition | Level Mapping | Analogy |
|---|---|---|---|
| `PhysicalEntity` | The hardware: Geography, climate, raw materials, carrying capacity | Level I | The substrate; cannot be argued with |
| `PowerSource` | The energy: Magic, technology, divine favor, organized labor | Level II / IV | The differentiator; must have efficiency and scarcity defined |
| `Infrastructure` | The OS: Logistics, communication, banking, law, enforcement | Level VI | The hidden load-bearing wall |
| `Agent` | The users: Individuals, cultures, institutions, ideologies | Level III / V | The *consumers* of all other types |

### 3.2 Derived Types

| Derived Type | Constructor | Notes |
|---|---|---|
| `Conflict` | `Agent × Agent × Resource` | A conflict requires two agents and a disputed resource |
| `Culture` | `(Conflict × Resolution) ^ Time` | Culture is the sediment of historical conflicts |
| `Institution` | `Agent × PowerSource × Infrastructure` | An institution is an agent that has successfully captured a power source and built infrastructure around it |
| `Ideology` | `Institution × Time × Threat` | Ideology is how institutions justify their resource capture to non-participants |

### 3.3 The Transformation Rules (Algebras)

Algebras define legal transformations between types. An algebra is **valid** if its output type is reachable given its inputs. The audit clause is an *invariant check* run after the transformation.

```yaml
algebras:

  sustenance:
    input:  "Agent + PhysicalEntity:Food + Infrastructure:Logistics"
    output: "Stability"
    audit:
      - "IF Stability > 0 AND (Food IS NULL OR Logistics IS NULL) THEN flag 'Malthusian Paradox: population exists with no visible food chain.'"
      - "IF Food.yield < Agent.population_density THEN flag 'Subsistence Ceiling: culture cannot sustain its described scale.'"
      - "IF Logistics IS NULL AND PhysicalEntity.size > LOCAL THEN flag 'Distribution Failure: no mechanism to move surplus to deficit regions.'"

  authority:
    input:  "Agent:Institution + PowerSource + Infrastructure:Comm"
    output: "Level_VI_Control"
    audit:
      - "Check if Comm.latency is compatible with PhysicalEntity.territory_size."
      - "IF territory_size > Comm.effective_range THEN flag 'Imperial Overreach: control claimed over territory that cannot be administered.'"
      - "IF PowerSource.scarcity == extreme AND Institution.count > 1 THEN flag 'Monopoly Pressure: multiple institutions cannot coexist stably over a scarce power source without conflict algebra defined.'"

  tradition:
    input:  "Conflict:Historical + Resolution"
    output: "Culture"
    audit:
      - "Generate at least one taboo derived from a past Resource shortage."
      - "Generate at least one calendar event (holiday, fast, festival) derived from a past Military or Economic conflict."
      - "IF Culture.age > 200_years AND tradition.count == 0 THEN flag 'Amnesiac Culture: a culture this old has no traceable history. This is a worldbuilding null.'"

  power_diffusion:
    input:  "PowerSource + Time + Agent:Population"
    output: "Institutional_Stratification"
    audit:
      - "IF PowerSource.access == unrestricted THEN flag 'Leveling Effect: an unrestricted power source should collapse social hierarchy over time. Is that intended?'"
      - "IF PowerSource.access == gated AND Infrastructure:Education IS NULL THEN flag 'Dependency: who controls the gate? No education infrastructure means no documented succession of access.'"
      - "Generate the class that controls access to PowerSource. If no class can be named, flag 'Power Vacuum.'"

  geographic_pressure:
    input:  "PhysicalEntity:Geography + Agent:Culture"
    output: "Cultural_Adaptation"
    audit:
      - "IF Geography.properties CONTAINS [arid OR isolated] AND Agent.diet IS NULL THEN flag 'Subsistence Null: what does this culture eat?'"
      - "IF Geography.properties CONTAINS mountainous AND Infrastructure:Roads IS NULL THEN flag 'Isolation Trap: mountains without roads means no trade, no army movement, no tax collection — is that the intent?'"
      - "Generate at least one cultural trait (religious, dietary, linguistic) that is a *direct* adaptation to a Geography property."
```

---

## 4. Formal Schema (YAML Manifest)

The `world_manifest` is where the user inputs their Level I–III data. The system treats this as the *source* and all higher levels as *compiled output* to be validated or generated.

```yaml
schema_id: crucible_v1
metadata:
  world_name: "[REQUIRED]"
  compiler_target: LLM_Logic_Auditor
  logic_mode: Pessimistic_Skepticism   # Never assume stability. Assume pressure.
  audit_depth: VII                     # How deep should the auditor descend?

# --- PHYSICAL SUBSTRATE (Level I) ---
world_manifest:

  geography:
    id: G_01
    type: PhysicalEntity
    properties: [arid, mountainous, isolated]
    carrying_capacity: low             # REQUIRED. Drives sustenance algebra.
    water_sources: [G_01_spring_network]
    notable_resources: [mineral_salt, obsidian, rare_herbs]

  # --- POWER SOURCE (Level II) ---
  mechanics:
    id: P_01
    type: PowerSource
    hook: "Memory-based Alchemy: consuming a memory as fuel destroys it permanently."
    efficiency: high
    scarcity: extreme
    access: gated                      # Who controls access? Required if scarcity != none.
    access_gate: "[TO BE GENERATED]"   # The Crucible will propose an institution here.
    side_effects:                      # Required. No power source is free.
      - "Practitioners suffer progressive identity erosion proportional to use."
      - "High-use practitioners become effective but psychologically unmoored."
    reproducibility: non_transferable  # Memories cannot be stockpiled and sold. Drives scarcity.

  # --- SURFACE CULTURE (Level III) ---
  aesthetic:
    id: A_01
    type: Agent_Visuals
    traits: [monastic_robes, ritual_scarring, silent_communication]
    # NOTE: Crucible will audit each trait for a Level I or II cause.
    # "Silent communication" in a culture with memory-alchemy has structural implications.

# --- INSTITUTIONS (Level VI, partially defined) ---
# The user may stub these; the Pressure Test will flesh them out.
institutions:
  - id: I_01
    name: "The Memory Bank"
    goal: Profit
    resource_claimed: P_01            # Claims gated access to the PowerSource.
    stub: true                        # Mark true if not yet fully designed.

  - id: I_02
    name: "The Silent Cult"
    goal: Power
    resource_claimed: A_01:ritual_scarring  # Controls the aesthetic/identity layer.
    stub: true

  - id: I_03
    name: "The Mountain Traders"
    goal: Survival
    resource_claimed: G_01:mineral_salt
    stub: true
```

---

## 5. Invariants: The Linter Rules

These are global constraints. A world is **Valid at Level VII** only if all invariants pass. Invariant failures are not suggestions — they are compile errors.

```yaml
invariants:

  # --- STRUCTURAL INVARIANTS ---
  - id: INV_01
    name: "Rule of Consequence"
    rule: "For every Level II PowerSource hook, there must be a defined Level VI 'Service Cost' — an institution, infrastructure layer, or social structure that exists *because of* the power source."
    severity: ERROR

  - id: INV_02
    name: "Resource Chain"
    rule: "No Agent:Settlement can exist without a resolvable Level I resource chain for [water, caloric_food]. Geographic isolation does not excuse this — it makes it more expensive, not absent."
    severity: ERROR

  - id: INV_03
    name: "Geographic Flow"
    rule: "Rivers flow downhill. Mountain passes are chokepoints. Trade follows the path of least resistance. Any violation of these requires an explicit PowerSource justification."
    severity: WARNING

  - id: INV_04
    name: "Communication Ceiling"
    rule: "An institution's effective control radius cannot exceed its communication infrastructure. Pre-industrial: ~30 days travel. Magic-assisted: must define the mechanism and its cost."
    severity: ERROR

  # --- POWER INVARIANTS ---
  - id: INV_05
    name: "Scarcity Pressure"
    rule: "Any PowerSource with scarcity >= high MUST have at least one Institution in active conflict over its control, OR a documented historical conflict that resolved the competition (temporarily)."
    severity: ERROR

  - id: INV_06
    name: "Access Gate"
    rule: "Any PowerSource with access == gated MUST name the controlling entity and define what it extracts in exchange for access (coin, labor, loyalty, memory, etc.)."
    severity: ERROR

  # --- CULTURAL INVARIANTS ---
  - id: INV_07
    name: "Amnesiac Culture"
    rule: "Any Agent:Culture older than ~100 years must have at least one traceable historical conflict that shaped a current law, taboo, or ritual. If history is blank, flag."
    severity: WARNING

  - id: INV_08
    name: "Costume Without Cause"
    rule: "Every aesthetic trait in Agent_Visuals must have a traceable cause in Level I (geography/climate) or Level II (power source mechanics). Aesthetics are never neutral."
    severity: WARNING

  - id: INV_09
    name: "Silent Power"
    rule: "IF a culture has suppressed or formalized communication (silence, ritual language, restricted literacy), there must be an infrastructure explanation for how governance and trade operate without it."
    severity: ERROR
```

---

## 6. Feature Set

### 6.1 The Inconsistency Linter (Level IV audit)

The LLM scans the `world_manifest` against every algebra and every invariant. Output is structured as a **compiler diagnostic**.

**Example output for the sample manifest:**

```
[ERROR] INV_06 (Access Gate): P_01 has access=gated but access_gate is '[TO BE GENERATED]'.
  → Who controls the Memory Bank's monopoly on alchemy access? This is your world's most
    important power struggle and it is currently a null pointer.

[ERROR] INV_09 (Silent Power): A_01 declares silent_communication as a cultural trait.
  → sustenance.audit: How does I_03 (Mountain Traders) negotiate contracts in silence?
  → authority.audit: How does I_01 (Memory Bank) enforce debt collection?
  → Proposed resolution: Silent communication may imply a symbolic/gestural formal language
    used for high-stakes transactions, with spoken language reserved for private or
    low-stakes exchange. Flag for author decision.

[WARNING] INV_08 (Costume Without Cause): A_01 trait 'ritual_scarring'.
  → No Level I or II cause found. Hypothesis candidates:
    (a) Scarring as a visible record of memories sacrificed (P_01 side effect made external).
    (b) Scarring as a social marker of alchemical rank — the more you've spent, the more you've earned.
    (c) Geographic/initiation rite tied to G_01's isolation.
  → Author must select or reject a hypothesis.

[WARNING] INV_07 (Amnesiac Culture): No historical conflict defined.
  → history.audit: The History Generator is recommended before further development.
```

---

### 6.2 The History Generator (Level V synthesis)

Given the current `world_manifest`, the History Generator synthesizes a plausible 500-year timeline by simulating the *discovery moment* of the PowerSource and propagating its effects through the algebra chain.

**Logic:**
1. Identify the PowerSource discovery event (or prompt author to define it).
2. Simulate `power_diffusion` algebra: what institution first captured it?
3. Simulate `authority` algebra forward in time: as the institution grew, what infrastructure did it build to *protect* its access?
4. Identify the first `Conflict` algebra: who challenged that institution, and why?
5. Run `tradition` algebra: what taboos, laws, and holidays emerged from that conflict?
6. Output as a structured timeline with named factions and dated crises.

**Example output fragment (sample manifest):**

```
Year 0:    P_01 first documented. A hermit in the G_01 mountain range discovers that
           memory consumption can transmute mineral_salt into medicine. Effect is local.

Year 40:   First organized practitioners form a loose guild. No formal access gate.
           Power is diffuse and poorly understood.

Year 112:  The Salt Famine. G_01's primary food-trade resource (mineral_salt export) collapses
           due to overuse of alchemy draining the practitioners who managed the mines.
           Population pressure creates the first Malthusian crisis.

Year 115:  Resolution: The Memory Bank formalizes. Access to P_01 is now gated through
           a licensed practitioner system. The Salt Famine becomes the founding trauma
           → GENERATES: The Fast of Empty Hands (annual ritual, INV_07 satisfied).
           → GENERATES: Taboo against unsanctioned memory-work ("ghost-burning").

Year 200:  The Silent Cult emerges as a counter-institution. Where the Memory Bank
           commodifies memory-spending, the Cult venerates memory-preservation.
           First institutional conflict over P_01 access.
```

---

### 6.3 The Agentic Pressure Test (Level VII simulation)

The Pressure Test instantiates the defined institutions as competing agents with declared goals and asks: **given current infrastructure and power constraints, who is winning in 20 years, and what does the world look like when they do?**

**Simulation parameters:**
- Each institution is given a goal: `Profit`, `Power`, `Survival`, `Orthodoxy`, or `Expansion`.
- Each institution has access to only the `resource_claimed` it has defined.
- The LLM evaluates each institution's *structural advantage* given the current invariant state of the world.

**Example output:**

```
PRESSURE TEST RESULTS — Horizon: 20 years

Institution Positions (current):
  I_01 Memory Bank  | Goal: Profit   | Controls: P_01 access gate
  I_02 Silent Cult  | Goal: Power    | Controls: ritual identity layer
  I_03 Mtn Traders  | Goal: Survival | Controls: G_01:mineral_salt trade routes

Structural Analysis:

  ADVANTAGE: I_01 (Memory Bank)
  Rationale: P_01 is the world's only high-efficiency power source. Profit-driven
  institutions with monopoly access to a scarce resource have a strong attractor state:
  they will raise the cost of access until challenged. With no defined counter-institution
  at IV (Economic layer), there is no market competition. I_01 will accrete wealth and
  begin purchasing political compliance from I_03 within ~8 years.

  THREAT VECTOR: I_02 (Silent Cult)
  Rationale: The Cult controls identity, not economics. However, INV_05 requires an active
  conflict over P_01. The Cult's most logical avenue is ideological delegitimization of
  the Memory Bank — framing memory-for-profit as sacrilege. This is a slow but corrosive
  attack on I_01's legitimacy, not its resources. Without a Level VI enforcement mechanism
  (I_02 has none defined), the Cult cannot win a direct confrontation.

  FAILURE STATE: I_03 (Mountain Traders)
  Rationale: I_03's resource (mineral_salt) is already flagged as historically unstable
  (Salt Famine, Year 112). G_01's carrying capacity is LOW. I_03 is in a structurally
  weak position. In 20 years, without an infrastructure investment (roads, preservation
  alchemy), I_03 either collapses or becomes a client of I_01.

PROJECTED STATE (Year +20):
  I_01 dominant. I_03 absorbed or tributary. I_02 in open ideological conflict with I_01,
  with radicalization likely as institutional power continues to concentrate.

  NARRATIVE PRESSURE POINT: A practitioner who has spent so many memories they no longer
  know who they are — but are extremely *powerful* — is neither loyal to I_01 (which made
  them) nor capable of joining I_02 (which values preservation). This is your protagonist's
  structural position.
```

---

## 7. LLM System Prompt: The Auditor

This is the prompt you give the LLM to instantiate it as the Crucible's reasoning engine. It should be provided as the system prompt in any Crucible session.

```
SYSTEM PROMPT — THE CRUCIBLE AUDITOR v2.0

You are a worldbuilding logic auditor operating under the Crucible framework. Your function
is not to generate creative content — it is to find the structural failures in the world
being described and force the author to resolve them.

YOUR OPERATING PRINCIPLES:

1. PESSIMISTIC SKEPTICISM. Your default assumption is that any claimed stability is false.
   Every "ancient empire" is one bad harvest from collapse. Every "peaceful culture" has
   suppressed conflict. Never accept surface-level claims. Always ask: what is the actual
   mechanism keeping this state in equilibrium?

2. INVARIANT ENFORCEMENT BEFORE GENERATION. Before you suggest any new lore, you must
   run a verification pass against all defined invariants. If any invariant is unresolved,
   you must flag it as an ERROR or WARNING BEFORE proceeding. You do not build on an
   invalid foundation.

3. CHAIN OF THOUGHT AUDITING. When evaluating any world element, you must trace its
   dependency chain explicitly:
   → What Level I (Physical) constraint does this depend on?
   → What Level II (Power) mechanism enables or limits this?
   → What Level VI (Infrastructure) is required to sustain this at scale?
   If any link in this chain is NULL, flag it.

4. HYPOTHESIS DISCIPLINE. When an invariant failure is detected, you must offer the author
   2–3 specific resolution hypotheses, ranked by structural coherence. You do not pick for
   the author. You present options and their downstream implications.

5. DIAGNOSTIC FORMAT. Structure all audit output as compiler diagnostics:
   [SEVERITY] INV_ID (Invariant Name): Brief description.
     → Algebra implicated: ...
     → Proposed resolutions: ...

6. THE PRESSURE TEST STANCE. When running the Agentic Pressure Test, you must adopt
   an adversarial stance toward every institution. Institutions are not noble, stable, or
   coherent by default. Every institution is protecting a resource claim. Find the crack
   in every institution's position.

7. DO NOT FLATTER. You are a compiler, not a collaborator. "This is interesting" is not
   useful feedback. "This fails INV_05 because you have two institutions claiming the same
   power source with no defined conflict algebra" is useful feedback.

INPUT FORMAT: You will receive a world_manifest YAML. Begin by listing all defined types
and their levels. Then run every applicable algebra. Then check every invariant. Then
report diagnostics. Only after diagnostics are complete should you offer to run the
History Generator or Pressure Test.
```

---

## 8. Implementation Architecture

### 8.1 Parser

- Parse the YAML manifest to extract all typed entities.
- Build a **dependency graph**: nodes are typed entities, edges are algebra transformations.
- Run invariant checks as graph queries (e.g., "does every `PowerSource` with `access=gated` have an outgoing edge to an `Institution`?").

### 8.2 Visualization

Represent the world as a directed graph where:
- **Node color** encodes Level (I = gray, II = blue, III = green, IV = yellow, V = orange, VI = red, VII = white).
- **Edge weight** encodes dependency strength.
- **Broken edges** (null pointers) render as dashed red lines — visually surfacing invariant failures.

A Neo4j or simple D3.js force-directed graph works for this. The key is that *missing* connections should be as visually prominent as present ones.

### 8.3 Session Flow

```
1. Author uploads world_manifest YAML.
2. Parser extracts entities and builds dependency graph.
3. Auditor LLM runs invariant check pass (Chain-of-Thought enforced).
4. Diagnostics returned to author.
5. Author resolves or defers each diagnostic.
6. [Optional] Author triggers History Generator → timeline output added to manifest.
7. [Optional] Author triggers Pressure Test → institutional simulation output.
8. Loop until audit_depth is satisfied.
```

### 8.4 Prompt Engineering Notes

- Use **Chain-of-Thought** with explicit step headers (`Step 1: Extract types. Step 2: Run algebras. Step 3: Check invariants.`) to prevent the LLM from skipping the audit and going straight to generation.
- Provide **few-shot examples** of valid diagnostic output in the system prompt to lock in the compiler-diagnostic format.
- Use **temperature 0.2–0.4** for audit passes (you want determinism), and **0.7–0.9** for History Generator and Pressure Test (you want generative variety).
- Consider a two-pass architecture: first pass is pure audit (low temperature, structured output), second pass is generative resolution proposals (higher temperature).

---

## 9. Open Questions / v3.0 Candidates

- **Faction Simulation:** The Pressure Test currently runs a single 20-year horizon. A recursive simulation (re-run every 10 years, updating the manifest with outcomes) would produce emergent history rather than projected history.
- **Player Character Integration:** Add a `protagonist` type with a defined `resource_position` (what they control or lack access to). The Pressure Test then answers: *where does this character fit in the institutional war, and who has structural reasons to want them dead?*
- **Cross-World Comparison:** If two worlds are loaded simultaneously, the auditor can identify which invariants are shared (genre conventions) and which are world-specific differentiators.
- **Export to TTRPG formats:** The timeline and institutional outputs map cleanly to campaign prep formats (session zero, faction sheets, encounter tables derived from conflict algebras).

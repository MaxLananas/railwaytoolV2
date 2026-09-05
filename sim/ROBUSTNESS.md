# ROBUSTNESS — contrats, invariants et couverture

Objectif utilisateur : **dans des milliers et des milliers de cas, il doit
être impossible de trouver un bug visible**. Ce document liste chaque
situation codée, chaque invariant vérifié et où il est appliqué
(pipeline produit Java, simulateur de référence, corpus de parité).

## 1. Contrats visibles (ce que le joueur ne doit JAMAIS voir)

| # | Contrat | Implémenté dans | Vérifié par |
|---|---------|-----------------|-------------|
| C1 | **Aucun rail manquant** : tout voxel de trace devient un core visible (corail/pilier noir/pupitre/mousse, ±1 vertical). | designs classic/nature, gardes anti-refus | invariant **I1** (export) |
| C2 | **Aucun morceau de voie flottant** : aucun rail ni base de colonne ne garde de l'air — ou de l'EAU — sous lui (≤ 4 blocs comblés ; au-delà = pont assumé). | `fillSupports` classic/nature (air+eau, rails+bases), `support_fill` sim | invariant **I4** |
| C3 | **Aucune pile/monticule** : une colonne (x,z) ne porte jamais 2 voxels de voie à des hauteurs différentes. | `dedupe_columns` / `Grounding.dedupeColumns` (métrique ruban) | invariant **I3** |
| C4 | **Voie continue** : les cores d'une trace forment UN ruban (colonnes adjacentes, toute hauteur — escaliers et croisements empilés inclus ; seul un trou HORIZONTAL est une cassure). | grounding+passe dedupe (connectivité préservée) | invariant **I2** (métrique `components_ribbon`) |
| C5 | **Aucune laine résiduelle** : aucune laine de trace ne reste visible après le build (rail manquant ailleurs). | purge L, dedupe, consommation au refus de colonne | invariant **I5** |
| C6 | **Rebuild idempotent** : reconstruire la même voie ne change rien (pas de valse murets↔panneaux↔portes, pas de porte orpheline). | garde colonne à deux vitesses (§3) | stress `classic-idempotence`, `classic-protect` |
| C7 | **Aucun rail existant détruit** : une 2e voie (ou un rebuild) n'écrase jamais un core existant ; le décor d'une autre voie n'est repris que par un core (jonction), sinon jamais. | gardes cores (`NATURE_CORES`/`isRailCore`) | stress `-protect`, corpus `jun-*` |
| C8 | **Aucun bloc parasite autour d'un pilier** : les piliers de support descendent proprement jusqu'au sol (ou au lit d'eau), sans souches en plein ciel. | `fillSupports` étendu aux bases de colonnes | `pillar_scan` (0 parasite) |
| C9 | **Aucun creusage anarchique** : un tunnel ne perce qu'UNE crête de ≤ 2 blocs par colonne, tous passages confondus. | branche `dug` de la rectification verticale | stress `stupid_stress` |
| C10 | **Leaf_litter fidèle au script** : 3 segments dès que le coin change de X ou de Z (tables 7 paires, `is_moss`, lectern+carpet sinon moss+bouton). | nature (portage exact du Lua) | stress true-diag, parité |
| C11 | **Portes classiques = moitié basse uniquement** (thème clair). | `build_column`/`ColumnWriter.column` | parité tokens `door_lower_*` |

## 2. Situations codées (matrice de couverture)

Corpus de parité `sim/parity_export.py` → **752 scènes** régénérées à chaque
CI (déterministe, `PYTHONHASHSEED=0`), rejouées bloc par bloc par le VRAI
pipeline Java dans `ParityHarness` (`gradle parityCheck`, porte du build).

| Famille | N | Ce qu'elle piège |
|---------|---|------------------|
| `drift/long2/lturn/tjun/xlvl/spiral/zigzag/fuzz` (historique) | 42 | les régressions réelles des captures (dérive dentée, branche L, croisements T/X même niveau & dy=1, spirale collines, zigzag chaos) |
| `fuzz-{flat,hills,chaos}-*` | 180 | polylignes aléatoires 6-16 pts, 3 terrains, 3 styles/thèmes |
| `jun-*` | 90 | jonctions denses 2-3 voies au même centre (croisements réels) |
| `buried-*` | 40 | mode enterré (`baseDy=-1`) |
| `lake-*` | 80 | voies traversant des mares (surface/lit, mélange croisé) |
| `wool-*` | 80 | laines posées par le joueur sur le passage (obstacles ou marqueurs) |
| `rock-*` | 50 | terrain parsemé pierre/andesite/gravier |
| `mount-*` | 80 | montées/descentes raides (sauts ±1/±2) |
| `tun-*` | 50 | tunnels sous les crêtes (points sous le terrain) |
| `teeth-*` | 80 | dérives dentées y alterné (dents de scie) |

En pratique le corpus s'exécute entièrement **valide** : les invariants
I1-I5 ne laissent exporter aucune scène dont l'attendu serait faux — une
violation d'invariant INVALIDE l'export (exit 1) et casse donc la CI :
c'est un vrai bug découvert, jamais une attente « ajustée ».

Suites de stress locales (sim) — toutes vertes :
- `stress.py` : 729 scénarios / 6068 builds (lignes, vraies diagonales,
  escaliers, boucles, croisements, idempotence stricte, protection rebuild).
- `realistic_stress.py` : 56 lignes / 288 builds / 232 M vérifications.
- `repro_user.py`, `repro_photos.py`, `repro_scene3.py` : les captures.
- `pillar_scan.py` : chaque pilier, absence de parasite + diagrammes
  (`sim/pillar_report.txt`).

### Déterminisme strict : le tirage du sol ne décide jamais la géométrie

Le mix de sol (deepslate / cobbled / pale_oak / minerais / gravel) est tiré
aléatoirement **mais tous ses blocs sont `wool-layable`** (sim
`NATURAL_SOFT` = Java `Grounding.isWoolLayable`) : une trace ultérieure qui
repasse exactement sur un support posé par une trace antérieure continue
sans dévier. Sinon la position des voies dépendrait du RNG — non
reproductible entre le sim et le port Java, et instable d'un run à l'autre
(scène témoin `jun-70`, croisement 3 traces : un `pale_oak_wood` tiré
déviait la 3ᵉ trace d'une case). Le RNG Java de `pickSoil` est en outre
**seedé** (`Random(20240913)`, jamais `ThreadLocalRandom`) : captures et
parité reproductibles bloc pour bloc, tous les blocs du mix tokenisant
`soil` des deux côtés.

## 3. Règles de priorité (qui gagne une case ?)

1. **Un core (corail/pilier noir/pupitre/mousse) n'est JAMAIS écrasé** —
   ni par du décor, ni par un core ultérieur (garde `NATURE_CORES` sim /
   `ColumnWriter.isRailCore` Java, passes designs ET grounding).
2. **Un core ultérieur reprend la case d'un décor** d'une voie antérieure
   (jonctions denses : le rail visible passe avant le muret d'à côté).
3. **Un décor cède devant tout bloc de rail existant** (anti-valse au
   rebuild), sauf s'il est identique à lui-même. Le gravier est le lit
   passif améliorable (un décor peut le remplacer, jamais l'inverse).
4. **La colonne refusée consomme sa laine** : si le core d'un voxel est
   précisément celui qui occupe la case (croisement = colonne partagée),
   la laine blanche de trace disparaît proprement — jamais de laine
   résiduelle visible (C5).

## 4. Appartenance d'un voxel de trace = POSITION, pas nom de bloc

Le remplissage uniforme par défaut du mod est `ORANGE_WOOL` ; le joueur
peut aussi poser de la laine à la main. Une case `*_wool` n'est donc
JAMAIS un critère de voie : toutes les passes basses
(`Grounding/LCorners/flattenTeeth/dedupeColumns` et leurs miroirs sim)
consultent le `_TRACE_WOOL`/« laidWool » = l'ensemble des POSITIONS des
voxels réellement laineux de la trace courante. Conséquences :

- le sol-laine d'un build précédent ne verrouille plus les descentes ;
- le coin-laissé d'une remontée ne peut plus écraser un corail voisin
  sous prétexte que le voxel « y était dans la liste » (bug `jun-45`) ;
- une laine posée par le joueur sur le passage est un **obstacle solide
  protégé**, jamais absorbée par la voie ni supprimée ;
- la pose initiale de laine (`isWoolLayable`/`NATURAL_SOFT`) n'accepte
  que l'air et le terrain naturel meuble (herbe/roche/terre/sable/neige/
  glace/eau) : galleries dans la roche OK, rails/wool/décor existants
  intouchables.

## 5. Croisements et jonctions (géométrie)

- Descente d'une laine : elle s'arrête sur tout solide ; un bloc de rail
  existant EST un support (la 2e voie s'appuie, jamais d'écrasement).
- **Junction-sink** : une laine arrêtée sur une colonne de DÉCOR d'une
  autre voie `[sol de support + décor, aucun core]` est reprise dans la
  base — son core prendra la case du décor, le croisement reste **continu
  au même niveau** au lieu d'un hochement de +2 cassant le ruban.
- Croisement sur core : la colonne partage le point (les deux cores
  coexistent empilés — I2 les considère comme le même point de voie).
- L-purge : veto diagonale + connexité locale 3³ + connexité globale
  (points d'articulation) ; un coin ne devient herbe que POSÉ hors eau.

## 6. Chiffres actuels

- **752 scènes de parité** exportées, toutes valides (I1-I5 verts).
- Harnais Java : parité **bloc par bloc** exigée (comparaison bidirectionnelle
  par ensemble de changements : toute fuite n'importe où = échec).
- 0 parasite autour des piliers, 0 violation stress sur 6068+288 builds.

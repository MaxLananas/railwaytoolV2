# Railway Tools for Axiom — Rail BTE

Addon Axiom **tout-en-un** pour les voies ferrées de BuildTheEarth : il reproduit
intégralement le système de rail du tutoriel ferroviaire BTE France, sans les 4
scripts d'origine (spline → rectification → coloration → construction), avec un
seul outil, en une fraction de seconde.

**Minecraft 1.21.10 · Fabric · Axiom 5.4.2+**

---

## Le principe : un seul outil

Le tutoriel BTE France demande 4 scripts Axiom exécutés à la main dans l'ordre
(tracer la spline, niveler la laine, colorier, construire). **Rail BTE** fait tout
cela automatiquement, en direct, pendant que vous posez des points :

1. **Voxelisation 26-connexe** — la spline Catmull-Rom est convertie en un chemin
   de blocs continu, sans trous.
2. **Rectification** — la trace est nivellée (colle au relief, monte si enterrée,
   descend si en l'air) et les coins en « L » sont épurés — exactement la logique
   des scripts 1 et 2 du tuto.
3. **Classification géométrique** — chaque bloc est analysé en N-S / E-O / diagonal
   à partir de ses voisins (déterministe, sans script de coloration).
4. **Construction** — le design choisi est construit dans le pipeline d'édition
   d'Axiom : **annulation native (Ctrl+Z)** et synchronisation serveur.

L'aperçu fantôme se recalcule à chaque point ou changement d'option : vous voyez le
rail exact avant de le construire. Poser 8 points pour un virage, vérifier, Entrée,
c'est fini — environ 0,5 seconde.

## Utilisation

| Action | Effet |
|--------|-------|
| **Clic droit** | Ajoute un point de contrôle (au-dessus du bloc visé) |
| **Entrée** | Construit le rail (undoable via Axiom) |
| **Suppr** | Retire le dernier point |

Options du panneau :

- **Densité de la spline** (2–12) : lissage du tracé.
- **Coller au relief** : nivelage vertical (comme l'outil 1 du tuto).
- **Épurer les coins en L** : redresse les artefacts de voxelisation (outil 2 du tuto).
- **Aperçu fantôme** : affiche le rail calculé sans toucher au monde.
- **Style** : *Classique* ou *Nature*.
- **Orientation** : Auto, N-S, E-O ou Diagonale forcée.
- **Classique** : thème Sombre (murets mud-brick + étagères en sapin) ou Clair
  (murets en andésite + portes en fer), remplissage du sol uniforme (bloc
  personnalisable — bouton *Bloc actif Axiom*) ou aléatoire (mélange de 5 blocs
  pondérés, personnalisables), hauteur en surface ou enterrée.

## Les deux designs (identiques au tuto)

### Classique

- Centre N-S : corail mort en bulle fixé au mur, face au **sud** ; centre E-O : face
  à l'**est**.
- Bordures : murets sans colonne centrale ; **murets d'angle** aux virages et
  transitions de diagonales ; **side-blocks** (étagères ou portes) en bout de
  lignes tournantes, aux tiers des longs segments — tables exactes des scripts.
- Diagonales : vraies (moitiés corail S/E + blocs de transition à 4 murets) et
  fausses (4 murets d'angle) gérées ; les cas indéterminés posent de la laine
  noire de signalisation, comme le script d'origine.
- Remplissage : laine orange ou mélange 45/40/10/4/2 %.

### Nature

- Traverses : pupitres (face nord/est) + tapis de mousse pâle ; sur les marches
  hautes : bloc de mousse pâle + bouton de chêne alimenté.
- Gravier latéral, litière de feuilles (2–3 segments orientés selon les voisins,
  tables exactes du script), gravier + litière aux intersections N-S × E-O.
- Les diagonales sont converties vers le type de leur extrémité (amélioration par
  rapport au tuto, qui ne supportait que le design classique).

## Robustesse

- Le moteur lit « monde + plan en cours » : les coraux déjà construits servent
  d'indices d'orientation exactement comme dans les scripts, sans toucher au monde
  réel avant validation.
- Un rail déjà construit n'est **jamais écrasé** (famille de blocs protégés).
- Plafond de sécurité : 60 000 blocs par construction, 6 000 pour le fantôme.
- Si Axiom n'est pas installé, le mod se désactive proprement.

## Construction (développeurs)

```bash
./gradlew build
```

Le build extrait automatiquement `axiomclientapi.jar` (jar-in-jar d'Axiom) vers
`libs/` pour la compilation. Le jar final est dans `build/libs/`.

## Licence

CC0 — domaine public.

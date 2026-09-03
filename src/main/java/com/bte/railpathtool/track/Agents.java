package com.bte.railpathtool.track;

import static com.bte.railpathtool.track.TrackModel.DY_TOLERANCE;

/**
 * « Agents » de scan le long de la trace — portage fidèle des scripts Lua.
 *
 * Chaque bloc de rail regarde sa ligne : combien de blocs la continuent de chaque côté,
 * de quel côté elle tourne quand elle s'arrête, et si elle repart après un décalage.
 * Ces mesures pilotent le choix muret vs side-block, les coraux et les murets d'angle.
 */
public final class Agents {

    /** Le script diagonal n'a pas de borne ; cap de sécurité (indépassable en pratique). */
    public static final int MAX_LINE_SCAN = 20;
    public static final int MAX_DIAG_SCAN = 64;

    private Agents() {
    }

    public enum Turn {NONE, EAST, WEST, SOUTH, NORTH}

    public enum DiagSense {SWNE, SENW}

    /** Résultat d'un scan axial (ligne N-S ou E-O). */
    public static final class LineScan {
        public int dist = 0;             // blocs de trace continue trouvés
        public Turn turn = Turn.NONE;    // direction du virage à la fin de la ligne
        public int turnLen = 1;          // 2 = la ligne repart après décalage, 1 = virage net
    }

    /** Résultat d'un scan diagonal. */
    public static final class DiagScan {
        public int dist = 0;             // blocs DIAG continus
        public TrackType exitType = null; // type de ce qui raccorde à l'extrémité (NS/EW/null)
    }

    /**
     * Scan axial générique.
     *
     * @param step    direction de marche (ex : {0,-1} pour le nord)
     * @param side1   premier côté testé quand la ligne s'arrête (ordre du script)
     * @param side2   second côté testé
     */
    public static LineScan scanLine(TrackModel model, int x, int y, int z, TrackType want,
                                    int[] step, int[] side1, Turn side1Turn,
                                    int[] side2, Turn side2Turn) {
        LineScan a = new LineScan();
        int curY = y;
        for (int i = 1; i <= MAX_LINE_SCAN; i++) {
            int nx = x + step[0] * i;
            int nz = z + step[1] * i;
            boolean found = false;
            for (int dy : DY_TOLERANCE) {
                if (model.typeAt(nx, curY + dy, nz) == want) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) {
                continue;
            }
            int[][] sides = {side1, side2};
            Turn[] names = {side1Turn, side2Turn};
            for (int s = 0; s < 2; s++) {
                for (int dy : DY_TOLERANCE) {
                    if (model.typeAt(nx + sides[s][0], curY + dy, nz + sides[s][1]) == want) {
                        a.turn = names[s];
                        int vy = curY + dy;
                        boolean cont = false;
                        for (int dy2 : DY_TOLERANCE) {
                            if (model.typeAt(nx + sides[s][0] + step[0], vy + dy2,
                                    nz + sides[s][1] + step[1]) == want) {
                                cont = true;
                                break;
                            }
                        }
                        a.turnLen = cont ? 2 : 1;
                        return a;
                    }
                }
            }
            break;
        }
        return a;
    }

    /** Scans N-S standard (nord puis sud, virages est/ouest). */
    public static LineScan scanNorth(TrackModel m, int x, int y, int z) {
        return scanLine(m, x, y, z, TrackType.NS, new int[]{0, -1},
                new int[]{1, 0}, Turn.EAST, new int[]{-1, 0}, Turn.WEST);
    }

    public static LineScan scanSouth(TrackModel m, int x, int y, int z) {
        return scanLine(m, x, y, z, TrackType.NS, new int[]{0, 1},
                new int[]{1, 0}, Turn.EAST, new int[]{-1, 0}, Turn.WEST);
    }

    public static LineScan scanWest(TrackModel m, int x, int y, int z) {
        return scanLine(m, x, y, z, TrackType.EW, new int[]{-1, 0},
                new int[]{0, 1}, Turn.SOUTH, new int[]{0, -1}, Turn.NORTH);
    }

    public static LineScan scanEast(TrackModel m, int x, int y, int z) {
        return scanLine(m, x, y, z, TrackType.EW, new int[]{1, 0},
                new int[]{0, 1}, Turn.SOUTH, new int[]{0, -1}, Turn.NORTH);
    }

    // ------------------------------------------------------------------
    //  Décision muret vs side-block (tables exactes du script, dédupliquées)
    //  Règle : en bout de ligne qui tourne, le side-block est posé à l'OPPOSÉ
    //  de la direction du virage ; le reste de la ligne est en murets, avec
    //  répartition par tiers (Q = L/3, R = L%3) pour 3 <= L <= 40.
    // ------------------------------------------------------------------

    /**
     * Décide du bloc latéral d'une ligne N-S (logique exacte du script 3) :
     * position relative comptée depuis le sud ; le premier tiers suit le virage
     * du côté sud, le dernier tiers celui du côté nord ; le side posé regarde
     * à l'OPPOSÉ du virage. Résultat identique pour les deux côtés latéraux.
     */
    public static LatSide decideNs(LineScan agentNord, LineScan agentSud) {
        int length = agentNord.dist + agentSud.dist + 1;
        int pos = agentSud.dist + 1;
        if (length <= 1) {
            return LatSide.WALL_NS;
        }
        if (length == 2) {
            if (pos == 1) {
                return oppNs(agentSud.turn);
            }
            if (agentNord.turnLen == 1) {
                return oppNs(agentNord.turn);
            }
            return LatSide.WALL_NS;
        }
        if (length <= 40) {
            int q = length / 3;
            int r = length % 3;
            if (pos <= q) {
                return oppNs(agentSud.turn);
            }
            if (pos > 2 * q + r) {
                return oppNs(agentNord.turn);
            }
        }
        return LatSide.WALL_NS;
    }

    /** Idem pour une ligne E-O (position relative comptée depuis l'est). */
    public static LatSide decideEw(LineScan agentOuest, LineScan agentEst) {
        int length = agentOuest.dist + agentEst.dist + 1;
        int pos = agentEst.dist + 1;
        if (length <= 1) {
            return LatSide.WALL_EW;
        }
        if (length == 2) {
            if (pos == 1) {
                return oppEw(agentEst.turn);
            }
            if (agentOuest.turnLen == 1) {
                return oppEw(agentOuest.turn);
            }
            return LatSide.WALL_EW;
        }
        if (length <= 40) {
            int q = length / 3;
            int r = length % 3;
            if (pos <= q) {
                return oppEw(agentEst.turn);
            }
            if (pos > 2 * q + r) {
                return oppEw(agentOuest.turn);
            }
        }
        return LatSide.WALL_EW;
    }

    /** Ligne N-S : virage est -> side ouest (OPPOSITE_NS du script). */
    private static LatSide oppNs(Turn turn) {
        return switch (turn) {
            case EAST -> LatSide.SIDE_WEST;
            case WEST -> LatSide.SIDE_EAST;
            default -> LatSide.WALL_NS;
        };
    }

    /** Ligne E-O : virage sud -> side nord (OPPOSITE_EW du script). */
    private static LatSide oppEw(Turn turn) {
        return switch (turn) {
            case SOUTH -> LatSide.SIDE_NORTH;
            case NORTH -> LatSide.SIDE_SOUTH;
            default -> LatSide.WALL_EW;
        };
    }

    /** Choix latéral résolu (muret d'axe, ou side-block orienté à poser). */
    public enum LatSide {
        WALL_NS(null), WALL_EW(null),
        SIDE_NORTH(Turn.NORTH), SIDE_SOUTH(Turn.SOUTH),
        SIDE_EAST(Turn.EAST), SIDE_WEST(Turn.WEST);

        /** Facing du side-block à poser ; null si c'est le muret d'axe. */
        public final Turn sideFacing;

        LatSide(Turn sideFacing) {
            this.sideFacing = sideFacing;
        }

        public boolean isWall() {
            return sideFacing == null;
        }
    }

    // ------------------------------------------------------------------
    //  Diagonales
    // ------------------------------------------------------------------

    /** Sens de la diagonale — mêmes priorités que le script (diag continue > axial > défaut). */
    public static DiagSense diagSense(TrackModel m, int x, int y, int z) {
        if (m.typeNear(x + 1, y, z - 1) == TrackType.DIAG
                || m.typeNear(x - 1, y, z + 1) == TrackType.DIAG) {
            return DiagSense.SWNE;
        }
        TrackType tNe = m.typeAt(x + 1, y, z - 1); // y exact (comme le script)
        TrackType tSo = m.typeAt(x - 1, y, z + 1);
        if (tNe == TrackType.NS || tNe == TrackType.EW
                || tSo == TrackType.NS || tSo == TrackType.EW) {
            return DiagSense.SWNE;
        }
        return DiagSense.SENW;
    }

    /** Scan le long d'une diagonale (dx, dz) ; détecte le type de la raccordure. */
    public static DiagScan scanDiag(TrackModel m, int x, int y, int z, int dx, int dz) {
        DiagScan a = new DiagScan();
        int curY = y;
        for (int i = 1; i <= MAX_DIAG_SCAN; i++) {
            int nx = x + dx * i;
            int nz = z + dz * i;
            boolean found = false;
            for (int dy : DY_TOLERANCE) {
                if (m.typeAt(nx, curY + dy, nz) == TrackType.DIAG) {
                    a.dist++;
                    curY += dy;
                    found = true;
                    break;
                }
            }
            if (found) {
                continue;
            }
            for (int dy : DY_TOLERANCE) {
                TrackType t = m.typeAt(nx, curY + dy, nz);
                if (t == TrackType.NS || t == TrackType.EW) {
                    a.exitType = t;
                    break;
                }
            }
            break;
        }
        return a;
    }

    /** Résultat d'analyse d'un bloc diagonal. */
    public static final class DiagResult {
        /** Type de corail central ; null => indéterminé (black_wool de signalisation). */
        public TrackType coreType;
        /** Pose les 4 murets d'angle (transition / fausse diagonale). */
        public boolean transition;
        /** Sinon, pose les deux murets du côté correspondant au type de corail. */
        public DiagSense sense;

        DiagResult(TrackType coreType, boolean transition, DiagSense sense) {
            this.coreType = coreType;
            this.transition = transition;
            this.sense = sense;
        }
    }

    /**
     * Décide du rendu d'un bloc DIAG.
     *  - fausse diagonale (même type axial des 2 côtés) : corail de ce type + 4 murets
     *  - vraie diagonale : moitiés colorées par leurs extrémités, 2 blocs centraux
     *    de transition (4 murets), indéterminé => null.
     */
    public static DiagResult analyseDiag(TrackModel m, int x, int y, int z) {
        DiagSense sense = diagSense(m, x, y, z);
        boolean swne = sense == DiagSense.SWNE;
        DiagScan north = swne ? scanDiag(m, x, y, z, 1, -1) : scanDiag(m, x, y, z, -1, -1);
        DiagScan south = swne ? scanDiag(m, x, y, z, -1, 1) : scanDiag(m, x, y, z, 1, 1);

        boolean falseDiag = north.exitType != null && north.exitType == south.exitType;
        if (falseDiag) {
            return new DiagResult(north.exitType, true, sense);
        }

        int length = north.dist + south.dist + 1;
        int mid = length / 2;
        int rel = south.dist + 1;

        TrackType core;
        boolean transition;
        if (length == 1) {
            core = TrackType.NS;
            transition = true;
        } else {
            TrackType ext = rel <= mid ? south.exitType : north.exitType;
            core = ext; // peut être null => indéterminé
            transition = rel == mid || rel == mid + 1;
        }
        return new DiagResult(core, transition, sense);
    }
}

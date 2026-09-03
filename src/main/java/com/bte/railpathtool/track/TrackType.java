package com.bte.railpathtool.track;

/** Les 3 états d'un bloc de trace de rail (identiques au tuto BTE France). */
public enum TrackType {
    /** Nord-Sud : corail facing sud / pupitre facing nord / rouge. */
    NS,
    /** Est-Ouest : corail facing est / pupitre facing est / bleu. */
    EW,
    /** Diagonale à 45° (SW-NE ou SE-NW) / vert. */
    DIAG
}

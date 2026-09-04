package com.bte.railpathtool.lib.graph;

/**
 * Union-Find (compression de chemin + union par rang) : regroupe des voxels en
 * composantes connexes. Sert a detecter une trace morcelee (ilot coupe du
 * reste) pour avertir le joueur avant la construction.
 */
public final class UnionFind {

    private final int[] parent;
    private final int[] rank;
    private int components;

    public UnionFind(int n) {
        parent = new int[n];
        rank = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        components = n;
    }

    public int find(int x) {
        int p = parent[x];
        if (p != x) {
            parent[x] = find(p);
        }
        return parent[x];
    }

    public boolean union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra == rb) {
            return false;
        }
        if (rank[ra] < rank[rb]) {
            parent[ra] = rb;
        } else if (rank[ra] > rank[rb]) {
            parent[rb] = ra;
        } else {
            parent[rb] = ra;
            rank[ra]++;
        }
        components--;
        return true;
    }

    public int components() {
        return components;
    }
}

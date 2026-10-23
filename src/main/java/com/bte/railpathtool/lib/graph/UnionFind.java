package com.bte.railpathtool.lib.graph;

/**
 * Union-Find with path compression and union by rank. Groups voxels into
 * connected components so a fragmented trace can warn the player before the
 * build.
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

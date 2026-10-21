package com.nstut.endless.vertical;

/** Per-solve opacity snapshot for a 16-cube and its five 15-block sky rays. */
public final class SectionSkyOpacityCache {
    public static final int WIDTH=46, HEIGHT=31, CAPACITY=WIDTH*WIDTH*HEIGHT;
    @FunctionalInterface public interface Sampler { int sample(int x,int y,int z); }
    private final int minX,minY,minZ;
    private final Sampler sampler;
    private final byte[] costs=new byte[CAPACITY];
    public SectionSkyOpacityCache(int minX,int minY,int minZ,Sampler sampler) {
        this.minX=minX;this.minY=minY;this.minZ=minZ;this.sampler=java.util.Objects.requireNonNull(sampler);
    }
    public int get(int x,int y,int z) {
        int dx=x-minX,dy=y-minY,dz=z-minZ;
        if(dx<0||dx>=WIDTH||dy<0||dy>=HEIGHT||dz<0||dz>=WIDTH)throw new IllegalArgumentException("Outside bounded sky ray snapshot");
        int index=(dy*WIDTH+dz)*WIDTH+dx;
        int cost=costs[index];
        if(cost==0){cost=Math.min(15,Math.max(1,sampler.sample(x,y,z)));costs[index]=(byte)cost;}
        return cost;
    }
}

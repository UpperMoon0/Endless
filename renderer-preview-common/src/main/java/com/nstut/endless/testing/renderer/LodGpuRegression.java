package com.nstut.endless.testing.renderer;

import com.nstut.endless.vertical.lod.VerticalLodGpu;
import com.nstut.endless.vertical.lod.VerticalLodMesh;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;

/** Real GL pixels and state restoration, isolated from the fixture framebuffer. */
public final class LodGpuRegression {
    public static void verify() {
        int draw=GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        int read=GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        int rb=GL33C.glGetInteger(GL33C.GL_RENDERBUFFER_BINDING);
        int[] viewport=new int[4]; GL33C.glGetIntegerv(GL33C.GL_VIEWPORT,viewport);
        float[] clear=new float[4]; GL33C.glGetFloatv(GL33C.GL_COLOR_CLEAR_VALUE,clear);
        double clearDepth=GL33C.glGetDouble(GL33C.GL_DEPTH_CLEAR_VALUE);
        boolean scissor=GL33C.glIsEnabled(GL33C.GL_SCISSOR_TEST);
        boolean depthWrite=GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK);
        int fbo=GL33C.glGenFramebuffers(),color=GL33C.glGenTextures(),depth=GL33C.glGenRenderbuffers(),depthTexture=GL33C.glGenTextures();
        int texture=GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
        var gpu=new VerticalLodGpu();
        try {
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,fbo);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,color);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_RGBA8,32,32,0,GL33C.GL_RGBA,GL33C.GL_UNSIGNED_BYTE,0L);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,texture);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER,GL33C.GL_COLOR_ATTACHMENT0,GL33C.GL_TEXTURE_2D,color,0);
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER,depth);
            GL33C.glRenderbufferStorage(GL33C.GL_RENDERBUFFER,GL33C.GL_DEPTH_COMPONENT24,32,32);
            GL33C.glFramebufferRenderbuffer(GL33C.GL_FRAMEBUFFER,GL33C.GL_DEPTH_ATTACHMENT,GL33C.GL_RENDERBUFFER,depth);
            if(GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER)!=GL33C.GL_FRAMEBUFFER_COMPLETE)
                throw new IllegalStateException("LOD test framebuffer incomplete");
            GL33C.glViewport(0,0,32,32); GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
            GL33C.glClearColor(0,0,0,1); GL33C.glClearDepth(1); GL33C.glDepthMask(true);
            var projection=new Matrix4f().ortho(-1,17,-1,17,.1f,100);
            var red=gpu.upload(VerticalLodMesh.build((x,y,z)->0xffff0000,4));
            var blue=gpu.upload(VerticalLodMesh.build((x,y,z)->0xff0000ff,4));
            render(gpu,red,projection,0); pixel(0,80,255);
            // Identical cached geometry with only a camera-relative anchor change.
            render(gpu,red,projection,1000); pixel(-1,0,0);
            render(gpu,blue,projection,0); pixel(2,80,255);
            gpu.setAmbient(.1f);
            var dark=gpu.upload(VerticalLodMesh.build((x,y,z)->0x10ff0000,4));
            render(gpu,dark,projection,0); pixel(0,10,30);
            var emissive=gpu.upload(VerticalLodMesh.build((x,y,z)->0x1fff0000,4));
            render(gpu,emissive,projection,0); pixel(0,80,255);
            gpu.setAmbient(1);
            var perspective=new Matrix4f().perspective((float)Math.toRadians(60),1,.05f,32);
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT|GL33C.GL_DEPTH_BUFFER_BIT);
            gpu.begin(perspective);
            try { gpu.draw(red,-8,-8,-128); gpu.draw(blue,-8,-8,-256); } finally { gpu.end(); }
            pixel(0,80,255); // Both are beyond native far; the nearer proxy must win.
            GL33C.glClearColor(0,1,0,1); GL33C.glClearDepth(.5);
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT|GL33C.GL_DEPTH_BUFFER_BIT); GL33C.glClearDepth(1);
            gpu.begin(perspective);
            try { gpu.draw(red,-8,-8,-128); } finally { gpu.end(); }
            pixel(1,200,255); // Opaque native foreground must mask the distant proxy.
            var depthPixel=MemoryUtil.memAllocFloat(1);
            try {
                GL33C.glReadPixels(16,16,1,1,GL33C.GL_DEPTH_COMPONENT,GL33C.GL_FLOAT,depthPixel);
                if(Math.abs(depthPixel.get(0)-.5f)>.00001f) throw new IllegalStateException("LOD modified native depth");
            } finally { MemoryUtil.memFree(depthPixel); }
            // Production path: explicit texture target, even when a framegraph has
            // unbound its framebuffer before the post-world hook (Minecraft 26).
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,depthTexture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_DEPTH_COMPONENT32F,32,32,0,GL33C.GL_DEPTH_COMPONENT,GL33C.GL_FLOAT,0L);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
            GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,texture);
            GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER,GL33C.GL_DEPTH_ATTACHMENT,GL33C.GL_TEXTURE_2D,depthTexture,0);
            GL33C.glClearColor(0,0,0,1);GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT|GL33C.GL_DEPTH_BUFFER_BIT);
            gpu.setTarget(color,depthTexture,32,32);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,0);
            gpu.begin(perspective);
            try { gpu.draw(red,-8,-8,-128); } finally { gpu.end(); }
            if(GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING)!=0) throw new IllegalStateException("LOD leaked target framebuffer");
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,fbo);pixel(0,80,255);
            GL33C.glClearColor(0,1,0,1);GL33C.glClearDepth(.5);
            GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT|GL33C.GL_DEPTH_BUFFER_BIT);GL33C.glClearDepth(1);
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,0);
            gpu.begin(perspective);
            try { gpu.draw(red,-8,-8,-128); } finally { gpu.end(); }
            GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,fbo);pixel(1,200,255);
        } finally {
            gpu.close();
            GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER,draw);
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER,read);
            GL33C.glBindRenderbuffer(GL33C.GL_RENDERBUFFER,rb);
            GL33C.glDeleteTextures(color); GL33C.glDeleteTextures(depthTexture); GL33C.glDeleteRenderbuffers(depth); GL33C.glDeleteFramebuffers(fbo);
            GL33C.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GL33C.glClearColor(clear[0],clear[1],clear[2],clear[3]); GL33C.glClearDepth(clearDepth);
            GL33C.glDepthMask(depthWrite);
            if(scissor) GL33C.glEnable(GL33C.GL_SCISSOR_TEST); else GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
        }
    }
    private static void render(VerticalLodGpu gpu,VerticalLodGpu.Mesh mesh,Matrix4f projection,double x) {
        GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT|GL33C.GL_DEPTH_BUFFER_BIT);
        int program=GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM),vao=GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
        int buffer=GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING),depth=GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC);
        boolean blend=GL33C.glIsEnabled(GL33C.GL_BLEND),cull=GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
        gpu.begin(projection);
        try { gpu.draw(mesh,x,0,-32); } finally { gpu.end(); }
        if(program!=GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM)||vao!=GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING)
            ||buffer!=GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING)||depth!=GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC)
            ||blend!=GL33C.glIsEnabled(GL33C.GL_BLEND)||cull!=GL33C.glIsEnabled(GL33C.GL_CULL_FACE))
            throw new IllegalStateException("LOD pass leaked native GL state");
    }
    private static void pixel(int channel,int minimum,int maximum) {
        var data=MemoryUtil.memAlloc(4);
        try {
            GL33C.glReadPixels(16,16,1,1,GL33C.GL_RGBA,GL33C.GL_UNSIGNED_BYTE,data);
            for(int i=0;i<3;i++) {
                int value=data.get(i)&255;
                if((i==channel && (value<minimum || value>maximum))||(i!=channel && value>5))
                    throw new IllegalStateException("LOD framebuffer mismatch channel="+i+" value="+value);
            }
        } finally { MemoryUtil.memFree(data); }
    }
}

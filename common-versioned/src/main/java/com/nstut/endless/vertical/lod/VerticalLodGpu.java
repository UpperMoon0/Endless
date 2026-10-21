package com.nstut.endless.vertical.lod;

import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryUtil;
import java.nio.FloatBuffer;

/** Small persistent color-mesh pass; restores every GL binding/state that it changes. */
public final class VerticalLodGpu implements AutoCloseable {
    private int program, matrix, offset, ambientLocation, fogRangesLocation, fogColorLocation, fogLinearLocation;
    private float ambient = 1;
    private float renderFogStart=1_000_000,renderFogEnd=2_000_000,environmentalStart=1_000_000,environmentalEnd=2_000_000;
    private float fogR,fogG,fogB,fogA=1;
    private boolean linearFog;
    private int nativeColor,nativeDepth,targetWidth,targetHeight;
    private int framebuffer,depthTexture,depthCopy,sampler,depthWidth,depthHeight;
    private int savedDrawFramebuffer,savedReadFramebuffer,savedActiveTexture,savedTexture,savedSampler;
    private final int[] savedViewport=new int[4];
    private final double[] savedDepthRange=new double[2];
    private double savedClearDepth;
    private boolean targetBound,samplerObjects;
    private int nativeDepthLocation,depthInfoLocation,clipModeLocation;
    private int savedProgram, savedVao, savedBuffer, savedDepthFunc;
    private boolean depth, cull, blend, depthWrite;
    private Mesh[] drawMeshes=new Mesh[16];
    private double[] drawX=new double[16],drawY=new double[16],drawZ=new double[16];
    private int drawCount;
    private int batchVao, batchBuffer, batchVertices, queuedBytes, batchCount;
    private long nextMeshId;
    private long[] batchIds=new long[16],batchX=new long[16],batchY=new long[16],batchZ=new long[16];
    private FloatBuffer staging;
    public record Mesh(long id, float[] data, int vertices) implements AutoCloseable {
        @Override public void close() { /* CPU arrays are released by the bounded owning cache. */ }
        public int bytes() { return vertices * 28; }
    }

    public void setAmbient(float light) { ambient = Math.max(.05f,Math.min(1,light)); }
    public void setTarget(int color,int depth,int width,int height) {
        nativeColor=color;nativeDepth=depth;targetWidth=width;targetHeight=height;
    }
    public void setFog(float start,float end,float envStart,float envEnd,float r,float g,float b,float a,boolean linear) {
        renderFogStart=Float.isFinite(start)?Math.min(start,1_000_000):1_000_000;
        renderFogEnd=Float.isFinite(end)?Math.max(renderFogStart+1,Math.min(end,2_000_000)):2_000_000;
        environmentalStart=Float.isFinite(envStart)?Math.min(envStart,1_000_000):1_000_000;
        environmentalEnd=Float.isFinite(envEnd)?Math.max(environmentalStart+1,Math.min(envEnd,2_000_000)):2_000_000;
        fogR=r;fogG=g;fogB=b;fogA=a;linearFog=linear;
    }

    public void begin(Matrix4fc mvp) {
        drawCount=0;
        targetBound=false;
        queuedBytes = 0;
        savedProgram = GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM);
        savedVao = GL33C.glGetInteger(GL33C.GL_VERTEX_ARRAY_BINDING);
        savedBuffer = GL33C.glGetInteger(GL33C.GL_ARRAY_BUFFER_BINDING);
        savedDepthFunc = GL33C.glGetInteger(GL33C.GL_DEPTH_FUNC);
        depth = GL33C.glIsEnabled(GL33C.GL_DEPTH_TEST);
        cull = GL33C.glIsEnabled(GL33C.GL_CULL_FACE);
        blend = GL33C.glIsEnabled(GL33C.GL_BLEND);
        depthWrite = GL33C.glGetBoolean(GL33C.GL_DEPTH_WRITEMASK);
        if (program == 0) {
            int vertex = shader(GL33C.GL_VERTEX_SHADER, """
                #version 150
                in vec3 Position;
                in vec3 Color;
                in float Emission;
                uniform mat4 Mvp;
                uniform vec3 Offset;
                out vec3 color;
                out float emission;
                out vec3 fogPosition;
                noperspective out float nativeProjectedDepth;
                uniform vec4 NativeDepthInfo;
                uniform int ZeroToOneClip;
                void main() {
                    gl_Position = Mvp * vec4(Position + Offset, 1.0);
                    nativeProjectedDepth = (gl_Position.z/gl_Position.w)*NativeDepthInfo.x+NativeDepthInfo.y;
                    // Independent infinite-far depth retains ordering between distant
                    // surfaces. Native depth is compared in the fragment shader.
                    gl_Position.z = gl_Position.w - (ZeroToOneClip != 0 ? 1.0 : 2.0);
                    color = Color;
                    emission = Emission;
                    fogPosition = Position + Offset;
                }
                """);
            int fragment = shader(GL33C.GL_FRAGMENT_SHADER, """
                #version 150
                in vec3 color;
                in float emission;
                uniform float Ambient;
                in vec3 fogPosition;
                uniform vec4 FogRanges;
                uniform vec4 FogColor;
                uniform int LinearFog;
                uniform sampler2D NativeDepth;
                uniform vec4 NativeDepthInfo;
                noperspective in float nativeProjectedDepth;
                out vec4 fragColor;
                float fogFactor(float distance, float start, float end) {
                    float value = clamp((distance-start)/(end-start),0.0,1.0);
                    return LinearFog != 0 ? value : value*value*(3.0-2.0*value);
                }
                void main() {
                    float nativeDepth = texelFetch(NativeDepth,ivec2(gl_FragCoord.xy),0).r;
                    if(nativeDepth != NativeDepthInfo.z &&
                        (nativeProjectedDepth-nativeDepth)*NativeDepthInfo.w > 0.0000001) discard;
                    float fog = max(fogFactor(length(fogPosition.xz),FogRanges.x,FogRanges.y),
                        fogFactor(length(fogPosition),FogRanges.z,FogRanges.w));
                    vec3 lit = color * max(Ambient, emission);
                    fragColor = vec4(mix(lit,FogColor.rgb,fog*FogColor.a),1.0);
                }
                """);
            program = GL33C.glCreateProgram();
            GL33C.glAttachShader(program, vertex); GL33C.glAttachShader(program, fragment);
            GL33C.glBindAttribLocation(program, 0, "Position");
            GL33C.glBindAttribLocation(program, 1, "Color");
            GL33C.glBindAttribLocation(program, 2, "Emission");
            GL33C.glLinkProgram(program);
            GL33C.glDeleteShader(vertex); GL33C.glDeleteShader(fragment);
            if (GL33C.glGetProgrami(program, GL33C.GL_LINK_STATUS) == 0)
                throw new IllegalStateException(GL33C.glGetProgramInfoLog(program));
            matrix = GL33C.glGetUniformLocation(program, "Mvp");
            offset = GL33C.glGetUniformLocation(program, "Offset");
            ambientLocation = GL33C.glGetUniformLocation(program, "Ambient");
            fogRangesLocation = GL33C.glGetUniformLocation(program,"FogRanges");
            fogColorLocation = GL33C.glGetUniformLocation(program,"FogColor");
            fogLinearLocation = GL33C.glGetUniformLocation(program,"LinearFog");
            nativeDepthLocation = GL33C.glGetUniformLocation(program,"NativeDepth");
            depthInfoLocation = GL33C.glGetUniformLocation(program,"NativeDepthInfo");
            clipModeLocation = GL33C.glGetUniformLocation(program,"ZeroToOneClip");
        }
        GL33C.glUseProgram(program);
        GL33C.glUniform1f(ambientLocation, ambient);
        GL33C.glUniform4f(fogRangesLocation,renderFogStart,renderFogEnd,environmentalStart,environmentalEnd);
        GL33C.glUniform4f(fogColorLocation,fogR,fogG,fogB,fogA);
        GL33C.glUniform1i(fogLinearLocation,linearFog?1:0);
        GL33C.glUniformMatrix4fv(matrix, false, mvp.get(new float[16]));
        GL33C.glEnable(GL33C.GL_DEPTH_TEST); GL33C.glDepthFunc(GL33C.GL_LEQUAL);
        GL33C.glDepthMask(true); GL33C.glDisable(GL33C.GL_CULL_FACE); GL33C.glDisable(GL33C.GL_BLEND);
    }

    public Mesh upload(VerticalLodMesh mesh) {
        int vertices = mesh.boxes().size() * 36;
        FloatBuffer data = java.nio.FloatBuffer.allocate(vertices * 7);
        for (var b : mesh.boxes()) {
            face(data,b,0,0.65f); face(data,b,1,0.65f);
            face(data,b,2,0.5f); face(data,b,3,1.0f);
            face(data,b,4,0.8f); face(data,b,5,0.8f);
        }
        return new Mesh(++nextMeshId,data.array(),vertices);
    }

    private static void face(FloatBuffer out, VerticalLodMesh.Box b, int face, float shade) {
        for (int corner : new int[]{0,1,2,0,2,3}) {
            int u = corner == 1 || corner == 2 ? 1 : 0, v = corner >= 2 ? 1 : 0;
            float x, y, z;
            if (face < 2) { x = face == 0 ? b.x0() : b.x1(); y = u == 0 ? b.y0() : b.y1(); z = v == 0 ? b.z0() : b.z1(); }
            else if (face < 4) { y = face == 2 ? b.y0() : b.y1(); x = u == 0 ? b.x0() : b.x1(); z = v == 0 ? b.z0() : b.z1(); }
            else { z = face == 4 ? b.z0() : b.z1(); x = u == 0 ? b.x0() : b.x1(); y = v == 0 ? b.y0() : b.y1(); }
            out.put(x).put(y).put(z).put(((b.rgb()>>>16)&255)/255f*shade)
                .put(((b.rgb()>>>8)&255)/255f*shade).put((b.rgb()&255)/255f*shade).put(((b.rgb()>>>24)&15)/15f);
        }
    }

    public boolean draw(Mesh mesh, double x, double y, double z) {
        if (mesh.vertices == 0 || (long)queuedBytes + mesh.bytes() > (32<<20)) return false;
        queuedBytes += mesh.bytes();
        if(drawCount==drawMeshes.length) {
            int size=drawCount*2;
            drawMeshes=java.util.Arrays.copyOf(drawMeshes,size);
            drawX=java.util.Arrays.copyOf(drawX,size); drawY=java.util.Arrays.copyOf(drawY,size); drawZ=java.util.Arrays.copyOf(drawZ,size);
        }
        drawMeshes[drawCount]=mesh; drawX[drawCount]=x; drawY[drawCount]=y; drawZ[drawCount]=z; drawCount++;
        return true;
    }

    public void end() {
        try { flush(); }
        finally {
            restoreTarget();
            GL33C.glBindVertexArray(savedVao); GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER,savedBuffer);
            GL33C.glUseProgram(savedProgram); GL33C.glDepthFunc(savedDepthFunc); GL33C.glDepthMask(depthWrite);
            restore(GL33C.GL_DEPTH_TEST,depth); restore(GL33C.GL_CULL_FACE,cull); restore(GL33C.GL_BLEND,blend);
            java.util.Arrays.fill(drawMeshes,0,drawCount,null); drawCount=0;
        }
    }

    private void flush() {
        if (drawCount==0) return;
        double anchorX=drawX[0],anchorY=drawY[0],anchorZ=drawZ[0];
        boolean changed=drawCount!=batchCount;
        int vertices = 0;
        for (int i=0;i<drawCount;i++) {
            vertices += drawMeshes[i].vertices;
            if(i>=batchCount || batchIds[i]!=drawMeshes[i].id
                || batchX[i]!=Math.round(drawX[i]-anchorX) || batchY[i]!=Math.round(drawY[i]-anchorY)
                || batchZ[i]!=Math.round(drawZ[i]-anchorZ)) changed=true;
        }
        if (batchVao == 0) {
            batchVao = GL33C.glGenVertexArrays(); batchBuffer = GL33C.glGenBuffers();
            GL33C.glBindVertexArray(batchVao); GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER,batchBuffer);
            GL33C.glEnableVertexAttribArray(0); GL33C.glVertexAttribPointer(0,3,GL33C.GL_FLOAT,false,28,0L);
            GL33C.glEnableVertexAttribArray(1); GL33C.glVertexAttribPointer(1,3,GL33C.GL_FLOAT,false,28,12L);
            GL33C.glEnableVertexAttribArray(2); GL33C.glVertexAttribPointer(2,1,GL33C.GL_FLOAT,false,28,24L);
        }
        GL33C.glBindVertexArray(batchVao); GL33C.glBindBuffer(GL33C.GL_ARRAY_BUFFER,batchBuffer);
        // Camera-relative anchor changes only a uniform. Repack geometry only when
        // visible membership, source revisions, detail, or the native window changes.
        if (changed || vertices != batchVertices) {
            int floats = vertices * 7;
            if (staging == null || staging.capacity() < floats) {
                if (staging != null) MemoryUtil.memFree(staging);
                staging = MemoryUtil.memAllocFloat(floats);
            }
            staging.clear();
            for (int draw=0;draw<drawCount;draw++) {
                float dx=(float)(drawX[draw]-anchorX),dy=(float)(drawY[draw]-anchorY),dz=(float)(drawZ[draw]-anchorZ);
                float[] data=drawMeshes[draw].data;
                for (int i=0;i<data.length;i+=7) staging.put(data[i]+dx).put(data[i+1]+dy).put(data[i+2]+dz)
                    .put(data[i+3]).put(data[i+4]).put(data[i+5]).put(data[i+6]);
            }
            staging.flip(); GL33C.glBufferData(GL33C.GL_ARRAY_BUFFER,staging,GL33C.GL_DYNAMIC_DRAW);
            if(batchIds.length<drawCount) {
                batchIds=new long[drawMeshes.length]; batchX=new long[drawMeshes.length];
                batchY=new long[drawMeshes.length]; batchZ=new long[drawMeshes.length];
            }
            for(int i=0;i<drawCount;i++) {
                batchIds[i]=drawMeshes[i].id; batchX[i]=Math.round(drawX[i]-anchorX);
                batchY[i]=Math.round(drawY[i]-anchorY); batchZ[i]=Math.round(drawZ[i]-anchorZ);
            }
            batchCount=drawCount; batchVertices=vertices;
        }
        if(!prepareTarget()) return;
        GL33C.glUniform3f(offset,(float)anchorX,(float)anchorY,(float)anchorZ);
        GL33C.glDrawArrays(GL33C.GL_TRIANGLES,0,batchVertices);
    }

    /** Share the final color texture, but never clear or overwrite Minecraft's depth. */
    private boolean prepareTarget() {
        savedDrawFramebuffer=GL33C.glGetInteger(GL33C.GL_DRAW_FRAMEBUFFER_BINDING);
        savedReadFramebuffer=GL33C.glGetInteger(GL33C.GL_READ_FRAMEBUFFER_BINDING);
        int color=nativeColor,depth=nativeDepth,width=targetWidth,height=targetHeight,depthType=GL33C.GL_TEXTURE;
        if(color<=0) {
            if(savedDrawFramebuffer==0) return false;
            int attachment=GL33C.glGetInteger(GL33C.GL_DRAW_BUFFER0);
            if(GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER,attachment,
                GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE)!=GL33C.GL_TEXTURE) return false;
            color=GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER,attachment,GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            depthType=GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER,GL33C.GL_DEPTH_ATTACHMENT,GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            depth=GL33C.glGetFramebufferAttachmentParameteri(GL33C.GL_DRAW_FRAMEBUFFER,GL33C.GL_DEPTH_ATTACHMENT,GL33C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }
        if(depth<=0 || GL33C.glIsEnabled(GL33C.GL_STENCIL_TEST)) return false;
        var capabilities=org.lwjgl.opengl.GL.getCapabilities();
        samplerObjects=capabilities.OpenGL33 || capabilities.GL_ARB_sampler_objects;
        GL33C.glGetIntegerv(GL33C.GL_VIEWPORT,savedViewport);
        GL33C.glGetDoublev(GL33C.GL_DEPTH_RANGE,savedDepthRange);
        savedClearDepth=GL33C.glGetDouble(GL33C.GL_DEPTH_CLEAR_VALUE);
        savedActiveTexture=GL33C.glGetInteger(GL33C.GL_ACTIVE_TEXTURE);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);
        savedTexture=GL33C.glGetInteger(GL33C.GL_TEXTURE_BINDING_2D);
        savedSampler=samplerObjects?GL33C.glGetInteger(GL33C.GL_SAMPLER_BINDING):0;
        targetBound=true;
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,color);
        if(width<=0 || height<=0) {
            width=GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D,0,GL33C.GL_TEXTURE_WIDTH);
            height=GL33C.glGetTexLevelParameteri(GL33C.GL_TEXTURE_2D,0,GL33C.GL_TEXTURE_HEIGHT);
        }
        if(width<=0 || height<=0) return false;
        if(framebuffer==0) framebuffer=GL33C.glGenFramebuffers();
        if(depthTexture==0) depthTexture=GL33C.glGenTextures();
        if(width!=depthWidth || height!=depthHeight) {
            int unpack=GL33C.glGetInteger(GL33C.GL_PIXEL_UNPACK_BUFFER_BINDING);
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER,0);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,depthTexture);
            GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_DEPTH_COMPONENT32F,width,height,0,GL33C.GL_DEPTH_COMPONENT,GL33C.GL_FLOAT,0L);
            textureParameters();
            GL33C.glBindBuffer(GL33C.GL_PIXEL_UNPACK_BUFFER,unpack);
            depthWidth=width;depthHeight=height;
        }
        int sampledDepth=depth;
        if(depthType!=GL33C.GL_TEXTURE) {
            if(depthCopy==0) depthCopy=GL33C.glGenTextures();
            GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER,savedDrawFramebuffer);
            GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,depthCopy);
            GL33C.glCopyTexImage2D(GL33C.GL_TEXTURE_2D,0,GL33C.GL_DEPTH_COMPONENT32F,0,0,width,height,0);
            textureParameters();sampledDepth=depthCopy;
        }
        GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER,framebuffer);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER,GL33C.GL_COLOR_ATTACHMENT0,GL33C.GL_TEXTURE_2D,color,0);
        GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER,GL33C.GL_DEPTH_ATTACHMENT,GL33C.GL_TEXTURE_2D,depthTexture,0);
        GL33C.glDrawBuffer(GL33C.GL_COLOR_ATTACHMENT0);
        if(GL33C.glCheckFramebufferStatus(GL33C.GL_FRAMEBUFFER)!=GL33C.GL_FRAMEBUFFER_COMPLETE)
            throw new IllegalStateException("Vertical LOD framebuffer incomplete");
        GL33C.glViewport(0,0,width,height);
        GL33C.glDepthRange(0,1);GL33C.glClearDepth(1);GL33C.glClear(GL33C.GL_DEPTH_BUFFER_BIT);
        GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,sampledDepth);
        if(samplerObjects) {
            if(sampler==0) {
                sampler=GL33C.glGenSamplers();
                GL33C.glSamplerParameteri(sampler,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
                GL33C.glSamplerParameteri(sampler,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
                GL33C.glSamplerParameteri(sampler,GL33C.GL_TEXTURE_COMPARE_MODE,GL33C.GL_NONE);
            }
            GL33C.glBindSampler(0,sampler);
        }
        boolean zeroToOne=(capabilities.OpenGL45 || capabilities.GL_ARB_clip_control)
            && GL33C.glGetInteger(org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE)==org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
        float range=(float)(savedDepthRange[1]-savedDepthRange[0]);
        GL33C.glUniform4f(depthInfoLocation,zeroToOne?range:range*.5f,
            (float)savedDepthRange[0]+(zeroToOne?0:range*.5f),(float)savedClearDepth,savedClearDepth>=.5?1:-1);
        GL33C.glUniform1i(clipModeLocation,zeroToOne?1:0);GL33C.glUniform1i(nativeDepthLocation,0);
        return true;
    }
    private static void textureParameters() {
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MIN_FILTER,GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_MAG_FILTER,GL33C.GL_NEAREST);
        GL33C.glTexParameteri(GL33C.GL_TEXTURE_2D,GL33C.GL_TEXTURE_COMPARE_MODE,GL33C.GL_NONE);
    }
    private void restoreTarget() {
        if(!targetBound) return;
        GL33C.glBindFramebuffer(GL33C.GL_DRAW_FRAMEBUFFER,savedDrawFramebuffer);
        GL33C.glBindFramebuffer(GL33C.GL_READ_FRAMEBUFFER,savedReadFramebuffer);
        GL33C.glViewport(savedViewport[0],savedViewport[1],savedViewport[2],savedViewport[3]);
        GL33C.glDepthRange(savedDepthRange[0],savedDepthRange[1]);GL33C.glClearDepth(savedClearDepth);
        GL33C.glActiveTexture(GL33C.GL_TEXTURE0);GL33C.glBindTexture(GL33C.GL_TEXTURE_2D,savedTexture);
        if(samplerObjects) GL33C.glBindSampler(0,savedSampler);
        GL33C.glActiveTexture(savedActiveTexture);targetBound=false;
    }
    private static void restore(int state, boolean enabled) { if(enabled) GL33C.glEnable(state); else GL33C.glDisable(state); }
    private static int shader(int type,String source) {
        int shader=GL33C.glCreateShader(type); GL33C.glShaderSource(shader,source); GL33C.glCompileShader(shader);
        if(GL33C.glGetShaderi(shader,GL33C.GL_COMPILE_STATUS)==0) throw new IllegalStateException(GL33C.glGetShaderInfoLog(shader));
        return shader;
    }
    public int bufferBytes() { return batchVertices * 28; }
    public int stagingBytes() { return staging == null ? 0 : staging.capacity() * 4; }
    public int depthBytes() { return depthWidth*depthHeight*4*(depthCopy==0?1:2); }
    @Override public void close() {
        if(program!=0) GL33C.glDeleteProgram(program);
        if(batchBuffer!=0) GL33C.glDeleteBuffers(batchBuffer);
        if(batchVao!=0) GL33C.glDeleteVertexArrays(batchVao);
        if(staging!=null) MemoryUtil.memFree(staging);
        if(framebuffer!=0) GL33C.glDeleteFramebuffers(framebuffer);
        if(depthTexture!=0) GL33C.glDeleteTextures(depthTexture);
        if(depthCopy!=0) GL33C.glDeleteTextures(depthCopy);
        if(sampler!=0) GL33C.glDeleteSamplers(sampler);
        framebuffer=depthTexture=depthCopy=sampler=depthWidth=depthHeight=0;
        nativeColor=nativeDepth=targetWidth=targetHeight=0;
        program=batchBuffer=batchVao=batchVertices=batchCount=0; staging=null;
        java.util.Arrays.fill(drawMeshes,null); drawCount=0;
    }
}

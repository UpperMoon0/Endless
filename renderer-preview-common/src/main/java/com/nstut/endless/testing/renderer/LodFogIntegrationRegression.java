package com.nstut.endless.testing.renderer;

import org.lwjgl.opengl.GL33C;
import java.util.function.Function;

/** Exercise the actual optional renderer's transformed parser and program bind. */
public final class LodFogIntegrationRegression {
    public static void verify() {
        String prefix=null;
        Class<?> parser=null;
        for(String candidate:new String[]{"net.caffeinemc.mods.sodium.client.gl.shader.",
            "me.jellysquid.mods.sodium.client.gl.shader.","org.embeddedt.embeddium.impl.gl.shader."}) {
            try { parser=Class.forName(candidate+"ShaderParser");prefix=candidate;break; }
            catch(ClassNotFoundException absent) { }
        }
        if(parser==null) throw new IllegalStateException("Native LOD fog parser missing");
        int previous=GL33C.glGetInteger(GL33C.GL_CURRENT_PROGRAM),program=0;
        try {
            String source="float getFragDistance(int shape, vec3 point) { return max(length(point.xz),abs(point.y)); }";
            String parsed;
            try { parsed=parser.getMethod("parseShader",String.class).invoke(null,source).toString(); }
            catch(NoSuchMethodException modern) {
                var ctor=parser.getDeclaredConstructor();ctor.setAccessible(true);
                Object instance=ctor.newInstance();
                parser.getMethod("parseShader",String.class,String.class).invoke(instance,"endless-lod-test",source);
                var finish=parser.getDeclaredMethod("finish");finish.setAccessible(true);
                Object result=finish.invoke(instance);
                var sources=result.getClass().getDeclaredMethod("src");sources.setAccessible(true);
                parsed=sources.invoke(result).toString();
            }
            if(!parsed.contains("uniform int endlessVerticalLod;") || !parsed.contains("return length(point.xz);"))
                throw new IllegalStateException("Native renderer did not adapt vertical fog: "+prefix);
            int vertex=compile(GL33C.GL_VERTEX_SHADER,"#version 150\nuniform int endlessVerticalLod; void main(){gl_Position=vec4(float(endlessVerticalLod),0,0,1);}");
            int fragment=compile(GL33C.GL_FRAGMENT_SHADER,"#version 150\nout vec4 color;void main(){color=vec4(1);}");
            program=GL33C.glCreateProgram();GL33C.glAttachShader(program,vertex);GL33C.glAttachShader(program,fragment);
            GL33C.glLinkProgram(program);GL33C.glDeleteShader(vertex);GL33C.glDeleteShader(fragment);
            if(GL33C.glGetProgrami(program,GL33C.GL_LINK_STATUS)==0) throw new IllegalStateException(GL33C.glGetProgramInfoLog(program));
            Class<?> type=Class.forName(prefix+"GlProgram");
            var ctor=type.getDeclaredConstructor(int.class,Function.class);ctor.setAccessible(true);
            Object wrapper=ctor.newInstance(program,(Function<Object,Object>)(ignored->null));
            type.getMethod("bind").invoke(wrapper);
            boolean enabled=(boolean)Class.forName("com.nstut.endless.vertical.lod.VerticalLodFogUniform").getMethod("enabled").invoke(null);
            int actual=GL33C.glGetUniformi(program,GL33C.glGetUniformLocation(program,"endlessVerticalLod"));
            if(actual!=(enabled?1:0)) throw new IllegalStateException("Native LOD fog bind missing: "+prefix);
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException("Native LOD fog integration changed",failure); }
        finally { GL33C.glUseProgram(previous);if(program!=0) { com.nstut.endless.vertical.lod.VerticalLodFogUniform.forget(program); GL33C.glDeleteProgram(program); } }
    }
    private static int compile(int type,String source) {
        int shader=GL33C.glCreateShader(type);GL33C.glShaderSource(shader,source);GL33C.glCompileShader(shader);
        if(GL33C.glGetShaderi(shader,GL33C.GL_COMPILE_STATUS)==0) throw new IllegalStateException(GL33C.glGetShaderInfoLog(shader));
        return shader;
    }
}

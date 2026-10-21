package com.nstut.endless.vertical.lod;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Add an opt-in horizontal distance to recognized native terrain fog helpers. */
public final class VerticalLodFog {
    public static final String UNIFORM = "endlessVerticalLod";
    private static final Pattern FUNCTION = Pattern.compile("(float|vec2)\\s+(fog_distance|getFragDistance|fog_cylindrical_distance)\\s*\\(([^)]*)\\)\\s*\\{");
    private static final Pattern POSITION = Pattern.compile("vec3\\s+(\\w+)");
    private static final Pattern SHAPE = Pattern.compile("int\\s+(\\w+)");
    private VerticalLodFog() {}

    public static String adapt(String source) {
        if(source.contains(UNIFORM)) return source;
        Matcher functions=FUNCTION.matcher(source);
        StringBuffer result=new StringBuffer();
        boolean changed=false;
        while(functions.find()) {
            Matcher position=POSITION.matcher(functions.group(3)),shape=SHAPE.matcher(functions.group(3));
            if(!position.find()) continue;
            boolean cylinder=functions.group(2).equals("fog_cylindrical_distance");
            boolean paired=functions.group(1).equals("vec2") && functions.group(2).equals("getFragDistance");
            if(!cylinder && !paired && !shape.find()) continue;
            String condition=UNIFORM+" != 0"+(cylinder||paired?"":" && "+shape.group(1)+" == 1");
            String distance="length("+position.group(1)+".xz)";
            if(paired) distance="vec2("+distance+",length("+position.group(1)+"))";
            functions.appendReplacement(result,Matcher.quoteReplacement(functions.group()+"\n    if ("+condition+") return "+distance+";\n"));
            changed=true;
        }
        if(!changed) return source;
        functions.appendTail(result);
        String declaration="uniform int "+UNIFORM+";\n";
        String patched=result.toString();
        // Imported includes may carry their own version; keep that directive first.
        Matcher version=Pattern.compile("(?m)^\\s*#version[^\\r\\n]*(?:\\r?\\n)(?:[\\t ]*(?:#extension[^\\r\\n]*)?\\r?\\n)*").matcher(patched);
        if(version.find()) return patched.substring(0,version.end())+declaration+patched.substring(version.end());
        return declaration+patched;
    }
}

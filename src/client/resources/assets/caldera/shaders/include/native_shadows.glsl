#ifndef CALDERA_NATIVE_SHADOWS
#define CALDERA_NATIVE_SHADOWS
#ifndef SHADOW_QUALITY
#define SHADOW_QUALITY 2
#endif
#if SHADOW_QUALITY > 0
#define CALDERA_SHADOW_FILTER_TIER SHADOW_QUALITY
uniform sampler2D CalderaShadowMap0;
uniform sampler2D CalderaShadowMap1;
uniform sampler2D CalderaShadowMap2;
uniform sampler2D CalderaShadowMap3;
uniform sampler2D CalderaEntityShadowMap0;
uniform sampler2D CalderaEntityShadowMap1;
layout(std140) uniform CalderaShadowData {
    mat4 ShadowViewProjection[4];
    vec4 CascadeParams[4];
    vec4 LightDirection;
    vec4 ShadowParams;
};
#include "caldera/shadow-filter.glsl"
float calderaTerrainShadow(vec3 position, vec3 normal) {
    return mix(1.0, calderaDirectionalVisibility(position, normal), ShadowParams.x);
}

float calderaDirectShadow(vec3 position, vec3 normal) {
    return calderaCastShadowVisibility(position,normal);
}
float calderaGrassShadow(vec3 position, vec3 normal) {
    
    
    float visibility=calderaCastShadowVisibility(position,normal,true);
    
    
    return mix(1.0,mix(ShadowParams.z,1.0,visibility),ShadowParams.x);
}

float calderaVolumeShadow(vec3 position) {
    int cascade=calderaSelectCascade(length(position));
    int last=calderaLastCascade();
    if(length(position)>=CascadeParams[last].z) return 1.0;
    vec3 coord=calderaShadowCoord(cascade,position);
    if(any(lessThan(coord,vec3(0))) || any(greaterThan(coord,vec3(1)))) return 1.0;
    float visible=calderaShadowBilinearVisibility(cascade,coord.xy,coord.xy,coord.z,vec2(0));
    if(cascade==0) visible=min(visible,calderaEntityBilinearVisibility(cascade,coord.xy,coord.xy,coord.z,vec2(0)));
    float coverage=1.0-smoothstep(mix(CascadeParams[last].y,CascadeParams[last].z,0.88),CascadeParams[last].z,length(position));
    return mix(1.0,visible,coverage);
}

#else
float calderaTerrainShadow(vec3 position, vec3 normal) { return 1.0; }
float calderaDirectShadow(vec3 position, vec3 normal) { return 1.0; }
float calderaGrassShadow(vec3 position, vec3 normal) { return 1.0; }
float calderaVolumeShadow(vec3 position) { return 1.0; }
#endif
#endif

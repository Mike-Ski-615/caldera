#ifndef CALDERA_SHADOW_CASCADE_COUNT
#define CALDERA_SHADOW_CASCADE_COUNT 4
#endif
#ifndef CALDERA_SHADOW_FILTER_TIER
#define CALDERA_SHADOW_FILTER_TIER 3
#endif

bool calderaCascadeActive(int cascade) {
	return CascadeParams[cascade].w > 0.5;
}

int calderaLastCascade() {
	for (int cascade = CALDERA_SHADOW_CASCADE_COUNT - 1; cascade >= 0; --cascade) {
		if (calderaCascadeActive(cascade)) return cascade;
	}
	return 0;
}

int calderaSelectCascade(float cameraDistance) {
	int last = calderaLastCascade();
	for (int cascade = 0; cascade < CALDERA_SHADOW_CASCADE_COUNT; ++cascade) {
		if (calderaCascadeActive(cascade) && cameraDistance <= CascadeParams[cascade].z) return cascade;
	}
	return last;
}

float calderaShadowDepth(int cascade, vec2 uv) {
	if (cascade == 0) return textureLod(CalderaShadowMap0, uv, 0.0).r;
	if (cascade == 1) return textureLod(CalderaShadowMap1, uv, 0.0).r;
	if (cascade == 2) return textureLod(CalderaShadowMap2, uv, 0.0).r;
	return textureLod(CalderaShadowMap3, uv, 0.0).r;
}

vec4 calderaShadowGather(int cascade, vec2 uv) {
	if (cascade == 0) return textureGather(CalderaShadowMap0, uv);
	if (cascade == 1) return textureGather(CalderaShadowMap1, uv);
	if (cascade == 2) return textureGather(CalderaShadowMap2, uv);
	return textureGather(CalderaShadowMap3, uv);
}

#ifndef CALDERA_TERRAIN_SHADOWS_ONLY
vec4 calderaEntityShadowGather(int cascade, vec2 uv) {
	return textureGather(CalderaEntityShadowMap0, uv);
}

vec2 calderaEntityShadowTexel(int cascade) {
	return 1.0 / vec2(textureSize(CalderaEntityShadowMap0, 0));
}

#endif

vec2 calderaReceiverDepthGradient(int cascade, vec3 normal) {
	
	
	
	vec3 axis = abs(normal.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
	vec3 tangent = normalize(cross(normal, axis));
	vec3 bitangent = cross(normal, tangent);
	vec3 projectedTangent = mat3(ShadowViewProjection[cascade]) * tangent;
	vec3 projectedBitangent = mat3(ShadowViewProjection[cascade]) * bitangent;
	vec2 uvDx = projectedTangent.xy * 0.5;
	vec2 uvDy = projectedBitangent.xy * 0.5;
	float depthDx = projectedTangent.z;
	float depthDy = projectedBitangent.z;
	float determinant = uvDx.x * uvDy.y - uvDx.y * uvDy.x;
	if (abs(determinant) < 1.0e-10) return vec2(0.0);
	vec2 gradient = vec2(
		(depthDx * uvDy.y - depthDy * uvDx.y) / determinant,
		(uvDx.x * depthDy - uvDy.x * depthDx) / determinant);
	return clamp(gradient, vec2(-2.0), vec2(2.0));
}

vec4 calderaGatherCompareDepths(
		vec2 uv,
		vec2 texel,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 size = 1.0 / texel;
	vec2 lowerLeftUv = (floor(uv * size - 0.5) + 0.5) * texel;
	float lowerLeft = receiverDepth + dot(depthGradient, lowerLeftUv - receiverUv);
	float lowerRight = receiverDepth + dot(depthGradient, lowerLeftUv + vec2(texel.x, 0.0) - receiverUv);
	float upperLeft = receiverDepth + dot(depthGradient, lowerLeftUv + vec2(0.0, texel.y) - receiverUv);
	float upperRight = receiverDepth + dot(depthGradient, lowerLeftUv + texel - receiverUv);
	
	return vec4(upperLeft, upperRight, lowerRight, lowerLeft);
}

#ifndef CALDERA_TERRAIN_SHADOWS_ONLY
float calderaEntityBilinearVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = calderaEntityShadowTexel(cascade);
	vec2 size = 1.0 / texel;
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = calderaEntityShadowGather(cascade, uv);
	vec4 compareDepths = calderaGatherCompareDepths(uv, texel, receiverUv, receiverDepth, depthGradient);
	vec4 visible = step(compareDepths, depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float calderaEntityShadowVisibility(
		int cascade,
		vec2 uv,
		float compareDepth,
		vec2 depthGradient) {
#if CALDERA_SHADOW_FILTER_TIER <= 1
		return calderaEntityBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
#else

	vec2 texel = calderaEntityShadowTexel(cascade);
	float radius = CALDERA_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = calderaEntityBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += calderaEntityBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += calderaEntityBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners += calderaEntityBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners *= 0.25;
#if CALDERA_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = calderaEntityBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
	return mix(center, corners, 0.65);
#endif
#endif
}

#endif

vec2 calderaShadowTexel(int cascade) {
	if (cascade == 0) return 1.0 / vec2(textureSize(CalderaShadowMap0, 0));
	if (cascade == 1) return 1.0 / vec2(textureSize(CalderaShadowMap1, 0));
	if (cascade == 2) return 1.0 / vec2(textureSize(CalderaShadowMap2, 0));
	return 1.0 / vec2(textureSize(CalderaShadowMap3, 0));
}

float calderaShadowBilinearVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = calderaShadowTexel(cascade);
	vec2 size = 1.0 / texel;
	vec2 blend = fract(uv * size - 0.5);
	vec4 depths = calderaShadowGather(cascade, uv);
	vec4 compareDepths = calderaGatherCompareDepths(uv, texel, receiverUv, receiverDepth, depthGradient);
	vec4 visible = step(compareDepths, depths);
	float lower = mix(visible.w, visible.z, blend.x);
	float upper = mix(visible.x, visible.y, blend.x);
	return mix(lower, upper, blend.y);
}

float calderaShadowPointVisibility(
		int cascade,
		vec2 uv,
		vec2 receiverUv,
		float receiverDepth,
		vec2 depthGradient) {
	vec2 texel = calderaShadowTexel(cascade);
	vec2 sampleUv = (floor(uv / texel) + 0.5) * texel;
	float tapCompareDepth = receiverDepth + dot(depthGradient, sampleUv - receiverUv);
	return step(tapCompareDepth, calderaShadowDepth(cascade, sampleUv));
}

float calderaCloseTerrainVisibility(int cascade, vec2 uv, float compareDepth, vec2 depthGradient) {
#if CALDERA_SHADOW_FILTER_TIER <= 1
		return calderaShadowBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
#else

	vec2 texel = calderaShadowTexel(cascade);
	float radius = CALDERA_SHADOW_FILTER_TIER < 4 ? 0.55 : 0.85;
	vec2 offset = texel * radius;
	float corners = calderaShadowBilinearVisibility(cascade, uv + vec2(-offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += calderaShadowBilinearVisibility(cascade, uv + vec2( offset.x, -offset.y), uv, compareDepth, depthGradient);
	corners += calderaShadowBilinearVisibility(cascade, uv + vec2(-offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners += calderaShadowBilinearVisibility(cascade, uv + vec2( offset.x,  offset.y), uv, compareDepth, depthGradient);
	corners *= 0.25;
#if CALDERA_SHADOW_FILTER_TIER < 4
	return corners;
#else

	float center = calderaShadowBilinearVisibility(cascade, uv, uv, compareDepth, depthGradient);
	return mix(center, corners, 0.65);
#endif
#endif
}

vec3 calderaShadowCoord(int cascade, vec3 position) {
	vec4 clip = ShadowViewProjection[cascade] * vec4(position, 1.0);
	return vec3(clip.xy / max(abs(clip.w), 0.000001) * 0.5 + 0.5, clip.z / max(abs(clip.w), 0.000001));
}

float calderaShadowNormalOffset(int cascade, vec3 normal) {
	float facing = clamp(dot(normal, LightDirection.xyz), 0.0, 1.0);
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	
	
	
	return clamp(texelWorldSize * mix(0.06, 0.14, 1.0 - facing), 0.0005, 0.020);
}

float calderaCascadeVisibility(int cascade, vec3 position, vec3 normal, bool grassReceiver) {
	float texelWorldSize = max(CascadeParams[cascade].x, 0.0001);
	vec3 coord = calderaShadowCoord(cascade, position + normal * calderaShadowNormalOffset(cascade, normal));
	vec2 texel = calderaShadowTexel(cascade);
	#ifdef CALDERA_TERRAIN_SHADOWS_ONLY
	vec2 entityTexel = vec2(0.0);
#else
	vec2 entityTexel = cascade == 0 ? calderaEntityShadowTexel(cascade) : vec2(0.0);
#endif
	vec2 guard = max(texel, entityTexel) * 1.5;
	if (coord.x <= guard.x || coord.x >= 1.0 - guard.x
			|| coord.y <= guard.y || coord.y >= 1.0 - guard.y
			|| coord.z <= 0.0 || coord.z >= 1.0) return 1.0;

	
	
	float precisionBias = (0.0005 + texelWorldSize * 0.015) / max(CascadeParams[cascade].w, 1.0);
	float compareDepth = coord.z - precisionBias;
	vec2 depthGradient = calderaReceiverDepthGradient(cascade, normal);
	float visible;
    
    
    if(grassReceiver) {
        
        
        
        
        float facing=clamp(abs(dot(normal,normalize(LightDirection.xyz))),0.0,1.0);
        float slope=sqrt(max(1.0-facing*facing,0.0))/max(facing,0.2);
        float allowance=clamp(texelWorldSize*(0.65+0.7*slope),0.015,0.16);
        float grassDepth=compareDepth-allowance/max(CascadeParams[cascade].w,1.0);
        visible=calderaShadowBilinearVisibility(cascade,coord.xy,coord.xy,grassDepth,vec2(0));
    } else {
        visible = calderaCloseTerrainVisibility(cascade, coord.xy, compareDepth, depthGradient);
    }
	#ifdef CALDERA_TERRAIN_SHADOWS_ONLY
	return visible;
#else
	float entityVisible = cascade == 0
		? calderaEntityShadowVisibility(cascade, coord.xy, compareDepth, depthGradient)
		: 1.0;
	return min(visible, entityVisible);
#endif
}

float calderaCascadeVisibility(int cascade, vec3 position, vec3 normal) {
    return calderaCascadeVisibility(cascade,position,normal,false);
}

float calderaCastShadowVisibility(vec3 position, vec3 normal, bool grassReceiver) {
	float cameraDistance = length(position);
	int current = calderaSelectCascade(cameraDistance);
	int last = calderaLastCascade();
	if (cameraDistance >= CascadeParams[last].z) return 1.0;
	float currentVisibility = calderaCascadeVisibility(current, position, normal, grassReceiver);

	if (current == last) {
		float fadeStart = mix(CascadeParams[last].y, CascadeParams[last].z, 0.88);
		float coverage = 1.0 - smoothstep(fadeStart, CascadeParams[last].z, cameraDistance);
		return mix(1.0, currentVisibility, coverage);
	}

	float blendStart = mix(CascadeParams[current].y, CascadeParams[current].z, 0.90);
	float blend = smoothstep(blendStart, CascadeParams[current].z, cameraDistance);
	if (blend <= 0.0) return currentVisibility;
	return mix(currentVisibility, calderaCascadeVisibility(current + 1, position, normal, grassReceiver), blend);
}

float calderaCastShadowVisibility(vec3 position, vec3 normal) {
    return calderaCastShadowVisibility(position,normal,false);
}

float calderaDirectionalVisibility(vec3 position, vec3 normal) {
	float normalLight = dot(normal, LightDirection.xyz);
	if (normalLight <= 0.0) return ShadowParams.z;
	float faceLit = smoothstep(0.0, 0.08, normalLight);
	float faceVisibility = mix(ShadowParams.z, 1.0, faceLit);
	float castVisibility = mix(ShadowParams.z, 1.0, calderaCastShadowVisibility(position, normal));
	return min(faceVisibility, castVisibility);
}

#version 460 core

#define CALDERA_GLOBALS_BINDING 2
#moj_import <caldera:sodium_globals.glsl>
#moj_import <caldera:terrain_material.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 1) uniform sampler2D u_BlockTex;
layout(binding = 3) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) in vec2 v_TexCoord;
layout(location = 1) flat in uint v_Material;
layout(location = 2) in float v_Alpha;
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
	
	
	float alpha = calderaSampleTerrainMaterial(u_BlockTex, v_TexCoord, v_Material).a * v_Alpha;
	if (alpha < calderaMaterialAlphaCutoff(v_Material)) discard;
#endif
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}

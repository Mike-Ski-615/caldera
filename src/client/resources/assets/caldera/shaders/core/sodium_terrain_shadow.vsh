#version 460 core

#ifdef CALDERA_VEGETATION
layout(location=4) in uint a_CalderaMaterial;
#moj_import <caldera:vegetation_wind.glsl>
#endif

#define CALDERA_GLOBALS_BINDING 2
#moj_import <caldera:sodium_globals.glsl>
#moj_import <sodium:chunk_vertex.glsl>

layout(binding = 0) uniform sampler2D u_LightTex;
layout(binding = 3) uniform isamplerBuffer u_SectionTimeInfo;
layout(location = 0) out vec2 v_TexCoord;
layout(location = 1) flat out uint v_Material;
layout(location = 2) out float v_Alpha;

#ifdef VULKAN
layout(push_constant) uniform PC {
	vec3 u_RegionOffset;
	int u_CurrentTime;
	uint u_RegionID;
};
#else
uniform vec3 u_RegionOffset;
uniform int u_CurrentTime;
uniform uint u_RegionID;
#endif

uvec3 calderaRelativeChunkCoord(uint drawId) {
	return uvec3(drawId) >> uvec3(5u, 0u, 2u) & uvec3(7u, 3u, 7u);
}

void main() {
	_vert_init();
	vec3 translation = u_RegionOffset + calderaRelativeChunkCoord(_draw_id) * vec3(16.0);
	vec3 position = _vert_position + translation;
#ifdef CALDERA_VEGETATION
	position += calderaWindOffset(position, a_CalderaMaterial);
#endif
	gl_Position = u_ProjectionMatrix * u_ModelViewMatrix * vec4(position, 1.0);
	v_TexCoord = _vert_tex_diffuse_coord + _vert_tex_diffuse_coord_bias * u_TexCoordShrink;
	v_Material = _material_params;
	v_Alpha = _vert_color.a;
}

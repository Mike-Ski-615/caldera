#version 460 core

layout(binding = 0) uniform sampler2D Sampler0;
layout(location = 0) in vec2 texCoord0;
layout(location = 0) out vec4 fragColor;

void main() {
#ifdef ALPHA_CUTOUT
	if (texture(Sampler0, texCoord0).a < float(ALPHA_CUTOUT)) discard;
#endif
	fragColor = vec4(0.0, 0.0, 0.0, 1.0);
}
